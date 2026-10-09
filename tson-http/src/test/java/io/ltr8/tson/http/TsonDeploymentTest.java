package io.ltr8.tson.http;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.ScriptPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deployment descriptor: what it reads, what it applies, and what it will not hand out. */
class TsonDeploymentTest {

    private static final String FULL = """
            !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
            !deployment {
              name:         "production"
              listener:     { host: "127.0.0.1"  port: 8080 }
              identifiers:  { level: HIGHLY_RESTRICTIVE  unit: SEGMENT  skeleton_distinctness: false }
              tokens:       { level: MODERATELY_RESTRICTIVE  permitting: ["Latn" "cyrillic"] }
              schema_hosts: ["schemas.example.com"]
            }""";

    @Test
    void aDescriptorReadsAsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals("production", deployment.name());
        assertEquals(8080, deployment.listener().orElseThrow().port().orElseThrow());
        assertEquals(List.of("schemas.example.com"), deployment.schemaHosts());
        assertEquals(ScriptPolicy.Level.HIGHLY_RESTRICTIVE,
                deployment.identifiers().orElseThrow().level());
        assertEquals(TsonDeployment.Unit.SEGMENT, deployment.identifiers().orElseThrow().unit().orElseThrow());
    }

    /**
     * The unit, the look-alike switch and the script set survive into the library's own types, which is the
     * point of carrying them.
     */
    @Test
    void theUnitTheSwitchAndTheScriptSetReachTheLibraryPolicy() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        IdentifierPolicy identifiers = deployment.identifierPolicy().orElseThrow();
        assertTrue(identifiers.isPerSegment(), "SEGMENT should reach perSegment()");
        assertFalse(identifiers.appliesSkeletonDistinctness(), "an explicit false should switch the rule off");

        // `permitting` is the narrowest relaxation §8.2 offers: Cyrillic beside Latin, without dropping a
        // level and losing the rule everywhere else. A mixed Latin/Cyrillic name is refused at Moderately
        // Restrictive and admitted once that combination is named.
        ScriptPolicy tokens = deployment.tokenPolicy().orElseThrow();
        assertTrue(tokens.violation("аdmin").isEmpty(),
                () -> "LATIN+CYRILLIC was permitted: " + tokens.violation("аdmin").orElse(""));
        assertTrue(ScriptPolicy.moderatelyRestrictive().violation("аdmin").isPresent(),
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
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
                !deployment { name: "minimal" }""");

        assertTrue(deployment.identifierPolicy().isEmpty());
        assertTrue(deployment.tokenPolicy().isEmpty());
        assertEquals(List.of(), deployment.schemaHosts(), "an omitted list is empty, not null");

        // applyTo leaves a config untouched, which is only observable through what it does not throw.
        ProcessorConfig config = ProcessorConfig.defaults();
        assertEquals(config, deployment.applyTo(config));
    }

    /** The same holds one level down: a level stated with no switch leaves the look-alike rule on. */
    @Test
    void anUnstatedSwitchLeavesTheLookAlikeRuleOn() {
        TsonDeployment deployment = TsonDeployment.read("""
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
                !deployment { name: "level-only"  identifiers: { level: MODERATELY_RESTRICTIVE } }""");

        assertTrue(deployment.identifierPolicy().orElseThrow().appliesSkeletonDistinctness());
    }

    /**
     * <b>A token policy has no unit to write.</b> A value has no segments — {@code _} and {@code -} separate
     * a name's words and are ordinary characters in a value, so segmenting one would admit UTS #39's own
     * {@code Toys-Я-Us} — and the library makes a per-segment token policy unwritable. The schema follows it,
     * which is why {@code identifier_override} writes its fields out rather than composing {@code
     * token_override}: a composition would make one admissible where the other is expected.
     */
    @Test
    void aTokenPolicyCannotBeGivenAUnit() {
        List<io.ltr8.tson.base.Diagnostic> problems = TsonDeployment.tson().validate("""
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
                !deployment { name: "n"  tokens: { level: HIGHLY_RESTRICTIVE  unit: SEGMENT } }""");

        assertEquals(List.of(io.ltr8.tson.base.Diagnostic.Code.UNRECOGNIZED_FIELD),
                problems.stream().map(io.ltr8.tson.base.Diagnostic::code).toList(),
                () -> "expected the unit to be refused, got " + problems);
    }

    /** The profile of a processor configured from the library's defaults with {@code deployment} applied. */
    private static TsonDeployment.AcceptanceProfile profileOf(TsonDeployment deployment) {
        return deployment.profile(deployment.applyTo(ProcessorConfig.defaults()).processorPolicy());
    }

    /**
     * <b>The profile is derived, and drops what a counterparty has no business seeing.</b> Which origins a
     * deployment will fetch schemas from is internal topology; publishing it would hand an attacker the
     * allow-list to aim at.
     */
    @Test
    void theProfileCarriesThePolicyAndNotTheTrustConfiguration() {
        TsonDeployment.AcceptanceProfile profile = profileOf(TsonDeployment.read(FULL));

        assertEquals("production", profile.name());
        TsonDeployment.Policy.Identifiers identifiers = profile.policy().identifierPolicy();
        assertEquals(ScriptPolicy.Level.HIGHLY_RESTRICTIVE, identifiers.level());
        assertTrue(identifiers.perSegment());
        assertFalse(identifiers.skeletonDistinctness());
        assertEquals(ScriptPolicy.Level.MODERATELY_RESTRICTIVE, profile.policy().tokenPolicy().level());

        // Nothing about what this deployment trusts, and nothing about where it listens.
        String written = TsonDeployment.tson().objectWriter()
                .describing(TsonDeployment.ID, "acceptance_profile").toTson(profile);
        assertFalse(written.contains("schemas.example.com"), written);
        assertFalse(written.contains("127.0.0.1"), written);
        assertFalse(written.contains("8080"), written);
    }

    /**
     * <b>The profile states the whole policy in force, defaults included.</b> A descriptor states only what it
     * changes, so a profile built from it would leave a client to know what the library defaults to; one built
     * from what is enforced says it.
     */
    @Test
    void aPartTheDescriptorLeavesAloneIsStatedAtTheDefault() {
        TsonDeployment.Policy policy = profileOf(TsonDeployment.read("""
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
                !deployment { name: "minimal" }""")).policy();

        assertEquals(TsonDeployment.Policy.of(ProcessorPolicy.defaults()), policy);
        assertEquals(ScriptPolicy.Level.HIGHLY_RESTRICTIVE, policy.identifierPolicy().level());
        assertEquals(ScriptPolicy.Level.UNRESTRICTED, policy.tokenPolicy().level());
        assertEquals(64, policy.limits().maxDepth());
    }

    /**
     * <b>The profile is {@code policy.tn}'s {@code policy}</b>, the shape {@code tson policy} and the CLI's
     * report state one in, so a client reads one format. Written and read back against the published schema,
     * which imports the bundled one: a field this project names differently would not validate.
     */
    @Test
    void theProfileIsWrittenInPolicyTnsShape() {
        String written = TsonDeployment.tson().objectWriter()
                .describing(TsonDeployment.ID, "acceptance_profile").toTson(profileOf(TsonDeployment.read(FULL)));

        assertEquals(List.of(), TsonDeployment.tson().validate(written), written);
        assertTrue(written.contains("identifier_policy") && written.contains("token_policy"), written);
    }

    /**
     * <b>The profile states the data version, read from the library rather than copied.</b> §8.3 marks all
     * three of §8.2's rules unstable across Unicode releases, so two conforming processors may legitimately
     * disagree about one name and the version is what explains it. A refusal does not name it; the profile
     * states it once, for a client that would rather know before it sends than after it is refused.
     */
    @Test
    void theProfileStatesTheUnicodeDataVersion() {
        assertEquals(Optional.of(ProcessorPolicy.dataVersion()),
                profileOf(TsonDeployment.read(FULL)).policy().unicodeDataVersion());
        // Read, not copied: a constant here would go stale silently on a library upgrade.
        assertFalse(ProcessorPolicy.dataVersion().isBlank());
    }

    /**
     * <b>Script names are canonicalised against the authoritative table, not by the schema.</b> [UAX #24]
     * gives every script a long alias and an ISO 15924 short alias, matched case-insensitively, so {@code
     * Latn} and {@code cyrillic} name the same two scripts as {@code Latin} and {@code Cyrillic}. The schema
     * cannot enforce membership — 171 values that grow with the UCD have no place in a published immutable
     * document — so it constrains shape and this is where a name becomes a script.
     */
    @Test
    void scriptNamesAreCanonicalisedWhicheverAliasIsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals(List.of("LATIN", "CYRILLIC"), deployment.tokens().orElseThrow().permitting());
        // The profile writes each combination as policy.tn names scripts, by UAX #24 alias, sorted -- so two
        // deployments naming one set look alike, and look like the CLI's report of it.
        assertEquals(List.of(List.of("Cyrillic", "Latin")),
                profileOf(deployment).policy().tokenPolicy().permitting());
    }

    /**
     * A typo in a security setting stops the read, rather than being carried as a policy quietly missing a
     * script. {@code Cyrrilic} is well-shaped, so {@code script_name}'s pattern admits it and only the
     * authoritative table can refuse it.
     */
    @Test
    void anUnknownScriptNameStopsTheRead() {
        String message = assertThrows(RuntimeException.class, () -> TsonDeployment.read("""
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
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
        List<io.ltr8.tson.base.Diagnostic> problems = TsonDeployment.tson().validate("""
                !!schema:"https://tson.io/2026/37/io/ltr8/http/deployment.tn"
                !deployment { name: "n"  tokens: { level: SINGLE_SCRIPT  permitting: [42] } }""");

        assertEquals(List.of(io.ltr8.tson.base.Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                problems.stream().map(io.ltr8.tson.base.Diagnostic::code).toList(),
                () -> "expected the pattern to refuse it, got " + problems);
    }

}
