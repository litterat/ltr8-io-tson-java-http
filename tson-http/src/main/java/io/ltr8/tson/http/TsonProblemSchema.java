package io.ltr8.tson.http;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * This project's own {@code problem-1.tn} -- the schema every error body is written against -- as source text a
 * server can serve, and as a compiled schema bound to {@link TsonProblem}/{@link TsonProblemDiagnostic}.
 *
 * <p><b>{@link #source} exists because the {@code !!id} in an error body has to resolve.</b> A problem body
 * declares {@code !!schema:"…/problem-2.tn"}, and a client that wants to validate what it received needs that
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
    public static final String ID = "https://tson.io/2026/32/ltr8/http/problem-2.tn";

    /**
     * Every version of this schema that is still published, current first.
     *
     * <p>§10 makes a published schema immutable: {@code problem-1.tn} is superseded by {@code problem-2.tn},
     * which adds RFC 9457's {@code type} and {@code instance}, but a document that named the old one must go on
     * resolving — so it stays served. Nothing new is written against it. Hand this to
     * {@link TsonSchemaCatalog#of(java.util.Collection)} and a server publishes the whole history.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE, SUPERSEDED_1);
    }

    private static final DataNameBinder BINDER = name -> switch (name) {
        case "problem" -> TsonProblem.class;
        case "diagnostic" -> TsonProblemDiagnostic.class;
        case "diagnostic_code" -> Diagnostic.Code.class;
        default -> SchemaMetaNameBinder.INSTANCE.resolve(name);
    };

    private static final String SOURCE = readResource("/problem-2.tn");

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
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(BINDER).build());
        Tson tson = Tson.builder().schemaSource(uri -> SOURCE).dataBindContext(context).build();
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
