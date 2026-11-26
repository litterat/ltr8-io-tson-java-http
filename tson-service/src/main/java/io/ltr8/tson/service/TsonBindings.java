package io.ltr8.tson.service;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A schema-type-name-to-Java-class map as a {@link DataBindContext} -- the steps that turn one into the other, in
 * one place rather than at every call site.
 *
 * <p>The map is a {@link DataNameBinder} with the atom registrations applied, and <b>the map authors the
 * failure</b>: a name it does not bind is reported as a missing line of this application's configuration, listing
 * what it does bind. The kernel's own vocabulary never reaches it: {@code Tson.of} composes that over the
 * configured binder and answers it first, so a map binds the application's names and nothing else.
 *
 * <p>{@link #of(Map)} binds by name alone, the same class for a name whichever schema declares it; {@link #bySchema}
 * keys a map per schema, for two schemas each declaring one name.
 *
 * <p>One context binds a schema's value entries and a data document's values alike, so a vocabulary a schema
 * imports belongs in the same map: {@code TsonServiceSchema.bindingsWith} adds {@code service.tn}'s records to an
 * application's.
 *
 * <p><b>{@code Object} is registered as an atom</b>, so a dependent field ({@code payload: kind!}, [TSON-SCHEMA]
 * §5.2) binds to an {@code Object} component that receives whatever the type its sibling names reads to. Without it
 * the class cannot be analysed, and the library defers that as a missing binding: the schema prepares clean and every
 * read of the record is a 500. The cost is that any component erased to {@code Object} binds the same way rather than
 * being refused; which types a sender may name there is held at startup by {@code TsonServiceDescription}.
 */
public final class TsonBindings {

    private TsonBindings() {
    }

    /** {@code bindings} as a bind context, under the canonical constructor of each bound class. */
    public static DataBindContext of(Map<String, Class<?>> bindings) {
        return of(bindings, null);
    }

    /**
     * {@code bindings} as a bind context, selecting each class's {@code @Profile} constructor named
     * {@code profile} -- so one class can serve several versions of a schema.
     *
     * <p>A profile is fixed when a context is built, so a server whose one class serves two versions builds one
     * context per version and routes a document to the right one; a class per version needs no profile, and binds
     * through one context by {@link #bySchema}. Nothing here derives the profile from the schema a
     * document names: that mapping is the application's, and it is the one thing the application knows better.
     *
     * @param profile the profile name, or {@code null} for each class's canonical constructor
     */
    public static DataBindContext of(Map<String, Class<?>> bindings, String profile) {
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder(bindings));
        if (profile != null) {
            builder.profile(profile);
        }
        return withAtoms(builder);
    }

    /**
     * Each schema's map as one bind context: a type binds through the map of the schema declaring it, so two schemas
     * each declaring {@code order}, two versions of one, bind it to two classes through one {@code Tson}. A reader
     * asks the binder for a type within the canonical identity of the schema declaring it ([TSON-DATA] §2.2.1), and
     * for an extern ([TSON-SCHEMA] §5.13) the entry it lands on, so a schema's map binds the names that schema
     * declares, never another schema's local name for one of them. This is {@link DataNameBinder#byNamespace} over
     * one map per schema.
     *
     * <p>A schema's map authors the failure for its own names, as {@link #of(Map)}'s does, and a name asked within a
     * schema no map is keyed by is refused listing the schemas that are. Every schema whose types are bound is keyed,
     * a vocabulary such as {@code TsonServiceSchema.bindings()} under its own identity among them.
     *
     * @param bindings each schema, by its {@code !!id} or any spelling of the same identity, to its names' classes
     */
    public static DataBindContext bySchema(Map<String, Map<String, Class<?>>> bindings) {
        Map<String, DataNameBinder> binders = new LinkedHashMap<>();
        Objects.requireNonNull(bindings, "bindings").forEach((schema, names) -> {
            if (binders.put(CanonicalIdentity.canonicalize(schema), binder(names)) != null) {
                throw new IllegalArgumentException("'" + schema + "' is keyed twice, as two spellings of one identity");
            }
        });
        Set<String> keyed = Set.copyOf(binders.keySet());
        DataNameBinder unkeyed = new DataNameBinder() {
            @Override
            public Class<?> resolve(String name) throws DataBindException {
                throw new DataBindException("'" + name + "' is asked with no schema, and these bindings are keyed by "
                        + "schema: " + keyed);
            }

            @Override
            public Class<?> resolve(String namespace, String name) throws DataBindException {
                throw new DataBindException("'" + name + "' of '" + namespace + "' is not bound: the bindings are "
                        + "keyed by schema, and hold " + keyed);
            }
        };
        return withAtoms(DataBindContext.builder().nameBinder(DataNameBinder.byNamespace(binders, unkeyed)));
    }

    /** {@code bindings} as a name binder that authors its own failure. */
    private static DataNameBinder binder(Map<String, Class<?>> bindings) {
        Map<String, Class<?>> mapped = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        return name -> {
            Class<?> bound = mapped.get(name);
            if (bound == null) {
                throw new DataBindException("'" + name + "' is not bound: the bindings map " + mapped.keySet());
            }
            return bound;
        };
    }

    private static DataBindContext withAtoms(DataBindContext.Builder builder) {
        return builder.registerAtoms(AtomContext.hostTypes()).registerAtom(Object.class).build();
    }
}
