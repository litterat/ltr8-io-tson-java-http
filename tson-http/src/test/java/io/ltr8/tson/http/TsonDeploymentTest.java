package io.ltr8.tson.http;

import io.ltr8.tson.TsonConfig;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deployment descriptor: what it reads, what it applies, and what it will not hand out. */
class TsonDeploymentTest {

    private static final String FULL = """
            !!schema:"https://tson.io/2026/34/ltr8/http/deployment-1.tn"
            !deployment {
              name:         "production"
              listener:     { host: "127.0.0.1"  port: 8080 }
              identifiers:  { level: HIGHLY_RESTRICTIVE  unit: SEGMENT }
              tokens:       { level: MODERATELY_RESTRICTIVE  permitting: ["LATIN" "CYRILLIC"] }
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
                !!schema:"https://tson.io/2026/34/ltr8/http/deployment-1.tn"
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
     * §8.2 requires a refusal to state the data version, and nothing exposes one — {@code Xid.UNICODE_VERSION}
     * lives in a package the compiler module does not export. Asserted as absent rather than left untested, so
     * that the day it can be filled, this test says so by failing. {@code UPSTREAM.md} #3.
     */
    @Test
    void theUnicodeDataVersionIsNotAvailableToPublish() {
        assertTrue(TsonDeployment.read(FULL).profile().unicodeDataVersion().isEmpty(),
                "a version is now obtainable -- fill it in and delete this test's premise");
    }

    /** A typo in a security setting stops the process rather than being collected for nobody to read. */
    @Test
    void anUnknownScriptNameIsRefusedAtRead() {
        TsonDeployment deployment = TsonDeployment.read("""
                !!schema:"https://tson.io/2026/34/ltr8/http/deployment-1.tn"
                !deployment { name: "typo"  tokens: { level: SINGLE_SCRIPT  permitting: ["CYRRILIC"] } }""");

        String message = assertThrows(IllegalArgumentException.class, deployment::tokenPolicy).getMessage();
        assertTrue(message.contains("CYRRILIC"), message);
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
