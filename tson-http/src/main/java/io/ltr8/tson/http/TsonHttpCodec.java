package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonValue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads {@code application/tson} request bodies and writes {@code application/tson} response bodies. The one
 * piece every adapter shares: a framework adapter translates its own request and response objects into calls on
 * this, and holds no TSON knowledge of its own.
 *
 * <p><b>Reads collect, they do not fail fast.</b> Every read here runs with a {@link TsonDiagnosticsCollector},
 * so one 400 reports everything wrong with a body rather than only the first problem. The target consumer is a
 * generate-validate-retry loop, and a client told about one error per round trip needs one round trip per error.
 * The cost is that the reader keeps a {@code null} placeholder for each failed field and runs to the end -- which
 * is why a failed read throws rather than returning the partial value.
 *
 * <p><b>Thread-safety follows the {@link Tson} it is given.</b> A codec holds no per-request state, so it is safe
 * to share across request threads exactly as far as its {@code Tson} is: build the instance and resolve every
 * schema during single-threaded startup, then share it for reads. Never resolve a schema from a request handler.
 * See {@code CLAUDE.md}.
 *
 * <p><b>An error body says what governs it.</b> {@link #writeProblem} writes through a {@code describing}
 * writer, so a problem carries {@code !!schema:"…/problem-1.tn"} and reads back with no out-of-band knowledge --
 * and this project's own schema handler publishes that document, so the URL in it resolves. Every other write
 * is bare unless a caller asks otherwise, because the codec cannot know what governs an arbitrary object; the
 * overloads taking a schema and root type are how an application says.
 *
 * <p><b>An ordinary response streams; an error body does not.</b> {@link #writeTo} and {@link #writeTreeTo} hand
 * the response stream straight to the writer, so a large or open-ended document never exists as a {@code String}
 * -- the write-side counterpart to reading from an {@code InputStream}. The buffering {@link #write}/
 * {@link #writeTree} remain for a caller that wants the bytes in hand, typically to set {@code Content-Length}.
 *
 * <p>{@link #writeProblem} is deliberately only buffered. Streaming an error body means a failure part-way
 * through leaves a client holding a truncated problem on a response whose status is already sent, which is worse
 * than the failure being reported. A problem is small, so there is nothing to gain by streaming it.
 */
public final class TsonHttpCodec {

    private final Tson tson;
    private final TsonObjectWriter objectWriter;
    private final TsonTreeWriter treeWriter;
    private final TsonObjectWriter problemWriter;

    /** A codec over {@code tson}, whose schemas are expected to be already resolved. */
    public TsonHttpCodec(Tson tson) {
        this.tson = tson;
        this.objectWriter = tson.objectWriter();
        this.treeWriter = tson.treeWriter();
        // Built once: an error body always names problem-1.tn and its root type, so there is nothing per-call
        // to decide. Both arguments are required -- a bound record writes no type-ref of its own, so a
        // !!schema without one produces a document a reader cannot select a type from.
        this.problemWriter = objectWriter.describing(TsonProblemSchema.ID, "problem");
        // Warm-up, not a correctness measure. writeProblem runs when something has already gone wrong, often
        // for many requests at once, and resolving a descriptor for the first time on that path adds latency
        // exactly where it is least wanted.
        prepareToWrite(TsonProblem.class, TsonProblemDiagnostic.class);
    }

    /**
     * Resolves the binding descriptors for {@code classes} now, rather than on the first request that writes
     * one. Idempotent and cheap; call it at startup for every type this server writes.
     *
     * <p><b>A warm-up, not a correctness measure.</b> It began as one: descriptor resolution used to race on a
     * concurrent first write and the loser got {@code Class already registered} ({@code UPSTREAM.md} #8, now
     * fixed upstream -- a lost race takes the winner's entry). What remains is the latency, which is worth
     * moving off the request thread.
     */
    public void prepareToWrite(Class<?>... classes) {
        for (Class<?> target : classes) {
            try {
                tson.dataBindContext().getDescriptor(target);
            } catch (Exception e) {
                throw new IllegalStateException("cannot prepare " + target.getName() + " for writing", e);
            }
        }
    }

    /**
     * Reads a request body into a queryable tree, validating it against whatever schema applies -- the
     * {@code !!schema} the document names, or none, in which case it is checked against base syntax and the
     * built-in type vocabulary alone.
     *
     * @param body        the request body; read incrementally and not closed here, matching {@code Tson.validate}
     * @param contentType the request's {@code Content-Type} header, or {@code null} if it sent none
     * @throws TsonHttpException 415 if the body is not TSON, 400 if it is TSON but invalid
     */
    public TsonValue readTree(InputStream body, String contentType) {
        requireTsonBody(contentType);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        return require(read(() -> tson.treeReader().withDiagnostics(problems).read(body)), problems);
    }

    /**
     * {@link #readTree} against a stated schema and root type, for a body that names neither -- the shape a
     * handler uses when the route, not the document, decides what is being posted.
     *
     * <p>Both the schema and the type are required, and the schema must already be registered: selecting a root
     * type is meaningless without one to select it from, and resolving a schema here would be resolving from a
     * request thread. Passing an unregistered {@code schemaUri} is a server configuration error, not a client
     * error, and surfaces as a 500.
     *
     * @throws TsonHttpException 415 if the body is not TSON, 400 if it is TSON but invalid
     */
    public TsonValue readTreeAs(InputStream body, String contentType, String schemaUri, String typeName) {
        requireTsonBody(contentType);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        return require(read(() -> tson.treeReader().withSchema(schemaUri).withDiagnostics(problems)
                .readAs(body, typeName)), problems);
    }

    /**
     * Reads a request body into an instance of {@code targetClass}, bound through this codec's own
     * {@code DataBindContext}.
     *
     * <p><b>{@code targetClass} is the expected result, not the mapping.</b> Binding resolves a schema type
     * name to a class through the {@code DataNameBinder} on the {@code Tson}'s own {@code DataBindContext}, so
     * an application using these methods configures one at startup; without it the schema compiles and then has
     * no reader for its own types. A bound class must also be public -- tson-java declares no {@code opens} and
     * binding only ever touches public constructors and methods.
     *
     * @throws TsonHttpException 415 if the body is not TSON, 400 if it is TSON but invalid
     */
    public <T> T readObject(InputStream body, String contentType, Class<T> targetClass) {
        requireTsonBody(contentType);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        return require(read(() -> tson.objectReader().withDiagnostics(problems).read(body, targetClass)), problems);
    }

    /**
     * {@link #readObject} against a stated schema and root type, for a body that names neither. Same
     * requirements as {@link #readTreeAs}.
     *
     * @throws TsonHttpException 415 if the body is not TSON, 400 if it is TSON but invalid
     */
    public <T> T readObjectAs(InputStream body, String contentType, String schemaUri, String typeName,
                              Class<T> targetClass) {
        requireTsonBody(contentType);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        return require(read(() -> tson.objectReader().withSchema(schemaUri).withDiagnostics(problems)
                .readAs(body, typeName, targetClass)), problems);
    }

    /**
     * Writes a bound object into the response stream as it goes, so the document never exists as a
     * {@code String}. The stream is flushed and not closed -- it belongs to the adapter.
     *
     * <p>A response written this way carries no {@code Content-Length}, since the length is not known until the
     * document is finished; the framework sends it chunked. Use {@link #write(Object)} where that matters.
     */
    public void writeTo(Object value, OutputStream out) {
        objectWriter.write(value, out);
    }

    /** {@link #writeTo} for a tree. */
    public void writeTreeTo(TsonValue value, OutputStream out) {
        treeWriter.write(value, out);
    }

    /** A bound object as a response body, in hand -- for a caller that wants to set {@code Content-Length}. */
    public byte[] write(Object value) {
        return buffered(out -> objectWriter.write(value, out));
    }

    /**
     * A bound object as a <b>self-describing</b> response body: the document names {@code schemaUri} in a
     * {@code !!schema} directive and {@code rootTypeName} as its root type-ref, so a client reads it back
     * without being told either out of band.
     *
     * <p>Both are required and neither is guessed. A bound record writes no type-ref of its own, so a
     * {@code !!schema} alone yields a document whose reader cannot select a type -- half self-describing is not
     * self-describing.
     */
    public byte[] write(Object value, String schemaUri, String rootTypeName) {
        TsonObjectWriter describing = objectWriter.describing(schemaUri, rootTypeName);
        return buffered(out -> describing.write(value, out));
    }

    /** {@link #write(Object, String, String)}, streamed. */
    public void writeTo(Object value, String schemaUri, String rootTypeName, OutputStream out) {
        objectWriter.describing(schemaUri, rootTypeName).write(value, out);
    }

    /** A tree as a response body, in hand. */
    public byte[] writeTree(TsonValue value) {
        return buffered(out -> treeWriter.write(value, out));
    }

    /**
     * A tree as a <b>self-describing</b> response body. One argument where {@link #write(Object, String,
     * String)} takes two, because a tree node carries its own type-ref already.
     */
    public byte[] writeTree(TsonValue value, String schemaUri) {
        TsonTreeWriter describing = treeWriter.describing(schemaUri);
        return buffered(out -> describing.write(value, out));
    }

    /** {@link #writeTree(TsonValue, String)}, streamed. */
    public void writeTreeTo(TsonValue value, String schemaUri, OutputStream out) {
        treeWriter.describing(schemaUri).write(value, out);
    }

    /**
     * An error body. Kept separate from {@link #write} because it must not fail the way an ordinary write can:
     * a failure here happens while already handling a failure, and losing the original problem to a second one
     * leaves a client with nothing to act on. A problem that cannot be rendered as TSON is a fault in this
     * library or in {@code problem-1.tn}, so it surfaces as one rather than as an empty 500.
     */
    public byte[] writeProblem(TsonProblem problem) {
        try {
            return buffered(out -> problemWriter.write(problem, out));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to render this server's own problem body as TSON", e);
        }
    }

    /** The {@code Content-Type} every response body from this codec carries. */
    public TsonMediaType contentType() {
        return TsonMediaType.APPLICATION_TSON;
    }

    /**
     * Checks that a client will take a TSON response before a handler does the work of producing one.
     *
     * @param accept the request's {@code Accept} header, or {@code null} if it sent none -- which means "anything"
     * @throws TsonHttpException 406 if it will not
     */
    public void requireTsonAcceptable(String accept) {
        if (!TsonAcceptHeader.parse(accept).acceptsTson()) {
            throw TsonHttpException.notAcceptable("this endpoint produces " + TsonMediaType.APPLICATION_TSON
                    + ", which '" + accept + "' does not accept");
        }
    }

    /**
     * Checks a request's {@code Content-Type} names a body this codec can read.
     *
     * <p><b>An absent {@code Content-Type} is accepted.</b> RFC 9110 §8.3 lets a recipient assume a media type or
     * examine the content when none is given, and §7.1 makes a TSON document classifiable from its own opening
     * bytes -- so the parse itself is the check, and rejecting the request unread would be stricter than the
     * format requires. What is rejected is a header that positively claims something else.
     *
     * @throws TsonHttpException 415 if it does not
     */
    public void requireTsonBody(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return;
        }
        TsonMediaType mediaType;
        try {
            mediaType = TsonMediaType.parse(contentType);
        } catch (IllegalArgumentException malformed) {
            throw TsonHttpException.unsupportedMediaType("Content-Type '" + contentType + "' is not a media type: "
                    + malformed.getMessage());
        }
        if (!mediaType.isTson()) {
            throw TsonHttpException.unsupportedMediaType("this endpoint reads " + TsonMediaType.APPLICATION_TSON
                    + ", not " + mediaType);
        }
        if (mediaType.hasUnsupportedCharset()) {
            throw TsonHttpException.unsupportedMediaType("a TSON document is UTF-8 ([TSON-DATA] §7.1); '"
                    + contentType + "' claims " + mediaType.charset().orElseThrow());
        }
    }

    /** Runs a read, classifying anything the library throws out of it into a status. */
    private static <T> T read(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            throw TsonHttpException.from(e);
        }
    }

    /**
     * The read's value, or a 400 carrying every problem. A collecting read returns a value with a {@code null}
     * placeholder wherever a field failed, so a value alongside a non-empty diagnostic list is not a usable
     * result -- it is a rejected request that happened to run to the end.
     */
    private static <T> T require(T value, TsonDiagnosticsCollector problems) {
        List<Diagnostic> diagnostics = problems.diagnostics();
        if (!diagnostics.isEmpty()) {
            throw TsonHttpException.invalidDocument(diagnostics);
        }
        return value;
    }

    /**
     * A write collected into bytes. The writer encodes UTF-8 itself and emits no BOM -- [TSON-DATA] §7.1 fixes
     * the encoding and §9.4 treats a BOM as a confusable hazard rather than a signal.
     */
    private static byte[] buffered(Consumer<OutputStream> write) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        write.accept(bytes);
        return bytes.toByteArray();
    }
}
