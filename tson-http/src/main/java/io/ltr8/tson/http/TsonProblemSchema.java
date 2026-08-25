package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * This project's own {@code problem-1.tn} -- the schema every error body is written against -- as source text a
 * server can serve, and as a compiled schema bound to {@link TsonProblem}/{@link TsonProblemDiagnostic}.
 *
 * <p><b>{@link #source} exists because the {@code !!id} in an error body has to resolve.</b> A problem body
 * declares {@code !!schema:"…/problem-1.tn"}, and a client that wants to validate what it received needs that
 * document. Serving it from this constant is what makes the URL in the body true rather than decorative.
 *
 * <p><b>This is tson-http's own schema.</b> It began as a copy of {@code tson-cli}'s {@code diagnostics.tn} and
 * has diverged: a CLI reports on files and a server reports on requests, so {@code problem} follows RFC 9457
 * while the CLI's envelope is built around file reports. Holding one shape across both would have meant neither
 * fitting.
 *
 * <p>{@link #tson} builds a fresh {@link Tson} of its own each call, with this schema resolved and bound to the
 * two wire records, so registering it never collides with an application's. It is the read path, not the write
 * path: {@link TsonHttpCodec#writeProblem} emits a problem with a plain object writer, and reading that text back
 * through this is what demonstrates the emitted body is genuinely valid against a real schema rather than merely
 * shaped like it. A client that wants to validate the error bodies it receives uses the same thing.
 */
public final class TsonProblemSchema {

    /** The current error-body schema's identity -- the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/33/ltr8/http/problem-1.tn";

    /**
     * Every version of this schema that is still published, current first -- one, today.
     *
     * <p>§3.5 makes a <em>published</em> schema immutable: a shape change is a new name, and the superseded
     * document stays served because something may already name it. Nothing here is published, so there is one
     * version and it is edited in place. This method exists as the shape a server needs when that stops being
     * true -- hand it to {@link TsonSchemaCatalog#of(java.util.Collection)} and the whole history is published
     * at each document's own identity path, however long the history gets.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE);
    }

    /**
     * The same history keyed by identity, for a caller wiring a {@code TsonSchemaSource} by hand rather than
     * serving it over HTTP.
     *
     * <p>Worth using even at one version: serving the current text at a superseded version's URI fails as an
     * identity mismatch from the loader, whose message points at the fetch rather than at the map that was
     * wrong.
     */
    public static Map<String, String> publishedById() {
        return Map.of(ID, SOURCE);
    }

    /** The two wire records and the code enum -- everything this schema declares that a read binds. */
    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "problem", TsonProblem.class,
            "diagnostic", TsonProblemDiagnostic.class,
            "diagnostic_code", Diagnostic.Code.class);

    private static final String SOURCE = readResource("/problem-1.tn");

    private TsonProblemSchema() {
    }

    /** The current error-body schema's own source text. */
    public static String source() {
        return SOURCE;
    }

    /**
     * A fresh {@link Tson} with {@code problem-1.tn} resolved and bound to {@link TsonProblem}/
     * {@link TsonProblemDiagnostic} -- what a client reads an error body back through.
     */
    public static Tson tson() {
        Tson tson = Tson.builder().schemaSource(uri -> SOURCE).bindings(BINDINGS).build();
        tson.resolve(SOURCE);
        return tson;
    }

    /**
     * This schema compiled in object-binding mode -- what {@code TsonProblemSchemaTest} reads the declared
     * {@code diagnostic_code} members out of, so the check is against what a reader would actually enforce
     * rather than against the source text.
     */
    public static TsonCompiledSchema compiled() {
        return tson().bindRegistry().get(ID);
    }

    private static String readResource(String path) {
        try (InputStream in = TsonProblemSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
