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
 * declares {@code !!schema:"…/problem-3.tn"}, and a client that wants to validate what it received needs that
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
    public static final String ID = "https://tson.io/2026/32/ltr8/http/problem-3.tn";

    /**
     * Every version of this schema that is still published, current first.
     *
     * <p>§10 makes a published schema immutable: {@code problem-1.tn} and {@code problem-2.tn} are superseded by {@code problem-3.tn},
     * which adds RFC 9457's {@code type} and {@code instance}, but a document that named the old one must go on
     * resolving — so it stays served. Nothing new is written against it. Hand this to
     * {@link TsonSchemaCatalog#of(java.util.Collection)} and a server publishes the whole history.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE, SUPERSEDED_2, SUPERSEDED_1);
    }

    /**
     * Every published version keyed by its own identity -- for a caller wiring a {@code TsonSchemaSource} by
     * hand, where {@link #publishedSources()} is for one that serves them over HTTP.
     *
     * <p>Serving the current version's text at a superseded version's URI is the mistake this exists to stop.
     * It fails loudly rather than silently -- the loader cross-checks a fetched document's {@code !!id}
     * against the reference and reports an identity mismatch -- but the message points at the fetch, a long
     * way from the map that was wrong.
     */
    public static Map<String, String> publishedById() {
        return Map.of(ID, SOURCE, ID_2, SUPERSEDED_2, ID_1, SUPERSEDED_1);
    }

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "problem", TsonProblem.class,
            "diagnostic", TsonProblemDiagnostic.class,
            "diagnostic_code", Diagnostic.Code.class);

    private static final String SOURCE = readResource("/problem-3.tn");

    /** Superseded, still published. See {@link #publishedSources()}. */
    public static final String ID_2 = "https://tson.io/2026/32/ltr8/http/problem-2.tn";

    /** Superseded, still published. See {@link #publishedSources()}. */
    public static final String ID_1 = "https://tson.io/2026/32/ltr8/http/problem-1.tn";

    /** Superseded, still published. See {@link #publishedSources()}. */
    private static final String SUPERSEDED_2 = readResource("/problem-2.tn");

    /** Superseded, still published. See {@link #publishedSources()}. */
    private static final String SUPERSEDED_1 = readResource("/problem-1.tn");

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
