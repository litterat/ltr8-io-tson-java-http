package io.ltr8.tson.http;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;

import java.util.Map;
import java.util.Objects;

/**
 * A schema-type-name-to-Java-class map as a {@link DataBindContext} -- the three steps that turn one into the
 * other, in one place rather than at every call site.
 *
 * <p>The map is a {@link DataNameBinder} <b>chained over the kernel's own vocabulary rather than replacing
 * it</b>, with the atom registrations applied. Two of those three steps are invisible from the call site, and
 * missing either fails a long way from the cause -- which is why a hand-rolled {@code DataBindContext.builder()}
 * is the wrong shape for this even though it is what this delegates to.
 *
 * <p>The chain is a backstop, not the main path: a map-only binder resolves an ordinary user schema perfectly
 * well, the kernel's names being resolved through the resolution core's own context. It earns its keep where a
 * schema names a kernel or meta record type. That is also why <b>the map authors the failure</b> -- the last
 * binder consulted is the backstop, and letting it speak would report a missing line of this application's
 * configuration as "not kernel vocabulary".
 *
 * <p>A meta layer's vocabulary is a separate namespace and does not belong here; that is
 * {@code ProcessorConfig.withMetaNameBinder}, which binds what a schema is written <em>in</em> rather than the
 * data a schema describes.
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
            if (bound != null) {
                return bound;
            }
            try {
                return SchemaMetaNameBinder.INSTANCE.resolve(name);
            } catch (DataBindException notKernelVocabulary) {
                throw new DataBindException("'" + name + "' is not bound: the bindings map " + mapped.keySet()
                        + ", and it is not the kernel's own vocabulary either", notKernelVocabulary);
            }
        };
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder);
        if (profile != null) {
            builder.profile(profile);
        }
        return builder.registerAtoms(AtomContext.hostTypes()).build();
    }
}
