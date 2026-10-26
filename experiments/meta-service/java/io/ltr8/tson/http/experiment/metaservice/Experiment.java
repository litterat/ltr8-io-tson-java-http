package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shipped meta layer as this experiment's probes use it, and the reading of a value held under a namespace key.
 * {@code meta-service.tn} graduated into {@code tson-http}; what stays here probes it from directions the shipped
 * suite does not -- the agent layers, the wire form, and the findings the map design rests on.
 */
final class Experiment {

    /** The meta layer under test: {@code tson-http}'s own. */
    static final String META_ID = TsonApiSchema.ID;

    private Experiment() {
    }

    static String metaServiceSource() {
        return TsonApiSchema.source();
    }

    /**
     * Every value held under a key of an entry applying the namespace type {@code constructor}, by its key --
     * {@code interfaces} or {@code apis}. An interface or an api is a record under a key, not an entry, so this is
     * how a reader finds one by name.
     */
    static <T> Map<String, T> held(Namespace<Top> entries, String constructor, Class<T> type) {
        Map<String, T> found = new LinkedHashMap<>();
        entries.values().forEach(value -> {
            if (value instanceof Namespace<?> namespace && namespace.type().equals(constructor)) {
                namespace.forEach((key, held) -> found.put(key, type.cast(held)));
            }
        });
        return found;
    }

    /** The bound classes for the layer's records. */
    static ProcessorConfig bindVocabulary(ProcessorConfig config) {
        return config.withMetaNameBinder(TsonApiSchema.metaNameBinder());
    }
}
