package io.ltr8.tson.http;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * This project's own {@code problem-1.tn} -- the schema every error body is written against -- as source text a
 * server can serve, and as a compiled schema bound to {@link TsonProblem}/{@link TsonProblemDiagnostic}.
 *
 * <p><b>{@link #source} exists because the {@code !!id} in an error body has to resolve.</b> A problem body
 * declares {@code !!schema:"https://tson.io/2026/32/ltr8/http/problem-1.tn"}, and a client that wants to validate
 * what it received needs that document. Serving it from this constant is what makes the URL in the body true
 * rather than decorative.
 *
 * <p>{@link #tson} builds a fresh {@link Tson} of its own each call, with this schema resolved and bound to the
 * two wire records, so registering it never collides with an application's. It is the read path, not the write
 * path: {@link TsonHttpCodec#writeProblem} emits a problem with a plain object writer, and reading that text back
 * through this is what demonstrates the emitted body is genuinely valid against a real schema rather than merely
 * shaped like it. A client that wants to validate the error bodies it receives uses the same thing.
 */
public final class TsonProblemSchema {

    /** {@code problem-1.tn}'s canonical identity -- the {@code !!id} it declares and the URL it should be served at. */
    public static final String ID = "https://tson.io/2026/32/ltr8/http/problem-1.tn";

    private static final DataNameBinder BINDER = name -> switch (name) {
        case "problem" -> TsonProblem.class;
        case "diagnostic" -> TsonProblemDiagnostic.class;
        case "diagnostic_code" -> Diagnostic.Code.class;
        default -> SchemaMetaNameBinder.INSTANCE.resolve(name);
    };

    private static final String SOURCE = readResource("/problem-1.tn");

    private TsonProblemSchema() {
    }

    /** {@code problem-1.tn}'s own source text. */
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
