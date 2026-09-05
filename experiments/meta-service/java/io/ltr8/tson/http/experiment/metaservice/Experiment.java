package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.TsonConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Where this experiment's files are, and the binder for its vocabulary -- shared by the two probes. */
final class Experiment {

    /** The meta layer under test, read from {@code experiments/meta-service/} rather than copied into a string. */
    static final String META_ID = "https://tson.io/2026/35/ltr8/http/meta-service-1.tn";

    private Experiment() {
    }

    /** {@code experiments.dir} is set by the Gradle test task; the fallback is the module directory's parent. */
    static String metaServiceSource() {
        Path dir = Path.of(System.getProperty("experiments.dir", "../experiments"));
        try {
            return Files.readString(dir.resolve("meta-service/meta-service-1.tn"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bound classes for the sketch's {@code data} constructors, as {@code TsonApiSchema.metaNameBinder()} does. */
    static TsonConfig bindVocabulary(TsonConfig config) {
        return config.metaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                Set.of("io.ltr8.tson.http.experiment.metaservice"), Map.of()));
    }
}
