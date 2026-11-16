package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Top;

import java.util.function.Function;

/**
 * A type name where it lands: past an extern ([TSON-SCHEMA] §5.13) to the foreign entry it names, in the namespace of
 * the schema that holds it, or the entry itself where the name reaches no extern. Every check that judges a payload
 * type's definition reads it through this, so a record reached through an extern is judged as the record it is, and the
 * names its own declaration writes are looked up in its own schema's namespace, never the description's.
 *
 * @param in   the linked schema whose namespace holds the entry
 * @param name the entry's name there
 */
record Landed(TsonLinkedSchema in, String name) {

    /** {@code name}, written in {@code scope}'s namespace, past its alias hops and its externs. */
    static Landed of(TsonLinkedSchema scope, String name) {
        return scope.landing(name).map(landing -> new Landed(landing.in(), landing.name()))
                .orElseGet(() -> new Landed(scope, name));
    }

    /** The entry's definition; {@code null} for a name its namespace does not hold. */
    Top definition() {
        return in.schema().entries().get(name);
    }

    /**
     * The canonical identity of the schema declaring the entry: with {@link #name()}, what a reader asks the bind
     * context for the entry's class.
     */
    String identity() {
        return in.declaringIdentity(name);
    }

    /** A name this entry's own declaration writes, landed from this entry's namespace. */
    Landed resolve(String written) {
        return of(in, written);
    }

    /** The definitions of the names this entry's own declaration writes, each past its externs. */
    Function<String, Top> entries() {
        return written -> resolve(written).definition();
    }
}
