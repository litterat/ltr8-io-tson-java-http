package io.ltr8.tson.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.TsonConfig;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonUnicodePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How one instance is configured, read from a {@code deployment-1.tn} document.
 *
 * <p>The third artifact kind, beside a schema (what a document must be) and an API description (what an
 * endpoint offers). {@code deployment-1.tn} carries the argument for why the [TSON-DATA] §8.2 policies can
 * live in neither of the other two; {@code UPSTREAM.md} carries the version staged for the spec author.
 *
 * <p><b>Two rules this class exists to enforce by shape rather than by documentation.</b>
 *
 * <ul>
 *   <li><b>A processor is handed a descriptor.</b> There is no {@code load()} that searches a path, a
 *       classpath or an environment variable, and there will not be one: a descriptor is diffable where an
 *       environment variable is not, but a runtime that loads whatever it finds still lets a container image
 *       change a security policy with no code diff. {@link #read} takes the source text and the caller says
 *       where it came from.</li>
 *   <li><b>No document may name one.</b> Nothing here registers a descriptor with a schema source, and a
 *       server must not publish one. {@link #profile()} is what a counterparty gets, and it is derived.</li>
 * </ul>
 *
 * <p><b>An absent policy is not "no policy".</b> A descriptor stating neither leaves both at the library's
 * defaults, which point opposite ways on purpose — Highly Restrictive over declared names, unrestricted over
 * values — so {@link #identifierPolicy()} and {@link #tokenPolicy()} return empty rather than something
 * permissive, and {@link #applyTo} leaves a config alone rather than overwriting it with a guess.
 */
@Typename(name = "deployment")
public record TsonDeployment(String name, Optional<Listener> listener,
                             Optional<Policy> identifiers, Optional<Policy> tokens,
                             @Field("schema_hosts") List<String> schemaHosts) {

    /** The schema a descriptor names. Published like any other, unlike the descriptors it governs. */
    public static final String ID = "https://tson.io/2026/34/ltr8/http/deployment-1.tn";

    private static final String SOURCE = readResource("/deployment-1.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "deployment", TsonDeployment.class,
            "acceptance_profile", AcceptanceProfile.class,
            "unicode_policy", Policy.class,
            "listener", Listener.class,
            "restriction_level", TsonUnicodePolicy.Level.class,
            "policy_unit", Unit.class);

    /**
     * An optional list a document omits arrives as {@code null}, and the binder does not normalise it — the
     * convention upstream follows is that the record does.
     */
    public TsonDeployment {
        schemaHosts = schemaHosts == null ? List.of() : List.copyOf(schemaHosts);
    }

    /** Where this instance listens. Absent members leave the caller's own defaults alone. */
    @Typename(name = "listener")
    public record Listener(Optional<String> host, Optional<Integer> port) {
    }

    /** What a level is applied to. Absent means {@link #WHOLE}. */
    @Typename(name = "policy_unit")
    public enum Unit { WHOLE, SEGMENT }

    /** One §8.2 policy: a UTS #39 level, the unit it applies to, and any extra admitted script set. */
    @Typename(name = "unicode_policy")
    public record Policy(TsonUnicodePolicy.Level level, Optional<Unit> unit, List<String> permitting) {

        /**
         * <b>Script names are canonicalised here, against the authoritative table.</b> [UAX #24] gives every
         * script a long alias and an ISO 15924 short alias and matching is case-insensitive, so {@code
         * Latin}, {@code LATIN}, {@code latin} and {@code Latn} are four spellings of one script. The
         * schema's {@code script_name} constrains shape and cannot constrain membership -- a set of 171
         * values that grows with the UCD has no place in a published, immutable document -- so this is where
         * a name becomes a script, and where two descriptors naming one set stop differing.
         *
         * <p>A name that is not a script <b>stops the read</b>, which is deliberate: a descriptor is loaded
         * at startup by the operator who wrote it, and a typo in a security setting should halt the process
         * rather than be carried as a policy quietly missing a script.
         *
         * @throws IllegalArgumentException if {@code permitting} names something that is not a script
         */
        public Policy {
            permitting = permitting == null ? List.of()
                    : permitting.stream().map(Policy::canonical).toList();
        }

        /** This policy as the library's own type. */
        public TsonUnicodePolicy toPolicy() {
            TsonUnicodePolicy policy = TsonUnicodePolicy.of(level);
            if (unit.orElse(Unit.WHOLE) == Unit.SEGMENT) {
                policy = policy.perSegment();
            }
            if (!permitting.isEmpty()) {
                policy = policy.permitting(permitting.stream().map(UnicodeScript::forName)
                        .toArray(UnicodeScript[]::new));
            }
            return policy;
        }

        /** A script name as {@link UnicodeScript} spells it, whichever of its aliases was written. */
        private static String canonical(String name) {
            try {
                return UnicodeScript.forName(name).name();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + name + "' is not a Unicode script: a deployment "
                        + "descriptor's `permitting` names scripts by their UAX #24 Script property alias, "
                        + "long (Latin, Canadian_Aboriginal) or ISO 15924 (Latn, Cans)", e);
            }
        }
    }

    /**
     * What a counterparty may see — the policies, and nothing about what this deployment trusts.
     *
     * <p><b>A hint, not the authority.</b> It can be cached and a policy can change under it; only the
     * refusal a request actually receives says what applied to that request, which is where §8.2 puts it.
     */
    @Typename(name = "acceptance_profile")
    public record AcceptanceProfile(String name, Optional<Policy> identifiers, Optional<Policy> tokens,
                                    @Field("unicode_data_version") Optional<String> unicodeDataVersion) {
    }

    /** This schema's own source text, for a server that publishes it. */
    public static String source() {
        return SOURCE;
    }

    /**
     * A descriptor, read from {@code source}.
     *
     * <p>Takes text rather than a path, a name or a key, which is the whole of rule 1: the caller decides
     * where a descriptor comes from and that decision is visible at the call site.
     */
    public static TsonDeployment read(String source) {
        Tson tson = tson();
        return tson.objectReader().read(source, TsonDeployment.class);
    }

    /** A {@link Tson} with this schema resolved and bound — what {@link #read} reads through. */
    public static Tson tson() {
        Tson tson = Tson.builder().schemaSource(uri -> SOURCE).bindings(BINDINGS).build();
        tson.resolve(SOURCE);
        return tson;
    }

    /** This schema compiled in binding mode, for a test that reads the declared enum members back. */
    public static TsonCompiledSchema compiled() {
        return tson().bindRegistry().get(ID);
    }

    /** The declared-name policy this descriptor states, or empty to leave the library's default alone. */
    public Optional<TsonUnicodePolicy> identifierPolicy() {
        return identifiers.map(Policy::toPolicy);
    }

    /** The token policy this descriptor states, or empty to leave the library's default alone. */
    public Optional<TsonUnicodePolicy> tokenPolicy() {
        return tokens.map(Policy::toPolicy);
    }

    /**
     * Applies whatever this descriptor states to {@code config}, and returns it.
     *
     * <p>Only what it states: a descriptor with no {@code tokens} leaves the token policy at the library's
     * default rather than setting it to something permissive, because those are different postures and only
     * one of them was asked for.
     */
    public TsonConfig applyTo(TsonConfig config) {
        identifierPolicy().ifPresent(config::identifierPolicy);
        tokenPolicy().ifPresent(config::tokenPolicy);
        return config;
    }

    /**
     * What to publish, <b>derived from this descriptor</b> rather than written beside it — which is what
     * stops the two drifting, the same discipline a server's schema catalog follows.
     *
     * <p>{@code schema_hosts} and {@code listener} are dropped: which origins this deployment trusts is
     * nobody else's business, and where it listens is something a counterparty already knows.
     */
    public AcceptanceProfile profile() {
        return new AcceptanceProfile(name, identifiers, tokens, unicodeDataVersion());
    }

    /**
     * The Unicode data version this build computes §8.2's rules against, read from the library rather than
     * copied — a constant here would go stale silently on an upgrade, which is the failure the accessor
     * exists to prevent.
     *
     * <p>It is in the profile because §8.3 marks all three rules unstable across Unicode releases, so two
     * conforming processors may legitimately disagree about one name and the version is what explains the
     * disagreement. A refusal carries it too ({@code Diagnostic.unicodeDataVersion}); this states it once
     * for a client that wants to know before it sends rather than after it is refused.
     */
    private static Optional<String> unicodeDataVersion() {
        return Optional.of(TsonUnicodePolicy.dataVersion());
    }

    private static String readResource(String path) {
        try (InputStream in = TsonDeployment.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
