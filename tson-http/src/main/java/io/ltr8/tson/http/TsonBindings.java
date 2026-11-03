package io.ltr8.tson.http;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;

import java.util.Map;
import java.util.Objects;

/**
 * A schema-type-name-to-Java-class map as a {@link DataBindContext} -- the steps that turn one into the other, in
 * one place rather than at every call site.
 *
 * <p>The map is a {@link DataNameBinder} with the atom registrations applied, and <b>the map authors the
 * failure</b>: a name it does not bind is reported as a missing line of this application's configuration, listing
 * what it does bind. The kernel's own vocabulary never reaches it: {@code Tson.of} composes that over the
 * configured binder and answers it first, so a map binds the application's names and nothing else.
 *
 * <p>One context binds a schema's value entries and a data document's values alike, so a vocabulary a schema
 * imports belongs in the same map: {@code TsonApiSchema.bindingsWith} adds {@code meta-service.tn}'s records to an
 * application's.
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
     * <p>A profile is fixed when a context is built, so a server speaking two versions builds one context per
     * version and routes a document to the right one. Nothing here derives the profile from the schema a
     * document names: that mapping is the application's, and it is the one thing the application knows better.
     *
     * @param profile the profile name, or {@code null} for each class's canonical constructor
     */
    public static DataBindContext of(Map<String, Class<?>> bindings, String profile) {
        Map<String, Class<?>> mapped = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        DataNameBinder binder = name -> {
            Class<?> bound = mapped.get(name);
            if (bound == null) {
                throw new DataBindException("'" + name + "' is not bound: the bindings map " + mapped.keySet());
            }
            return bound;
        };
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder);
        if (profile != null) {
            builder.profile(profile);
        }
        return builder.registerAtoms(AtomContext.hostTypes()).build();
    }
}
