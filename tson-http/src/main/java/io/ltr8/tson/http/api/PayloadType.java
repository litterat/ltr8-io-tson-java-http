package io.ltr8.tson.http.api;

import io.ltr8.tson.base.CanonicalIdentity;

/**
 * A payload type where it is declared: the schema declaring it and its name there. A name a description reaches
 * through an extern ([TSON-SCHEMA] §5.13) is the foreign entry the extern lands on, never the extern's local name, so
 * a description naming {@code order_v1} and {@code order_v2}, externs of two schemas' {@code order}, names two payload
 * types of one name.
 *
 * <p>This is what a reader asks the bind context for the type's class, by {@link #identity()} and {@link #name()},
 * and what the codec takes to read or write a value of the type by its own schema, {@link #schemaId()} and {@link
 * #name()}.
 *
 * @param schemaId the declaring schema's {@code !!id}, as {@code readObjectAs}, {@code describing} and {@code
 *                 prepareToRead} take it
 * @param name     the type's name in the declaring schema
 */
public record PayloadType(String schemaId, String name) {

    /**
     * The declaring schema's canonical identity ([TSON-DATA] §2.2.1): the namespace a reader asks the binder for the
     * type within, as {@code DataNameBinder.byNamespace} is keyed.
     */
    public String identity() {
        return CanonicalIdentity.canonicalize(schemaId);
    }
}
