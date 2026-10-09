package io.ltr8.tson.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.ScriptPolicy;
import io.ltr8.tson.base.source.SchemaAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How one instance is configured, read from a {@code deployment.tn} document.
 *
 * <p>The third artifact kind, beside a schema (what a document must be) and an API description (what an
 * endpoint offers). {@code deployment.tn} carries the argument for why the [TSON-DATA] §8.2 policies can
 * live in neither of the other two, which Revision 37 adopted with the bundled {@code policy.tn} as the
 * policy's vocabulary. The descriptor states only what it changes; {@link #profile} states the whole
 * policy in force, in {@code policy.tn}'s shape.
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
 *       server must not publish one. {@link #profile} is what a counterparty gets, and it is derived.</li>
 * </ul>
 *
 * <p><b>An absent policy is not "no policy".</b> A descriptor stating neither leaves both at the library's
 * defaults, which point opposite ways on purpose — Highly Restrictive over declared names, unrestricted over
 * values — so {@link #identifierPolicy()} and {@link #tokenPolicy()} return empty rather than something
 * permissive, and {@link #applyTo} leaves a config alone rather than overwriting it with a guess.
 */
@Typename(name = "deployment")
public record TsonDeployment(String name, Optional<Listener> listener,
                             Optional<IdentifierOverride> identifiers, Optional<TokenOverride> tokens,
                             Optional<LimitsOverride> limits, @Field("schema_hosts") List<String> schemaHosts) {

    /** The schema a descriptor names. Published like any other, unlike the descriptors it governs. */
    public static final String ID = "https://tson.io/2026/37/io/ltr8/http/deployment.tn";

    private static final String SOURCE = readResource("/deployment.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.ofEntries(
            Map.entry("deployment", TsonDeployment.class),
            Map.entry("acceptance_profile", AcceptanceProfile.class),
            Map.entry("identifier_override", IdentifierOverride.class),
            Map.entry("token_override", TokenOverride.class),
            Map.entry("limits_override", LimitsOverride.class),
            Map.entry("listener", Listener.class),
            Map.entry("policy_unit", Unit.class),
            Map.entry("restriction_level", ScriptPolicy.Level.class),
            Map.entry("policy", Policy.class),
            Map.entry("identifier_policy", Policy.Identifiers.class),
            Map.entry("script_policy", Policy.Scripts.class),
            Map.entry("limits", Policy.Limits.class));

    /**
     * An optional list a document omits arrives as {@code null}, and the binder does not normalise it — the
     * convention upstream follows is that the record does.
     */
    public TsonDeployment {
        schemaHosts = schemaHosts == null ? List.of() : List.copyOf(schemaHosts);
    }

    /**
     * [TSON-DATA] §9.1's resource limits — what this processor will <em>spend</em> reading a document, where
     * the two §8.2 policies are what it will <em>admit</em>. Two settings because they answer two questions,
     * and a deployment changing one has said nothing about the other.
     *
     * <p>{@code max_depth} is the only member because it is the only limit the library enforces. §9.1 states
     * twelve; the rest are added as the library enforces them, rather than as a member nothing reads.
     */
    @Typename(name = "limits_override")
    public record LimitsOverride(@Field("max_depth") Optional<Integer> maxDepth) {

        /** This descriptor's limits policy, or empty where it states no member and the library's stands. */
        public Optional<LimitsPolicy> toPolicy() {
            return maxDepth.map(depth -> LimitsPolicy.defaults().withMaxDepth(depth));
        }
    }

    /** Where this instance listens. Absent members leave the caller's own defaults alone. */
    @Typename(name = "listener")
    public record Listener(Optional<String> host, Optional<Integer> port) {
    }

    /** What an identifier policy's level is applied to. Absent means {@link #WHOLE}. */
    @Typename(name = "policy_unit")
    public enum Unit { WHOLE, SEGMENT }

    /**
     * §8.2's token policy: a UTS #39 level over each whole token, and any extra admitted script combination.
     *
     * <p>It has no unit because a value has no segments — {@code _} and {@code -} separate a name's words and
     * are ordinary characters in a value — which the library makes unwritable rather than refused.
     */
    @Typename(name = "token_override")
    public record TokenOverride(ScriptPolicy.Level level, List<String> permitting) {

        /** @throws IllegalArgumentException if {@code permitting} names something that is not a script */
        public TokenOverride {
            permitting = canonicalScripts(permitting);
        }

        /** This policy as the library's own type. */
        public ScriptPolicy toPolicy() {
            return scriptPolicy(level, permitting);
        }
    }

    /**
     * §8.2's identifier policy: a level and a script combination as {@link TokenOverride} states them, the
     * unit the level applies to, and skeleton distinctness — the look-alike rule over a scope, which no level
     * reaches and so is a switch of its own. An absent switch leaves the library's default, which is on.
     */
    @Typename(name = "identifier_override")
    public record IdentifierOverride(ScriptPolicy.Level level, Optional<Unit> unit,
                                     @Field("skeleton_distinctness") Optional<Boolean> skeletonDistinctness,
                                     List<String> permitting) {

        /** @throws IllegalArgumentException if {@code permitting} names something that is not a script */
        public IdentifierOverride {
            permitting = canonicalScripts(permitting);
        }

        /** This policy as the library's own type. */
        public IdentifierPolicy toPolicy() {
            IdentifierPolicy policy = IdentifierPolicy.of(scriptPolicy(level, permitting));
            if (unit.orElse(Unit.WHOLE) == Unit.SEGMENT) {
                policy = policy.perSegment();
            }
            return skeletonDistinctness.map(policy::withSkeletonDistinctness).orElse(policy);
        }
    }

    /**
     * A processor's whole policy, in the shape the bundled {@code policy.tn} declares — the shape {@code tson
     * policy} and the CLI's report state one in, so a client reads one format whichever tool it asked.
     *
     * <p><b>Complete, unlike the descriptor.</b> Every part is stated, a default as much as a setting, because
     * a counterparty cannot be expected to know what any library defaults to.
     */
    @Typename(name = "policy")
    public record Policy(@Field("identifier_policy") Identifiers identifierPolicy,
                         @Field("token_policy") Scripts tokenPolicy,
                         @Field("unicode_data_version") Optional<String> unicodeDataVersion,
                         Limits limits) {

        /** {@code policy} as this processor enforces it, data version included. */
        public static Policy of(ProcessorPolicy policy) {
            IdentifierPolicy identifiers = policy.identifierPolicy();
            return new Policy(
                    new Identifiers(identifiers.scripts().level(), identifiers.isPerSegment(),
                            identifiers.appliesSkeletonDistinctness(), aliases(identifiers.scripts())),
                    new Scripts(policy.tokenPolicy().level(), aliases(policy.tokenPolicy())),
                    Optional.of(policy.unicodeDataVersion()), new Limits(policy.limits().maxDepth()));
        }

        /** {@code policy.tn}'s {@code identifier_policy}. */
        @Typename(name = "identifier_policy")
        public record Identifiers(ScriptPolicy.Level level, @Field("per_segment") boolean perSegment,
                                  @Field("skeleton_distinctness") boolean skeletonDistinctness,
                                  List<List<String>> permitting) {
        }

        /** {@code policy.tn}'s {@code script_policy}: a level and the script combinations admitted over it. */
        @Typename(name = "script_policy")
        public record Scripts(ScriptPolicy.Level level, List<List<String>> permitting) {
        }

        /** {@code policy.tn}'s {@code limits}. */
        @Typename(name = "limits")
        public record Limits(@Field("max_depth") int maxDepth) {
        }

        /** Each admitted combination as UAX #24 aliases, sorted so two deployments' profiles compare as text. */
        private static List<List<String>> aliases(ScriptPolicy policy) {
            return policy.permittedScripts().stream()
                    .map(scripts -> scripts.stream().map(Policy::alias).sorted().toList()).toList();
        }

        /**
         * A script's UAX #24 property value alias, which is how {@code policy.tn} names one: {@code Latin},
         * {@code Old_Italic}. The JDK's constant is the alias upper-cased, so each segment is title-cased back;
         * {@code SignWriting} is the one alias with a capital inside a segment.
         */
        static String alias(UnicodeScript script) {
            if (script == UnicodeScript.SIGNWRITING) {
                return "SignWriting";
            }
            StringBuilder alias = new StringBuilder();
            for (String segment : script.name().split("_")) {
                if (!alias.isEmpty()) {
                    alias.append('_');
                }
                alias.append(segment.charAt(0)).append(segment.substring(1).toLowerCase(Locale.ROOT));
            }
            return alias.toString();
        }
    }

    /**
     * What a counterparty may see — the policy in force, and nothing about what this deployment trusts.
     *
     * <p><b>A hint, not the authority.</b> It can be cached and a policy can change under it; only the
     * refusal a request actually receives says what applied to that request, which is where §8.2 puts it.
     */
    @Typename(name = "acceptance_profile")
    public record AcceptanceProfile(String name, Policy policy) {
    }

    /** This schema's own source text, for a server that publishes it. */
    public static String source() {
        return SOURCE;
    }

    /**
     * The bindings for every type {@code deployment.tn} declares, for a server whose own {@link Tson} reads
     * or writes a descriptor or a profile and should not restate this schema's vocabulary.
     */
    public static Map<String, Class<?>> bindings() {
        return BINDINGS;
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
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(uri -> SOURCE))
                .withDataBindContext(TsonBindings.of(BINDINGS)));
        tson.resolve(SOURCE);
        return tson;
    }

    /** The identifier policy this descriptor states, or empty to leave the library's default alone. */
    public Optional<IdentifierPolicy> identifierPolicy() {
        return identifiers.map(IdentifierOverride::toPolicy);
    }

    /** The token policy this descriptor states, or empty to leave the library's default alone. */
    public Optional<ScriptPolicy> tokenPolicy() {
        return tokens.map(TokenOverride::toPolicy);
    }

    /**
     * The [TSON-DATA] §9.1 limits policy this descriptor states, or empty to leave the library's default
     * alone — 64 levels of nesting, §9.1's own default.
     */
    public Optional<LimitsPolicy> limitsPolicy() {
        return limits.flatMap(LimitsOverride::toPolicy);
    }

    /**
     * {@code config} with whatever this descriptor states applied.
     *
     * <p>Only what it states: a descriptor with no {@code tokens} leaves the token policy at the library's
     * default rather than setting it to something permissive, because those are different postures and only
     * one of them was asked for. A {@link ProcessorConfig} is a value, so an absent setting is the config
     * handed back unchanged.
     */
    public ProcessorConfig applyTo(ProcessorConfig config) {
        ProcessorConfig withIdentifiers = identifierPolicy().map(config::withIdentifierPolicy).orElse(config);
        ProcessorConfig withTokens = tokenPolicy().map(withIdentifiers::withTokenPolicy).orElse(withIdentifiers);
        return limitsPolicy().map(withTokens::withLimits).orElse(withTokens);
    }

    /**
     * What to publish: this deployment's name and {@code inForce}, the policy the processor it configures
     * actually enforces — {@code tson.processorPolicy()}, or {@code applyTo(config).processorPolicy()} before
     * one is built.
     *
     * <p><b>Derived from what is enforced, not from what the descriptor says</b>, and the difference is the
     * point. A descriptor states only what it changes, so a profile built from it would leave out every part
     * at the library's default, and a client would have to know those defaults to know what applies. Taking
     * the policy in force states every part, and cannot disagree with what refuses a request.
     *
     * <p>{@code schema_hosts} and {@code listener} are dropped: which origins this deployment trusts is
     * nobody else's business, and where it listens is something a counterparty already knows. The limits are
     * kept, inside {@code policy}: a 413 says a document went past a bound, and a sender that read the bound
     * first never writes past it.
     */
    public AcceptanceProfile profile(ProcessorPolicy inForce) {
        return new AcceptanceProfile(name, Policy.of(inForce));
    }

    private static ScriptPolicy scriptPolicy(ScriptPolicy.Level level, List<String> permitting) {
        ScriptPolicy policy = ScriptPolicy.of(level);
        return permitting.isEmpty() ? policy
                : policy.permitting(permitting.stream().map(UnicodeScript::forName).toArray(UnicodeScript[]::new));
    }

    /**
     * <b>Script names are canonicalised here, against the authoritative table.</b> [UAX #24] gives every
     * script a long alias and an ISO 15924 short alias and matching is case-insensitive, so {@code Latin},
     * {@code LATIN}, {@code latin} and {@code Latn} are four spellings of one script. The schema's {@code
     * script_name} constrains shape and cannot constrain membership -- a set of 171 values that grows with the
     * UCD has no place in a published, immutable document -- so this is where a name becomes a script, and
     * where two descriptors naming one set stop differing.
     *
     * <p>A name that is not a script <b>stops the read</b>, which is deliberate: a descriptor is loaded at
     * startup by the operator who wrote it, and a typo in a security setting should halt the process rather
     * than be carried as a policy quietly missing a script. An omitted list arrives as {@code null}, and the
     * record normalises it, as is the convention.
     */
    private static List<String> canonicalScripts(List<String> permitting) {
        return permitting == null ? List.of() : permitting.stream().map(TsonDeployment::canonical).toList();
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
