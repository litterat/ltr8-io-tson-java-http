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
 * policy's vocabulary. A descriptor states a {@code policy.tn} {@code policy}, complete, and {@link #profile}
 * publishes the policy in force in the same shape.
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
 * <p><b>A stated policy is applied exactly as written; an absent one is not a permissive one.</b> There are no
 * partial overrides falling back to the library's defaults: a default is the library's to move, and a security
 * setting whose effect shifts under an upgrade with no diff to the descriptor is the silent relaxation §8.2
 * exists to prevent. A descriptor stating no policy leaves the library's defaults alone — Highly Restrictive
 * over declared names, unrestricted over values — rather than overwriting them with a guess.
 */
@Typename(name = "deployment")
public record TsonDeployment(String name, Optional<Listener> listener, Optional<Policy> policy,
                             @Field("schema_hosts") List<String> schemaHosts) {

    /** The schema a descriptor names. Published like any other, unlike the descriptors it governs. */
    public static final String ID = "https://tson.io/2026/37/io/ltr8/http/deployment.tn";

    private static final String SOURCE = readResource("/deployment.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "deployment", TsonDeployment.class,
            "acceptance_profile", AcceptanceProfile.class,
            "listener", Listener.class,
            "restriction_level", ScriptPolicy.Level.class,
            "policy", Policy.class,
            "identifier_policy", Policy.Identifiers.class,
            "script_policy", Policy.Scripts.class,
            "limits", Policy.Limits.class);

    /**
     * An optional list a document omits arrives as {@code null}, and the binder does not normalise it — the
     * convention upstream follows is that the record does.
     *
     * @throws IllegalArgumentException if the policy names a Unicode data version this processor does not carry
     */
    public TsonDeployment {
        schemaHosts = schemaHosts == null ? List.of() : List.copyOf(schemaHosts);
        policy.flatMap(Policy::unicodeDataVersion).filter(version -> !version.equals(ProcessorPolicy.dataVersion()))
                .ifPresent(version -> {
                    throw new IllegalArgumentException("a deployment descriptor's policy names Unicode data "
                            + "version " + version + ", and this processor carries " + ProcessorPolicy.dataVersion()
                            + ": the tables are the processor's, so leave unicode_data_version out");
                });
    }

    /** Where this instance listens. Absent members leave the caller's own defaults alone. */
    @Typename(name = "listener")
    public record Listener(Optional<String> host, Optional<Integer> port) {
    }

    /**
     * A processor's whole policy, in the shape the bundled {@code policy.tn} declares — the shape {@code tson
     * policy} and the CLI's report state one in, so a descriptor, a profile and a report are one format.
     *
     * <p><b>Complete.</b> Every part is stated, a default as much as a setting: a descriptor because what is in
     * the file is what runs, and a profile because a counterparty cannot be expected to know what any library
     * defaults to.
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

        /** This policy as the library's own type, stamped with the data version this processor carries. */
        public ProcessorPolicy toProcessorPolicy() {
            IdentifierPolicy identifiers = IdentifierPolicy.of(scriptPolicy(identifierPolicy.level(),
                    identifierPolicy.permitting()));
            if (identifierPolicy.perSegment()) {
                identifiers = identifiers.perSegment();
            }
            return ProcessorPolicy.of(identifiers.withSkeletonDistinctness(identifierPolicy.skeletonDistinctness()),
                    scriptPolicy(tokenPolicy.level(), tokenPolicy.permitting()),
                    LimitsPolicy.defaults().withMaxDepth(limits.maxDepth()));
        }

        /**
         * {@code policy.tn}'s {@code identifier_policy}: a level, whether it applies to each segment of a name,
         * the look-alike rule's own switch, and the script combinations admitted over the level.
         */
        @Typename(name = "identifier_policy")
        public record Identifiers(ScriptPolicy.Level level, @Field("per_segment") boolean perSegment,
                                  @Field("skeleton_distinctness") boolean skeletonDistinctness,
                                  List<List<String>> permitting) {
            /** @throws IllegalArgumentException if {@code permitting} names something that is not a script */
            public Identifiers {
                permitting = canonical(permitting);
            }
        }

        /**
         * {@code policy.tn}'s {@code script_policy}: a level and the script combinations admitted over it. It
         * has no unit because a value has no segments — {@code _} and {@code -} separate a name's words and are
         * ordinary characters in a value — which the library makes unwritable rather than refused.
         */
        @Typename(name = "script_policy")
        public record Scripts(ScriptPolicy.Level level, List<List<String>> permitting) {
            /** @throws IllegalArgumentException if {@code permitting} names something that is not a script */
            public Scripts {
                permitting = canonical(permitting);
            }
        }

        /**
         * {@code policy.tn}'s {@code limits}: [TSON-DATA] §9.1's resource limits, what this processor will
         * <em>spend</em> reading a document where the two policies are what it will <em>admit</em>. {@code
         * max_depth} is the only member because it is the only limit the library enforces.
         */
        @Typename(name = "limits")
        public record Limits(@Field("max_depth") int maxDepth) {
        }

        /** Each admitted combination as UAX #24 aliases, sorted so two policies compare as text. */
        private static List<List<String>> aliases(ScriptPolicy policy) {
            return policy.permittedScripts().stream()
                    .map(scripts -> scripts.stream().map(Policy::alias).sorted().toList()).toList();
        }

        /**
         * <b>Script names are canonicalised here, against the authoritative table.</b> [UAX #24] gives every
         * script a long alias and an ISO 15924 short alias and matching is case-insensitive, so {@code Latin},
         * {@code LATIN}, {@code latin} and {@code Latn} are four spellings of one script; each is written back
         * as its long alias, a combination sorted, so two policies naming one set compare equal. {@code
         * policy.tn} types a name as {@code text}, since a set of 171 values that grows with the UCD has no
         * place in a published schema, so this is where a name becomes a script.
         *
         * <p>A name that is not a script <b>stops the read</b>, which is deliberate: a descriptor is loaded at
         * startup by the operator who wrote it, and a typo in a security setting should halt the process rather
         * than be carried as a policy quietly missing a script.
         */
        private static List<List<String>> canonical(List<List<String>> permitting) {
            return permitting == null ? List.of() : permitting.stream()
                    .map(scripts -> scripts.stream().map(name -> alias(script(name))).sorted().toList()).toList();
        }

        private static UnicodeScript script(String name) {
            try {
                return UnicodeScript.forName(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + name + "' is not a Unicode script: a policy's "
                        + "`permitting` names scripts by their UAX #24 Script property alias, "
                        + "long (Latin, Canadian_Aboriginal) or ISO 15924 (Latn, Cans)", e);
            }
        }

        private static ScriptPolicy scriptPolicy(ScriptPolicy.Level level, List<List<String>> permitting) {
            ScriptPolicy policy = ScriptPolicy.of(level);
            for (List<String> combination : permitting) {
                policy = policy.permitting(combination.stream().map(UnicodeScript::forName)
                        .toArray(UnicodeScript[]::new));
            }
            return policy;
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

    /** The processor policy this descriptor states, or empty to leave the library's defaults alone. */
    public Optional<ProcessorPolicy> processorPolicy() {
        return policy.map(Policy::toProcessorPolicy);
    }

    /**
     * {@code config} with this descriptor's policy applied, whole — or handed back unchanged where it states
     * none, since an absent policy and a permissive one are different postures and only one was asked for.
     */
    public ProcessorConfig applyTo(ProcessorConfig config) {
        return processorPolicy().map(config::withProcessorPolicy).orElse(config);
    }

    /**
     * What to publish: this deployment's name and {@code inForce}, the policy the processor it configures
     * actually enforces — {@code tson.processorPolicy()}, or {@code applyTo(config).processorPolicy()} before
     * one is built.
     *
     * <p><b>Derived from what is enforced, not copied from the descriptor</b>: a descriptor stating no policy
     * still publishes the library's defaults, a client needs no library's defaults to know what applies, and
     * what is published cannot disagree with what refuses a request.
     *
     * <p>{@code schema_hosts} and {@code listener} are dropped: which origins this deployment trusts is
     * nobody else's business, and where it listens is something a counterparty already knows. The limits are
     * kept, inside {@code policy}: a 413 says a document went past a bound, and a sender that read the bound
     * first never writes past it.
     */
    public AcceptanceProfile profile(ProcessorPolicy inForce) {
        return new AcceptanceProfile(name, Policy.of(inForce));
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
