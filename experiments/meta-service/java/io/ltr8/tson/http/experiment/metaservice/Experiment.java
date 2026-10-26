package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Where this experiment's files are, and the binder for its vocabulary -- shared by the two probes. */
final class Experiment {

    /** The meta layer under test, read from {@code experiments/meta-service/} rather than copied into a string. */
    static final String META_ID = "https://tson.io/2026/38/io/ltr8/http/meta-service.tn";

    private Experiment() {
    }

    /** {@code experiments.dir} is set by the Gradle test task; the fallback is the module directory's parent. */
    static String metaServiceSource() {
        Path dir = Path.of(System.getProperty("experiments.dir", "../experiments"));
        try {
            return Files.readString(dir.resolve("meta-service/meta-service.tn"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * {@link #held}, keyed by the qualified name a reference spells -- the entry, then the key ({@code
     * orders.orders}) -- which is how {@link Routes} matches a {@code ref<interface>} or a {@code ref<method>} to
     * the interface it names.
     */
    static <T> Map<String, T> heldByName(Namespace<Top> entries, String constructor, Class<T> type) {
        Map<String, T> found = new LinkedHashMap<>();
        entries.forEach((entry, value) -> {
            if (value instanceof Namespace<?> namespace && namespace.type().equals(constructor)) {
                namespace.forEach((key, held) -> found.put(entry + "." + key, type.cast(held)));
            }
        });
        return found;
    }

    /** The bound classes for the sketch's records, as {@code TsonApiSchema.metaNameBinder()} does. */
    static ProcessorConfig bindVocabulary(ProcessorConfig config) {
        return config.withMetaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                Set.of("io.ltr8.tson.http.experiment.metaservice"), Map.of()));
    }
}
