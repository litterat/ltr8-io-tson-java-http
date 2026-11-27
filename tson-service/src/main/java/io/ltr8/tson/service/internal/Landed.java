package io.ltr8.tson.service.internal;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A type name where it lands, in the namespace of the schema that holds it. The questions it answers are kept apart:
 *
 * <ul>
 *   <li><b>What name the binder is asked</b> ({@link #of}, {@link #resolve}, {@link #name()}, {@link #identity()}):
 *       past an extern ([TSON-SCHEMA] §5.13), and the alias hops on both sides of each boundary, to the foreign entry
 *       it names, which is the entry a reader asks for; a name reaching no extern, a plain alias among them, is the
 *       entry as written, though a TSON read of a plain alias asks the binder for the entry the alias reaches.</li>
 *   <li><b>What shape the type has</b> ({@link #pastAliases()}): that landing past its remaining plain alias hops, so
 *       a check judging a type's definition reads a record reached through an extern or through an alias as the
 *       record it is.</li>
 *   <li><b>What class binds it</b> ({@link #boundClass}): the class a bind context gives this entry, asked as a
 *       reader asks it, by the schema declaring the entry and its name there.</li>
 * </ul>
 *
 * <p><b>Each hop is a landing of its own.</b> A name an entry's declaration writes is landed from that entry's
 * namespace ({@link #resolve}), and a name the definition it lands on writes from the next landing's, so a type
 * expression is resolved in the schema declaring the entry that holds it, however many externs and aliases lie
 * between: a field typed by an extern of an array has its element type looked up where the array is declared, never in
 * the record's schema. A check walking a definition carries the landing it came from alongside it, never a bare
 * {@link Top}.
 *
 * <p>{@code in} may hold the entry by import rather than declare it: an extern may name an entry its schema imports,
 * and a name with no extern lands in the namespace it was written in. That namespace resolves every name the entry
 * writes as its declaring schema does, since an import merges the declarer's whole namespace and admits no
 * redefinition of a name it brings, so the landing needs no registry lookup of the declaring schema.
 * {@link #identity()} names the declaring schema where a reader needs it.
 *
 * <p>This package is not exported: it holds what the {@code protocol} and {@code http} packages share, and imports
 * neither.
 *
 * @param in   the linked schema whose namespace holds the entry
 * @param name the entry's name there
 */
public record Landed(TsonLinkedSchema in, String name) {

    /**
     * {@code name}, written in {@code scope}'s namespace: where it reaches an extern, the foreign entry past the alias
     * hops on both sides of each boundary; otherwise {@code name} itself, a plain alias kept as written.
     */
    public static Landed of(TsonLinkedSchema scope, String name) {
        return scope.landing(name).map(landing -> new Landed(landing.in(), landing.name()))
                .orElseGet(() -> new Landed(scope, name));
    }

    /**
     * This entry past its plain alias hops: the entry whose definition is not an {@link Alias}, each hop landed from
     * the namespace of the alias writing it, so a hop reaching an extern crosses it as {@link #of} does. A chain that
     * closes on itself stops at the alias where it closes, which no check reads as a record or a container.
     */
    public Landed pastAliases() {
        Landed at = this;
        Set<String> seen = new HashSet<>();
        while (at.definition() instanceof Alias(TypeRef target) && seen.add(at.in.identity() + "#" + at.name)) {
            at = at.resolve(target.name());
        }
        return at;
    }

    /** The entry's definition; {@code null} for a name its namespace does not hold. */
    public Top definition() {
        return in.schema().entries().get(name);
    }

    /**
     * The canonical identity of the schema declaring the entry: with {@link #name()}, what the bind context is asked
     * for the entry's class.
     */
    public String identity() {
        return in.declaringIdentity(name);
    }

    /** A name this entry's own declaration writes, landed from this entry's namespace. */
    public Landed resolve(String written) {
        return of(in, written);
    }

    /**
     * The class {@code context} maps this entry to, asked by {@link #identity()} and {@link #name()} as a reader
     * asks it, without analysing the class; empty where nothing binds it.
     */
    public Optional<Class<?>> boundClass(DataBindContext context) {
        try {
            return Optional.ofNullable(context.nameBinder().resolve(identity(), name));
        } catch (DataBindException unbound) {
            return Optional.empty();
        }
    }
}
