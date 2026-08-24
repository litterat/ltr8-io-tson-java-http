package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.TsonConfig;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Several versions of one schema served side by side, each with its own {@link TsonHttpCodec}, and a request
 * routed to the right one by the schema its document declares.
 *
 * <h2>Why a codec per version, and not one codec for all of them</h2>
 *
 * <p>Because binding resolves a schema <em>type name</em> to a Java class, and
 * {@code DataNameBinder.resolve(String)} is handed the name alone -- no schema, no version. Two versions of a
 * schema both declare {@code order}, so one binder cannot map that name to two classes. Try it and the failure
 * is at least loud: <em>"the schema's root type `order` binds to OrderV1, which is not assignable to the
 * requested OrderV2"</em>. So each version gets its own {@code DataBindContext}, and that is what this holds.
 *
 * <p>Tree-mode reads have no such problem -- no classes are involved, and one {@code Tson} happily holds every
 * version at once. This exists for the binding case.
 *
 * <h2>The reason routing is a safety feature, not a convenience</h2>
 *
 * <p><b>A codec built for v1 will read a v2 document and silently drop what its class has no component for.</b>
 * Given {@code order-2.tn} adding a {@code currency} field, a v1 codec that can reach that schema returns
 * {@code OrderV1[sku=A, quantity=1]} -- no error, and <em>no diagnostic even under a collecting receiver</em>
 * ({@code UPSTREAM.md} #10). The document was read correctly against its own schema; it is the bind to a class
 * with fewer components that discards the field. For an order, that is the wrong currency rather than a
 * rejected request.
 *
 * <p>So {@link #route} refuses a document naming a schema this endpoint does not serve, and refuses one naming
 * none, rather than letting either fall through to a codec that will quietly do the wrong thing. That refusal
 * is the whole point.
 *
 * <h2>Two ways to model the Java side</h2>
 *
 * <ul>
 *   <li><b>A class per version</b> ({@code OrderV1}, {@code OrderV2}), switching on {@link Routed#schemaId()}.
 *       Explicit, and each class exactly matches its schema.</li>
 *   <li><b>One class across versions</b>, holding the union of every version's fields with a
 *       {@code @Profile}-annotated constructor per version, served by
 *       {@link Builder#version(String, String, TsonSchemaSource, Map, String)}. Each constructor supplies the
 *       default for the field its own version does not carry, so the class is complete whichever document
 *       built it. This needs the profile: without one, strict binding refuses the class outright, because the
 *       union is a shape no version's schema declares.</li>
 * </ul>
 *
 * <p><b>A constructor is selected by profile, and by nothing else.</b> An unannotated second constructor is
 * invisible to binding -- the canonical one is always used, so a record with two shapes and no {@code @Profile}
 * binds through the wrong one or not at all. Naming the profile is what makes the choice, and it is matched by
 * equality against the context's own.
 *
 * <h2>Major versions across separate servers</h2>
 *
 * <p>Nothing here requires one process. Routing by declared schema works the same whether the versions are
 * codecs in one server or hosts behind a proxy. A gateway that needs only the routing answer, without a codec,
 * calls {@link io.ltr8.tson.compiler.TsonDocumentHeader#peekResumable} directly and forwards the
 * {@code document()} it hands back -- which is the whole document, first byte included, so a one-shot body
 * survives being looked at. This class used to offer a {@code declaredSchemaOf} for that and it was wrong:
 * it returned the schema and left the caller a body that had been read to the end.
 */
public final class TsonSchemaVersions {

    private final Map<String, TsonHttpCodec> byIdentity;
    private final Map<String, String> declaredIds;
    private final Optional<String> defaultSchemaId;

    private TsonSchemaVersions(Map<String, TsonHttpCodec> byIdentity, Map<String, String> declaredIds,
                               Optional<String> defaultSchemaId) {
        this.byIdentity = Map.copyOf(byIdentity);
        this.declaredIds = Map.copyOf(declaredIds);
        this.defaultSchemaId = defaultSchemaId;
    }

    /**
     * A document routed to the codec for the version it declares.
     *
     * @param schemaId this endpoint's <b>registered</b> identity for the matched version, not the reference the
     *                 document happened to spell. A client may write the same identity with a different scheme
     *                 or a {@code ?sha256=} pin (§2.2.1), and a caller switching on the version must not see
     *                 that; the registered id is the stable value to switch on.
     */
    public record Routed(String schemaId, TsonHttpCodec codec, InputStream body) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The schema identities this serves, as declared. */
    public Set<String> schemaIds() {
        return Set.copyOf(declaredIds.values());
    }

    /**
     * The codec for {@code schemaId}, matched by canonical identity (§2.2.1 -- scheme and any {@code ?sha256=}
     * pin do not count).
     *
     * @throws TsonHttpException 400 if this endpoint serves no such schema
     */
    public TsonHttpCodec codecFor(String schemaId) {
        TsonHttpCodec codec = byIdentity.get(identityOf(schemaId));
        if (codec == null) {
            throw unknownSchema(schemaId);
        }
        return codec;
    }

    /** {@link #route(InputStream, String)} for a message carrying no {@code TSON-Schema} header. */
    public Routed route(InputStream body) {
        return route(body, null);
    }

    /**
     * Routes {@code body} to the codec for the version that governs it, handing back a stream still positioned
     * at the start.
     *
     * <p>The schema comes from the {@code TSON-Schema} header, the body's own {@code !!schema}, or both -- and
     * where both are present they must agree, which {@link TsonSchemaHeader#resolve} enforces. A JSON body has
     * only the header, which is the case the header exists for.
     *
     * <p><b>This still peeks at the body.</b> A header cannot be trusted over a directive that contradicts it,
     * so verification costs the same read it always did. The header's value is to whatever routed the request
     * <em>here</em> -- a gateway that will not parse a body, and cannot parse a compressed one.
     *
     * @param body       the message body
     * @param fieldValue the {@code TSON-Schema} header value, or {@code null}
     * @throws TsonHttpException 400 if nothing names a schema and no default is configured, if the header and
     *                           the body disagree, or if the schema named is one this endpoint does not serve
     */
    public Routed route(InputStream body, String fieldValue) {
        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(body, fieldValue);
        String schemaId = governing.schema().or(() -> defaultSchemaId).orElseThrow(() -> new TsonHttpException(
                TsonHttpException.BAD_REQUEST, TsonHttpException.TYPES + "no-schema-declared", "No schema declared",
                "this endpoint serves several schema versions, so a message must name the one that governs it "
                        + "-- in a " + TsonSchemaHeader.NAME + " header or a !!schema directive; it serves "
                        + schemaIds(), List.of(), null));
        // The registered id, not what the message spelled -- see Routed's own note.
        return new Routed(declaredIds.get(identityOf(schemaId)), codecFor(schemaId), governing.body());
    }

    private TsonHttpException unknownSchema(String schemaId) {
        return new TsonHttpException(TsonHttpException.BAD_REQUEST,
                TsonHttpException.TYPES + "unsupported-schema-version", "Unsupported schema version",
                "this endpoint does not serve '" + schemaId + "'; it serves " + schemaIds(), List.of(), null);
    }

    /** Canonical identity, or a 400 -- a reference that is not a legal identity names no version. */
    private static String identityOf(String schemaId) {
        try {
            return TsonCanonicalIdentity.canonicalize(schemaId);
        } catch (RuntimeException notAnIdentity) {
            throw new TsonHttpException(TsonHttpException.BAD_REQUEST,
                    TsonHttpException.TYPES + "unsupported-schema-version", "Unsupported schema version",
                    "'" + schemaId + "' is not a schema identity: " + notAnIdentity.getMessage(), List.of(),
                    notAnIdentity);
        }
    }

    /** Builds a {@link TsonSchemaVersions}. */
    public static final class Builder {

        private final Map<String, TsonHttpCodec> byIdentity = new LinkedHashMap<>();
        private final Map<String, String> declaredIds = new LinkedHashMap<>();
        private Optional<String> defaultSchemaId = Optional.empty();

        private Builder() {
        }

        /**
         * Serves {@code schemaId} through {@code codec} -- for a caller that has already wired its own
         * {@code Tson}. Each version must have its own, for the reason in the class note.
         */
        public Builder version(String schemaId, TsonHttpCodec codec) {
            String identity = identityOf(schemaId);
            if (byIdentity.putIfAbsent(identity, codec) != null) {
                throw new IllegalArgumentException("'" + schemaId + "' is already served; two codecs for one "
                        + "canonical identity would make routing arbitrary");
            }
            declaredIds.put(identity, schemaId);
            return this;
        }

        /**
         * Serves {@code schemaText}, building the {@code Tson} and its own bind context from {@code bindings}
         * -- the per-version wiring, which {@code TsonConfig.bindings} reduced to one call.
         *
         * @param schemaId the schema's identity, as documents will name it
         * @param source   where this version's schema and its imports come from
         * @param bindings this version's schema type names to their Java classes
         */
        public Builder version(String schemaId, String schemaText, TsonSchemaSource source,
                               Map<String, Class<?>> bindings) {
            return version(schemaId, schemaText, source, bindings, null);
        }

        /**
         * The same, under a <b>binding profile</b> -- which is what lets one Java class serve several
         * versions, with a constructor per version marked {@code @Profile}.
         *
         * <p>The profile is a label the caller chose and is matched by equality; nothing in the binder knows
         * what schema it stands for. Prefer a stable one ({@code "api-1"}) to a schema identity, because an
         * identity changes with the version and the annotation should not have to. A class with no
         * constructor for this profile falls back to its canonical (or {@code @Record}) one, which is how a
         * version whose shape is the class's own needs no annotation at all.
         *
         * <p><b>Selection does not replace the agreement check.</b> The profile picks a constructor and
         * strict binding then verifies that constructor against this version's schema, so a profile pointed
         * at the wrong version fails here rather than binding the other version's shape -- which is why
         * {@link TsonHttpCodec#prepareToRead} is called below and this is a startup failure.
         *
         * @param profile the binding profile for this version, or {@code null} for none
         */
        public Builder version(String schemaId, String schemaText, TsonSchemaSource source,
                               Map<String, Class<?>> bindings, String profile) {
            Map<String, Class<?>> copy = Map.copyOf(bindings);
            TsonConfig config = Tson.builder().schemaSource(source).bindings(copy);
            if (profile != null) {
                config.profile(profile);
            }
            Tson tson = config.build();
            tson.resolve(schemaText);
            TsonHttpCodec codec = new TsonHttpCodec(tson);
            copy.values().forEach(target -> codec.prepareToWrite(target));
            // Bind-mode compile now: a class that disagrees with the schema it is registered against is a
            // startup failure here, not a 500 on the first request that happens to read one.
            codec.prepareToRead(schemaId);
            return version(schemaId, codec);
        }

        /**
         * The version to assume for a document that declares no {@code !!schema}.
         *
         * <p>Off by default, and worth leaving off. A document that names no version is one whose author has
         * not said which contract they are writing to, and picking for them is how a v2 client silently gets
         * v1 behaviour -- the failure this whole class exists to prevent. Configure it only where an older
         * unversioned client has to keep working.
         */
        public Builder defaultVersion(String schemaId) {
            this.defaultSchemaId = Optional.of(schemaId);
            return this;
        }

        public TsonSchemaVersions build() {
            if (byIdentity.isEmpty()) {
                throw new IllegalStateException("no versions declared");
            }
            defaultSchemaId.ifPresent(id -> {
                if (!byIdentity.containsKey(identityOf(id))) {
                    throw new IllegalArgumentException("the default version '" + id + "' is not one of the "
                            + "versions served: " + declaredIds.values());
                }
            });
            return new TsonSchemaVersions(byIdentity, declaredIds, defaultSchemaId);
        }

        private static String identityOf(String schemaId) {
            try {
                return TsonCanonicalIdentity.canonicalize(schemaId);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("'" + schemaId + "' is not a schema identity: "
                        + e.getMessage(), e);
            }
        }
    }
}
