package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Top;

/**
 * A type name where it lands: past an extern ([TSON-SCHEMA] §5.13) to the foreign entry it names, in the namespace of
 * the schema that holds it, or the entry itself where the name reaches no extern. Every check that judges a payload
 * type's definition reads it through this, so a record reached through an extern is judged as the record it is.
 *
 * <p><b>Each hop is a landing of its own.</b> A name an entry's declaration writes is landed from that entry's
 * namespace ({@link #resolve}), and a name the definition it lands on writes from the next landing's, so a type
 * expression is resolved in the schema declaring the entry that holds it, however many externs lie between: a field
 * typed by an extern of an array has its element type looked up where the array is declared, never in the record's
 * schema. A check walking a definition carries the landing it came from alongside it, never a bare {@link Top}.
 *
 * <p>{@code in} may hold the entry by import rather than declare it: an extern may name an entry its schema imports,
 * and a name with no extern lands in the namespace it was written in. That namespace resolves every name the entry
 * writes as its declaring schema does, since an import merges the declarer's whole namespace and admits no
 * redefinition of a name it brings, so the landing needs no registry lookup of the declaring schema.
 * {@link #identity()} names the declaring schema where a reader needs it.
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
}
