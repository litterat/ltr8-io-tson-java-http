package io.ltr8.tson.http;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.json.tree.JsonValue;
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
 * <p><b>Reads collect, they do not fail fast.</b> Every read here runs with a {@link DiagnosticsCollector},
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
 * <p><b>A JSON body is opt-in, and read by the JSON reader.</b> {@link #acceptingJson} admits one and reads it
 * through [TSON-JSON]'s own reader, under the same policy, binding and registered schemas as a TSON body. See
 * that method for what it takes to read one.
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
    /** The JSON encoding's front door over the same configuration, or {@code null} where JSON is not admitted. */
    private final Json json;

    /** A codec over {@code tson}, whose schemas are expected to be already resolved. */
    public TsonHttpCodec(Tson tson) {
        this(tson, null);
    }

    private TsonHttpCodec(Tson tson, Json json) {
        this.json = json;
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
     * <p><b>A warm-up, not a correctness measure.</b> Descriptor resolution settles a concurrent first write by
     * keeping the winner's entry, so nothing here is load-bearing for correctness. What remains is the latency,
     * which is worth moving off the request thread.
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
     * Compiles {@code schemaIds} in bind mode now, rather than on the first request that reads one -- so a
     * schema and the class bound to it that disagree about a type's fields is a startup failure rather than a
     * request-time one.
     *
     * <p><b>This one is a correctness measure, unlike {@link #prepareToWrite}.</b> Both halves of the
     * agreement are fixed before any document exists, so the mistake is knowable at startup; found there it
     * is a wiring error fixed in minutes, and found at a read it is a 500 on a request that had nothing wrong
     * with it. Call it at startup for every schema this server reads against.
     *
     * @throws IllegalStateException wrapping the mismatch, naming the schema that could not be prepared
     */
    public void prepareToRead(String... schemaIds) {
        for (String schemaId : schemaIds) {
            try {
                tson.bindRegistry().get(schemaId);
            } catch (RuntimeException e) {
                throw new IllegalStateException("cannot prepare '" + schemaId + "' for reading: "
                        + e.getMessage(), e);
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
        requireTsonTree(contentType, "readJsonTree");
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        return require(read(() -> tson.treeReader().withDiagnostics(problems).read(body)), problems);
    }

    /**
     * Reads {@code body}'s header and stops, handing back the rest on the same stream -- for a caller that must
     * know what a document declares <b>before</b> choosing how to read it, on a body that cannot be read twice.
     *
     * <p>Nothing is buffered and re-fed: the header is the stream's first event, so a read handed the peek
     * carries on from just past it. That is what makes routing on {@code !!schema} cost a request body nothing.
     *
     * <p><b>Open it here rather than through {@code Tson.begin} directly.</b> A peek belongs to the processor
     * policy it was opened under, and a reader that disagrees is refused; taking it from the codec that will
     * read it is what keeps the two the same. Reading a peek through a <em>different</em> codec is therefore
     * only sound where both share a policy -- which is what {@link TsonSchemaVersions} relies on.
     */
    public TsonDocumentPeek begin(InputStream body) {
        return tson.begin(body);
    }

    /** {@link #readTree(InputStream, String)} continuing a peek this codec opened. */
    public TsonValue readTree(TsonDocumentPeek body, String contentType) {
        requirePeekable(contentType);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
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
        requireTsonTree(contentType, "readJsonTreeAs");
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        return require(read(() -> tson.treeReader().withSchema(schemaUri).withDiagnostics(problems)
                .readAs(body, typeName)), problems);
    }

    /**
     * {@link #readTreeAs(InputStream, String, String, String)} continuing a peek this codec opened -- the shape
     * a {@code TSON-Schema} header takes in tree mode, where the schema comes from the field and the root type
     * from the route.
     *
     * <p>There is deliberately no bind-mode counterpart: upstream's object reader has no
     * {@code readAs(peek, typeName, targetClass)}, so a body whose schema arrives only in the header must be
     * read in tree mode or from the start. Tracked in {@code UPSTREAM.md}.
     */
    public TsonValue readTreeAs(TsonDocumentPeek body, String contentType, String schemaUri, String typeName) {
        requirePeekable(contentType);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        return require(read(() -> tson.treeReader().withSchema(schemaUri).withDiagnostics(problems)
                .readAs(body, typeName)), problems);
    }

    /**
     * Reads a JSON request body into a {@link JsonValue} tree -- the JSON encoding's tree mode, schemaless.
     * A {@code TsonValue} is the TSON reader's tree, so a JSON body is never read into one.
     *
     * @throws TsonHttpException 415 if the body is not JSON or this codec does not admit JSON, 400 if it is
     *                           JSON but not within [TSON-JSON] §3.1's profile
     */
    public JsonValue readJsonTree(InputStream body, String contentType) {
        requireJsonBody(contentType);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        return require(read(() -> json.treeReader().withDiagnostics(problems).read(body)), problems);
    }

    /**
     * {@link #readJsonTree} against a stated schema and root type -- [TSON-JSON] §3.4's out-of-band binding, the
     * shape a {@code TSON-Schema} header takes. Validated in full; the tree is the JSON as it arrived.
     *
     * @throws TsonHttpException 415 if the body is not JSON or this codec does not admit JSON, 400 if it is
     *                           JSON but invalid
     */
    public JsonValue readJsonTreeAs(InputStream body, String contentType, String schemaUri, String typeName) {
        requireJsonBody(contentType);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        return require(read(() -> json.treeReader().withSchema(schemaUri).withDiagnostics(problems)
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
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        if (isJsonBody(contentType)) {
            return require(read(() -> json.objectReader().withDiagnostics(problems).read(body, targetClass)),
                    problems);
        }
        return require(read(() -> tson.objectReader().withDiagnostics(problems).read(body, targetClass)), problems);
    }

    /** {@link #readObject(InputStream, String, Class)} continuing a peek this codec opened. */
    public <T> T readObject(TsonDocumentPeek body, String contentType, Class<T> targetClass) {
        requirePeekable(contentType);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
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
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        if (isJsonBody(contentType)) {
            return require(read(() -> json.objectReader().withSchema(schemaUri).withDiagnostics(problems)
                    .readAs(body, typeName, targetClass)), problems);
        }
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

    /**
     * A codec that also admits a JSON body -- {@code application/tson+json}, [TSON-JSON]'s own media type, and
     * {@code application/json} or any other {@code +json} type -- <b>read by the JSON reader</b>, tson-java's
     * {@code tson-json}. Opt-in, because "reads TSON" and "reads JSON" are different promises: an endpoint that
     * wants only TSON goes on answering 415, and does by default.
     *
     * <p><b>One configuration, both encodings.</b> The JSON reader is built from this codec's own {@code Tson}:
     * the same processor policy (§9.1's limits and §8.2's name hygiene), the same {@code DataBindContext}, and the
     * same registered schemas, so a class binds identically under both and a document is judged by one policy
     * whichever encoding carried it. Nothing is fetched: a schema is named from what the {@code Tson} already
     * holds, so the startup rule stands. Call this at startup and keep the codec -- it holds the JSON reader's
     * compiled schemas, and a codec derived per request would compile them per request.
     *
     * <p><b>A JSON body names neither its schema nor its root type</b> -- not in a way this reader can use yet.
     * [TSON-JSON] §3.4 has two routes, and tson-java builds only the out-of-band one: the schema comes from the
     * {@code TSON-Schema} header ({@link TsonSchemaHeader}) and the root type from the route, so reading one
     * against a schema is {@link #readObjectAs} or {@link #readJsonTreeAs}. The in-band route, a root annotation
     * object carrying {@code $schema} and {@code $type}, is refused by that reader today.
     *
     * <p><b>Bind mode serves both encodings; tree mode is one per encoding.</b> {@link #readObject} and
     * {@link #readObjectAs} read whichever the {@code Content-Type} names. A tree is the encoding's own model --
     * {@code TsonValue} for TSON, {@link JsonValue} for JSON -- so a JSON body is read as a tree with {@link
     * #readJsonTree}/{@link #readJsonTreeAs}, and handing one to a {@code TsonValue} read is a fault in the
     * route, not the request.
     *
     * <p><b>A JSON body is never peeked.</b> A {@link TsonDocumentPeek} is the TSON reader's continuation of a
     * header, and a JSON document has none; a JSON body reaching a peek-taking read is refused the same way.
     */
    public TsonHttpCodec acceptingJson() {
        return new TsonHttpCodec(tson, Json.of(ProcessorConfig.defaults()
                        .withProcessorPolicy(tson.processorPolicy())
                        .withDataBindContext(tson.dataBindContext()))
                .withSchemas(tson.schemaRegistry()));
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
     * Checks a request's {@code Content-Type} names a body this codec can read: TSON, or JSON where it was built
     * {@link #acceptingJson}.
     *
     * <p><b>An absent {@code Content-Type} is accepted, as TSON.</b> RFC 9110 §8.3 lets a recipient assume a media
     * type or examine the content when none is given, and §7.1 makes a TSON document classifiable from its own
     * opening bytes -- so the parse itself is the check, and rejecting the request unread would be stricter than
     * the format requires. What is rejected is a header that positively claims something else.
     *
     * @throws TsonHttpException 415 if it does not
     */
    public void requireTsonBody(String contentType) {
        isJsonBody(contentType);
    }

    /**
     * Whether {@code contentType} names a JSON body this codec reads, after refusing one it reads neither way --
     * the one place the media-type gate is decided.
     *
     * @throws TsonHttpException 415 for a body that is neither TSON nor admitted JSON, or that claims a charset
     *                           other than UTF-8
     */
    private boolean isJsonBody(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        TsonMediaType mediaType;
        try {
            mediaType = TsonMediaType.parse(contentType);
        } catch (IllegalArgumentException malformed) {
            throw TsonHttpException.unsupportedMediaType("Content-Type '" + contentType + "' is not a media type: "
                    + malformed.getMessage());
        }
        boolean isJson = isJson(mediaType);
        if (!mediaType.isTson() && !(json != null && isJson)) {
            throw TsonHttpException.unsupportedMediaType("this endpoint reads " + TsonMediaType.APPLICATION_TSON
                    + (json != null ? " and JSON" : "") + ", not " + mediaType);
        }
        // [TSON-DATA] §7.1 and [TSON-JSON] §3.1 alike: a document in either encoding is UTF-8.
        if (mediaType.hasUnsupportedCharset()) {
            throw TsonHttpException.unsupportedMediaType("a TSON document is UTF-8 ([TSON-DATA] §7.1, [TSON-JSON] "
                    + "§3.1); '" + contentType + "' claims " + mediaType.charset().orElseThrow());
        }
        return isJson;
    }

    /** Admits a JSON body only, for the JSON tree reads. */
    private void requireJsonBody(String contentType) {
        if (json == null) {
            throw new IllegalStateException("a JSON read on a codec that does not admit JSON -- build the route's "
                    + "codec with acceptingJson()");
        }
        if (!isJsonBody(contentType)) {
            throw TsonHttpException.unsupportedMediaType("this route reads its body as JSON, not "
                    + (contentType == null || contentType.isBlank() ? "an unlabelled body" : contentType));
        }
    }

    /** Admits a TSON body only, for the reads producing a {@code TsonValue}; see {@link #acceptingJson}. */
    private void requireTsonTree(String contentType, String jsonRead) {
        if (isJsonBody(contentType)) {
            throw new IllegalStateException("a JSON body cannot be read into a TsonValue, which is the TSON "
                    + "reader's tree -- this route should read it with " + jsonRead);
        }
    }

    /** Admits a TSON body only, for the reads continuing a peek; see {@link #acceptingJson}. */
    private void requirePeekable(String contentType) {
        if (isJsonBody(contentType)) {
            throw new IllegalStateException("a JSON body was peeked, and a peek is the TSON reader's -- this route "
                    + "should read a JSON body from its stream");
        }
    }

    /**
     * Whether {@code mediaType} is JSON: {@code application/tson+json}, [TSON-JSON]'s own, or {@code
     * application/json} or any other {@code +json} type, all read by the JSON reader alike.
     */
    private static boolean isJson(TsonMediaType mediaType) {
        return "application".equals(mediaType.type())
                && ("json".equals(mediaType.subtype()) || mediaType.subtype().endsWith("+json"));
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
    private static <T> T require(T value, DiagnosticsCollector problems) {
        List<Diagnostic> diagnostics = problems.diagnostics();
        if (!diagnostics.isEmpty()) {
            throw TsonHttpException.invalidDocument(diagnostics);
        }
        return value;
    }

    /**
     * A write collected into bytes. The writer encodes UTF-8 itself and emits no BOM -- [TSON-DATA] §7.1 fixes
     * the encoding, says an encoder SHOULD NOT emit one, and makes U+FEFF a lexer error anywhere but the very
     * first character, it being a format character no profile admits.
     */
    private static byte[] buffered(Consumer<OutputStream> write) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        write.accept(bytes);
        return bytes.toByteArray();
    }
}
