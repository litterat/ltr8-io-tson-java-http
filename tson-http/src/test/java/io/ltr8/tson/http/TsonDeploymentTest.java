package io.ltr8.tson.http;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.ScriptPolicy;
import io.ltr8.unicode.RestrictionLevel;
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
            !!schema:"https://tson.io/2026/38/io/ltr8/http/deployment.tn"
            !deployment {
              name:     "production"
              listener: { host: "127.0.0.1"  port: 8080 }
              policy: {
                identifier_policy: { level: HIGHLY_RESTRICTIVE  per_segment: true  skeleton_distinctness: false
                                     permitting: [] }
                token_policy:      { level: MODERATELY_RESTRICTIVE  permitting: [["Latn" "cyrillic"]] }
                limits:            { max_depth: 32 }
              }
              schema_hosts: ["schemas.example.com"]
            }""";

    /** A descriptor whose policy is {@code policy}. */
    private static String withPolicy(String policy) {
        return """
                !!schema:"https://tson.io/2026/38/io/ltr8/http/deployment.tn"
                !deployment { name: "n"  policy: %s }""".formatted(policy);
    }

    private static final String DEFAULT_IDENTIFIERS =
            "{ level: HIGHLY_RESTRICTIVE  per_segment: false  skeleton_distinctness: true  permitting: [] }";

    @Test
    void aDescriptorReadsAsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals("production", deployment.name());
        assertEquals(8080, deployment.listener().orElseThrow().port().orElseThrow());
        assertEquals(List.of("schemas.example.com"), deployment.schemaHosts());
        TsonDeployment.Policy policy = deployment.policy().orElseThrow();
        assertEquals(RestrictionLevel.HIGHLY_RESTRICTIVE, policy.identifierPolicy().level());
        assertTrue(policy.identifierPolicy().perSegment());
        assertEquals(32, policy.limits().maxDepth());
    }

    /**
     * The unit, the look-alike switch, the script combinations and the limit all survive into the library's own
     * types, which is the point of carrying them.
     */
    @Test
    void everyPartReachesTheLibraryPolicy() {
        ProcessorPolicy policy = TsonDeployment.read(FULL).processorPolicy().orElseThrow();

        IdentifierPolicy identifiers = policy.identifierPolicy();
        assertTrue(identifiers.isPerSegment(), "per_segment should reach perSegment()");
        assertFalse(identifiers.appliesSkeletonDistinctness(), "an explicit false should switch the rule off");
        assertEquals(32, policy.limits().maxDepth());

        // `permitting` is the narrowest relaxation §8.2 offers: Cyrillic beside Latin, without dropping a
        // level and losing the rule everywhere else. A mixed Latin/Cyrillic name is refused at Moderately
        // Restrictive and admitted once that combination is named.
        ScriptPolicy tokens = policy.tokenPolicy();
        assertTrue(tokens.violation("аdmin").isEmpty(),
                () -> "LATIN+CYRILLIC was permitted: " + tokens.violation("аdmin").orElse(""));
        assertTrue(ScriptPolicy.moderatelyRestrictive().violation("аdmin").isPresent(),
                "and is refused without it, or this asserts nothing");
    }

    /**
     * <b>A stated policy is what runs, and what is published.</b> A descriptor's policy applied to a config and
     * read back from the processor is the same policy, stamped with the processor's data version -- nothing the
     * library defaults to was filled in or moved.
     */
    @Test
    void aStatedPolicyIsAppliedExactlyAsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);
        TsonDeployment.Policy stated = deployment.policy().orElseThrow();
        ProcessorPolicy inForce = deployment.applyTo(ProcessorConfig.defaults()).processorPolicy();

        assertEquals(new TsonDeployment.Policy(stated.identifierPolicy(), stated.tokenPolicy(),
                Optional.of(ProcessorPolicy.dataVersion()), stated.limits()), TsonDeployment.Policy.of(inForce));
    }

    /** A descriptor with this {@code schema_hosts} and, where given, this {@code listener.host}. */
    private static String withHosts(String hosts, String listenerHost) {
        return """
                !!schema:"https://tson.io/2026/38/io/ltr8/http/deployment.tn"
                !deployment { name: "n"  %s schema_hosts: [%s] }""".formatted(
                listenerHost == null ? "" : "listener: { host: " + listenerHost + " }", hosts);
    }

    /**
     * <b>The fetch allow-list holds hosts, compared by net.tn's {@code host} equality.</b> A host name in either
     * label form, any ASCII case, or an address in any spelling, reads to one canonical text -- the U-label name,
     * the dotted-quad, RFC 5952 for IPv6 -- so the spelling an operator writes never decides what matches. A
     * {@code String} component binds that text.
     */
    @Test
    void theFetchAllowListReadsEachHostToItsCanonicalText() {
        assertEquals(List.of("example.com", "bücher.example", "bücher.example", "10.0.0.1", "::1", "::1"),
                List.of("\"Example.COM\"", "\"bücher.example\"", "\"xn--bcher-kva.example\"", "\"10.0.0.1\"",
                        "\"[::1]\"", "\"0:0:0:0:0:0:0:1\"").stream()
                        .map(host -> TsonDeployment.read(withHosts(host, null)).schemaHosts().getFirst()).toList());
    }

    /**
     * <b>It is a set, so one host twice is refused under any two spellings</b> -- which is the type's equality
     * at work, not the text's. An IPv4 address and its IPv4-mapped IPv6 form are two hosts, each family keeping
     * its own equality.
     */
    @Test
    void oneHostTwiceIsRefusedWhicheverSpellingsAreUsed() {
        for (String twice : List.of("\"bücher.example\" \"xn--bcher-kva.example\"", "\"Example.COM\" \"example.com\"",
                "\"::1\" \"[::1]\"", "\"::1\" \"0:0:0:0:0:0:0:1\"")) {
            List<Diagnostic> problems = TsonDeployment.tson().validate(withHosts(twice, null));
            assertEquals(1, problems.size(), () -> twice + ": " + problems);
            assertTrue(problems.getFirst().message().contains("unique"), problems::toString);
        }
        assertEquals(List.of("127.0.0.1", "::ffff:127.0.0.1"),
                TsonDeployment.read(withHosts("\"127.0.0.1\" \"::ffff:127.0.0.1\"", null)).schemaHosts());
    }

    /**
     * <b>What is not a host is refused when the descriptor is read, and the refusal names the fix</b> -- a port,
     * a URL, percent-encoding, a trailing dot, an uppercase letter beyond ASCII, a zone identifier. Each would
     * otherwise be an entry that matches nothing.
     */
    @Test
    void whatIsNotAHostIsRefusedWithItsFix() {
        for (String notAHost : List.of("example.com:443", "https://example.com", "b%C3%BCcher.example",
                "example.com.", "BÜCHER.example", "fe80::1%eth0")) {
            List<Diagnostic> problems = TsonDeployment.tson().validate(withHosts("\"" + notAHost + "\"", null));
            assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), problems.stream().map(Diagnostic::code).toList(),
                    () -> notAHost + ": " + problems);
        }
        assertTrue(TsonDeployment.tson().validate(withHosts("\"BÜCHER.example\"", null)).getFirst().message()
                .contains("write 'bücher.example'"));
    }

    /**
     * <b>A listener's host is a name or an address, written untagged.</b> net.tn's {@code host} is one
     * string-class type, so the choice {@code ( hostname | ipv4 | ipv6 )} -- not disjoint under §5.4, and so
     * needing a tag on every value -- is not needed.
     */
    @Test
    void aListenerHostIsANameOrAnAddressUntagged() {
        assertEquals(List.of("localhost", "127.0.0.1", "::1"),
                List.of("localhost", "\"127.0.0.1\"", "\"[::1]\"").stream()
                        .map(host -> TsonDeployment.read(withHosts("", host)).listener().orElseThrow().host()
                                .orElseThrow()).toList());
    }

    /**
     * <b>An absent policy leaves the library's defaults alone rather than meaning "no policy".</b> The two
     * defaults point opposite ways for reasons §8.2 gives, so overwriting them with something permissive would
     * be a decision nobody made.
     */
    @Test
    void anAbsentPolicyIsNotAPermissiveOne() {
        TsonDeployment deployment = TsonDeployment.read("""
                !!schema:"https://tson.io/2026/38/io/ltr8/http/deployment.tn"
                !deployment { name: "minimal" }""");

        assertTrue(deployment.processorPolicy().isEmpty());
        assertEquals(List.of(), deployment.schemaHosts(), "an omitted list is empty, not null");
        ProcessorConfig config = ProcessorConfig.defaults();
        assertEquals(config, deployment.applyTo(config));
    }

    /**
     * <b>A policy is stated whole or not at all.</b> A descriptor setting only the token policy is refused by
     * the schema: there is no partial override for the library's defaults to fill in, since a default is the
     * library's to move and the descriptor is what is meant to say what runs.
     */
    @Test
    void aPolicyStatesEveryPart() {
        List<Diagnostic> problems = TsonDeployment.tson().validate(
                withPolicy("{ token_policy: { level: SINGLE_SCRIPT  permitting: [] } }"));

        assertEquals(List.of(Diagnostic.Code.FIELD_REQUIRED, Diagnostic.Code.FIELD_REQUIRED),
                problems.stream().map(Diagnostic::code).toList(),
                () -> "expected identifier_policy and limits to be required, got " + problems);
    }

    /**
     * <b>A token policy cannot be given a unit.</b> A value has no segments — {@code _} and {@code -} separate
     * a name's words and are ordinary characters in a value, so segmenting one would admit UTS #39's own
     * {@code Toys-Я-Us} — and {@code policy.tn}'s {@code script_policy} has no {@code per_segment} to write.
     */
    @Test
    void aTokenPolicyCannotBeGivenAUnit() {
        List<Diagnostic> problems = TsonDeployment.tson().validate(withPolicy("""
                { identifier_policy: %s
                  token_policy: { level: HIGHLY_RESTRICTIVE  per_segment: true  permitting: [] }
                  limits: { max_depth: 64 } }""".formatted(DEFAULT_IDENTIFIERS)));

        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD),
                problems.stream().map(Diagnostic::code).toList(),
                () -> "expected the unit to be refused, got " + problems);
    }

    /**
     * <b>The data version is the processor's, not the deployment's.</b> A descriptor naming one this processor
     * does not carry would describe a processor that does not exist, so it stops the read; naming the one it
     * does carry is harmless, as a copied report would.
     */
    @Test
    void aDescriptorNamingAnotherDataVersionStopsTheRead() {
        String policy = """
                { identifier_policy: %s
                  token_policy: { level: UNRESTRICTED  permitting: [] }
                  unicode_data_version: "%s"
                  limits: { max_depth: 64 } }""";

        String message = assertThrows(RuntimeException.class, () -> TsonDeployment.read(
                withPolicy(policy.formatted(DEFAULT_IDENTIFIERS, "1.1")))).getMessage();
        assertTrue(message.contains("1.1"), message);

        assertTrue(TsonDeployment.read(withPolicy(policy.formatted(DEFAULT_IDENTIFIERS,
                ProcessorPolicy.dataVersion()))).policy().isPresent());
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
        assertEquals(RestrictionLevel.MODERATELY_RESTRICTIVE, profile.policy().tokenPolicy().level());

        // Nothing about what this deployment trusts, and nothing about where it listens.
        String written = TsonDeployment.tson().objectWriter()
                .describing(TsonDeployment.ID, "acceptance_profile").toTson(profile);
        assertFalse(written.contains("schemas.example.com"), written);
        assertFalse(written.contains("127.0.0.1"), written);
        assertFalse(written.contains("8080"), written);
    }

    /** The profile of a processor configured from the library's defaults with {@code deployment} applied. */
    private static TsonDeployment.AcceptanceProfile profileOf(TsonDeployment deployment) {
        return deployment.profile(deployment.applyTo(ProcessorConfig.defaults()).processorPolicy());
    }

    /**
     * <b>A descriptor stating no policy still publishes one</b>: the library's defaults, since the profile is
     * what is enforced rather than what the descriptor said, and a client needs no library's defaults to know
     * what applies.
     */
    @Test
    void aDescriptorWithNoPolicyPublishesTheDefaults() {
        TsonDeployment.Policy policy = profileOf(TsonDeployment.read("""
                !!schema:"https://tson.io/2026/38/io/ltr8/http/deployment.tn"
                !deployment { name: "minimal" }""")).policy();

        assertEquals(TsonDeployment.Policy.of(ProcessorPolicy.defaults()), policy);
        assertEquals(RestrictionLevel.HIGHLY_RESTRICTIVE, policy.identifierPolicy().level());
        assertEquals(RestrictionLevel.UNRESTRICTED, policy.tokenPolicy().level());
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
        assertFalse(ProcessorPolicy.dataVersion().isBlank());
    }

    /**
     * <b>Script names are canonicalised against the authoritative table, not by the schema.</b> [UAX #24]
     * gives every script a long alias and an ISO 15924 short alias, matched case-insensitively, so {@code
     * Latn} and {@code cyrillic} name the same two scripts as {@code Latin} and {@code Cyrillic}, and each
     * combination is held sorted -- so two descriptors naming one set look alike, and look like the CLI's
     * report of it.
     */
    @Test
    void scriptNamesAreCanonicalisedWhicheverAliasIsWritten() {
        TsonDeployment deployment = TsonDeployment.read(FULL);

        assertEquals(List.of(List.of("Cyrillic", "Latin")),
                deployment.policy().orElseThrow().tokenPolicy().permitting());
        assertEquals(List.of(List.of("Cyrillic", "Latin")),
                profileOf(deployment).policy().tokenPolicy().permitting());
    }

    /**
     * A typo in a security setting stops the read, rather than being carried as a policy quietly missing a
     * script -- {@code policy.tn} types a script name as {@code text}, so only the authoritative table can
     * refuse {@code Cyrrilic}, or a {@code 42}, which a {@code text} position admits.
     */
    @Test
    void anUnknownScriptNameStopsTheRead() {
        for (String name : List.of("\"Cyrrilic\"", "42")) {
            String message = assertThrows(RuntimeException.class, () -> TsonDeployment.read(withPolicy("""
                    { identifier_policy: %s
                      token_policy: { level: SINGLE_SCRIPT  permitting: [[%s]] }
                      limits: { max_depth: 64 } }""".formatted(DEFAULT_IDENTIFIERS, name)))).getMessage();

            assertTrue(message.contains(name.replace("\"", "")), message);
        }
    }
}
