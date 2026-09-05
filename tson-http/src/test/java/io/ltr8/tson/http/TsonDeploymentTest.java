package io.ltr8.tson.http;

import io.ltr8.tson.TsonConfig;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deployment descriptor: what it reads, what it applies, and what it will not hand out. */
class TsonDeploymentTest {

    private static final String FULL = """
            !!schema:"https://tson.io/2026/35/ltr8/http/deployment-1.tn"
            !deployment {
              name:         "production"
              listener:     { host: "127.0.0.1"  port: 8080 }
              identifiers:  { level: HIGHLY_RESTRICTIVE  unit: SEGMENT }
              tokens:       { level: MODERATELY_RESTRICTIVE  permitting: ["Latn" "cyrillic"] }
              schema_hosts: ["schemas.example.com"]
            }""";

    @Test
    void aDescriptorReadsAsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals("production", deployment.name());
        assertEquals(8080, deployment.listener().orElseThrow().port().orElseThrow());
        assertEquals(List.of("schemas.example.com"), deployment.schemaHosts());
        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE,
                deployment.identifiers().orElseThrow().level());
        assertEquals(TsonDeployment.Unit.SEGMENT, deployment.identifiers().orElseThrow().unit().orElseThrow());
    }

    /** The unit and the script set survive into the library's own type, which is the point of carrying them. */
    @Test
    void theUnitAndTheScriptSetReachTheLibraryPolicy() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        TsonUnicodePolicy identifiers = deployment.identifierPolicy().orElseThrow();
        assertTrue(identifiers.isPerSegment(), "SEGMENT should reach perSegment()");

        // `permitting` is the narrowest relaxation §8.2 offers: Cyrillic beside Latin, without dropping a
        // level and losing the rule everywhere else. A mixed Latin/Cyrillic name is refused at Moderately
        // Restrictive and admitted once that combination is named.
        TsonUnicodePolicy tokens = deployment.tokenPolicy().orElseThrow();
        assertTrue(tokens.violation("аdmin").isEmpty(),
                () -> "LATIN+CYRILLIC was permitted: " + tokens.violation("аdmin").orElse(""));
        assertTrue(TsonUnicodePolicy.moderatelyRestrictive().violation("аdmin").isPresent(),
                "and is refused without it, or this asserts nothing");
    }

    /**
     * <b>An absent policy leaves the library's default alone rather than meaning "no policy".</b> The two
     * defaults point opposite ways for reasons §8.2 gives, so overwriting an unstated one with something
     * permissive would be a decision nobody made.
     */
    @Test
    void anAbsentPolicyIsNotAPermissiveOne() {
        TsonDeployment deployment = TsonDeployment.read("""
                !!schema:"https://tson.io/2026/35/ltr8/http/deployment-1.tn"
                !deployment { name: "minimal" }""");

        assertTrue(deployment.identifierPolicy().isEmpty());
        assertTrue(deployment.tokenPolicy().isEmpty());
        assertEquals(List.of(), deployment.schemaHosts(), "an omitted list is empty, not null");

        // applyTo leaves a config untouched, which is only observable through what it does not throw.
        TsonConfig config = io.ltr8.tson.Tson.builder();
        assertEquals(config, deployment.applyTo(config));
    }

    /**
     * <b>The profile is derived, and drops what a counterparty has no business seeing.</b> Which origins a
     * deployment will fetch schemas from is internal topology; publishing it would hand an attacker the
     * allow-list to aim at.
     */
    @Test
    void theProfileCarriesThePoliciesAndNotTheTrustConfiguration() {
        TsonDeployment.AcceptanceProfile profile = TsonDeployment.read(FULL).profile();

        assertEquals("production", profile.name());
        assertEquals(TsonUnicodePolicy.Level.HIGHLY_RESTRICTIVE, profile.identifiers().orElseThrow().level());
        assertEquals(TsonUnicodePolicy.Level.MODERATELY_RESTRICTIVE, profile.tokens().orElseThrow().level());

        // Nothing about what this deployment trusts, and nothing about where it listens.
        String written = TsonDeployment.tson().objectWriter()
                .describing(TsonDeployment.ID, "acceptance_profile").toTson(profile);
        assertFalse(written.contains("schemas.example.com"), written);
        assertFalse(written.contains("127.0.0.1"), written);
        assertFalse(written.contains("8080"), written);
    }

    /**
     * <b>The profile states the data version, read from the library rather than copied.</b> §8.3 marks all
     * three of §8.2's rules unstable across Unicode releases, so two conforming processors may legitimately
     * disagree about one name and the version is what explains it. A refusal does not name it; the profile
     * states it once, for a client that would rather know before it sends than after it is refused.
     *
     * <p>This test was the flipped assertion of a gap: it asserted the version was <em>unavailable</em>, so
     * that the day it became obtainable it would say so by failing. It did.
     */
    @Test
    void theProfileStatesTheUnicodeDataVersion() {
        assertEquals(Optional.of(TsonUnicodePolicy.dataVersion()),
                TsonDeployment.read(FULL).profile().unicodeDataVersion());
        // Read, not copied: a constant here would go stale silently on a library upgrade.
        assertFalse(TsonUnicodePolicy.dataVersion().isBlank());
    }

    /**
     * <b>Script names are canonicalised against the authoritative table, not by the schema.</b> [UAX #24]
     * gives every script a long alias and an ISO 15924 short alias, matched case-insensitively, so {@code
     * Latn} and {@code cyrillic} name the same two scripts as {@code LATIN} and {@code CYRILLIC}. The schema
     * cannot enforce membership — 171 values that grow with the UCD have no place in a published immutable
     * document — so it constrains shape and this is where a name becomes a script.
     */
    @Test
    void scriptNamesAreCanonicalisedWhicheverAliasIsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals(List.of("LATIN", "CYRILLIC"), deployment.tokens().orElseThrow().permitting());
        // And the projection publishes the canonical form, so two deployments naming one set look alike.
        assertEquals(List.of("LATIN", "CYRILLIC"), deployment.profile().tokens().orElseThrow().permitting());
    }

    /**
     * A typo in a security setting stops the read, rather than being carried as a policy quietly missing a
     * script. {@code Cyrrilic} is well-shaped, so {@code script_name}'s pattern admits it and only the
     * authoritative table can refuse it.
     */
    @Test
    void anUnknownScriptNameStopsTheRead() {
        String message = assertThrows(RuntimeException.class, () -> TsonDeployment.read("""
                !!schema:"https://tson.io/2026/35/ltr8/http/deployment-1.tn"
                !deployment { name: "typo"  tokens: { level: SINGLE_SCRIPT  permitting: ["Cyrrilic"] } }"""))
                .getMessage();

        assertTrue(message.contains("Cyrrilic"), message);
    }

    /**
     * And the shape rule catches what it can before that: a script name is a name, so a bare number is not
     * one. Worth pinning because a {@code text} field would have accepted it — [TSON-DATA] §4 does not apply
     * base type resolution at a schema-typed position, so {@code 42} is a perfectly good {@code text}.
     */
    @Test
    void somethingThatIsNotEvenNameShapedIsRefusedByTheSchema() {
        List<io.ltr8.tson.compiler.Diagnostic> problems = TsonDeployment.tson().validate("""
                !!schema:"https://tson.io/2026/35/ltr8/http/deployment-1.tn"
                !deployment { name: "n"  tokens: { level: SINGLE_SCRIPT  permitting: [42] } }""");

        assertEquals(List.of(io.ltr8.tson.compiler.Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                problems.stream().map(io.ltr8.tson.compiler.Diagnostic::code).toList(),
                () -> "expected the pattern to refuse it, got " + problems);
    }

    /**
     * {@code restriction_level} is a hand-written copy of {@link TsonUnicodePolicy.Level}, and nothing else
     * checks the copy is current — the same discipline {@code problem-1.tn}'s {@code diagnostic_code} gets,
     * and for the same reason: a level added upstream would be unreadable by a descriptor that names it.
     */
    @Test
    void everyRestrictionLevelIsDeclaredInTheSchema() {
        TypeDefinition entry = TsonDeployment.compiled().schema().entries().get("restriction_level");
        List<String> declared = assertInstanceOf(EnumBody.class, entry.body(), "an enum").members();

        assertEquals(Arrays.stream(TsonUnicodePolicy.Level.values()).map(Enum::name).toList(), declared,
                "restriction_level copies TsonUnicodePolicy.Level, in order");
    }
}
