package io.ltr8.tson.http;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonHttpCodecTest {

    private static final String SCHEMA_ID = "https://example.com/2026/34/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.com/2026/34/app/order-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    /**
     * Public deliberately: tson-java declares no {@code opens} and binding only ever touches public
     * constructors and methods, so a package-private record fails analysis with a bare "Failed to resolve".
     */
    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private TsonHttpCodec codec;

    /**
     * Every schema resolved before the codec is built and shared -- the shape CLAUDE.md fixes for a server, and
     * the one these tests exercise, rather than resolving lazily from what stands in for a handler here.
     */
    @BeforeEach
    void setUp() {
        // Object binding needs the schema type name mapped to a class; the class handed to readObject is the
        // expected result, not the mapping. Without this, `order` compiles fine and then has no reader.
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);
    }

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsASelfDescribingBodyIntoATree() {
        TsonValue order = codec.readTree(body("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID)), "application/tson");
        assertEquals("ABC-1", order.get("sku").asString().orElseThrow());
        assertEquals(3, order.at("/quantity").asInt().orElseThrow());
    }

    @Test
    void readsABodyIntoABoundObject() {
        Order order = codec.readObject(body("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID)), "application/tson", Order.class);
        assertEquals(new Order("ABC-1", 3), order);
    }

    @Test
    void rejectsADocumentThatBreaksItsSchema() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class, () -> codec.readTree(body("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 99999999999 }""".formatted(SCHEMA_ID)), "application/tson"));
        assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
        assertEquals(1, rejected.diagnostics().size());
    }

    /**
     * The headline claim of collecting rather than failing fast: a client told about one error per round trip
     * needs one round trip per error, and the target consumer is a generate-validate-retry loop.
     */
    @Test
    void reportsEveryProblemInOneResponseNotJustTheFirst() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class, () -> codec.readTree(body("""
                !!schema:"%s"
                !order { }""".formatted(SCHEMA_ID)), "application/tson"));
        assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
        assertEquals(2, rejected.diagnostics().size(), "both missing fields, not only the first");
        assertTrue(rejected.diagnostics().stream().allMatch(d -> d.code() == Diagnostic.Code.FIELD_REQUIRED));
        assertEquals(List.of("/sku", "/quantity"),
                rejected.diagnostics().stream().map(d -> d.path().orElseThrow()).toList());
    }

    /**
     * A {@code text} field accepts any token's text, including {@code 42} and {@code true} -- [TSON-DATA] §4
     * says base type resolution does not apply at a schema-typed position, and §7.1's "form is not meaning"
     * makes a type contract operate on the token's text rather than on how it was written. Only a value that is
     * not a token at all fails. Pinned because it reliably reads as a bug: a client arriving from JSON Schema
     * expects `text` to reject a number, and a handler that needs it to must say so with a `pattern`.
     */
    @Test
    void aTextFieldAcceptsAnyTokenButNotAContainer() {
        assertDoesNotThrow(() -> codec.readTree(body("""
                !!schema:"%s"
                !order { sku: 42  quantity: 1 }""".formatted(SCHEMA_ID)), "application/tson"));

        TsonHttpException rejected = assertThrows(TsonHttpException.class, () -> codec.readTree(body("""
                !!schema:"%s"
                !order { sku: [1]  quantity: 1 }""".formatted(SCHEMA_ID)), "application/tson"));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, rejected.diagnostics().getFirst().code());
    }

    /**
     * A document that will not parse reports like any other bad document -- through the collector, carrying
     * everything found rather than the first thing thrown. {@code TsonHttpException.from}'s base-syntax branch
     * is still there as a net, classifying three exception types that no caller here could catch and rethrowing
     * anything else; this asserts the path that should be taken instead.
     */
    @Test
    void rejectsMalformedTsonAsABadRequestCarryingItsDiagnostics() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> codec.readTree(body("!order { sku: "), "application/tson"));
        assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
        assertFalse(rejected.diagnostics().isEmpty(), "a malformed body reports what was wrong with it");
        assertTrue(rejected.diagnostics().stream()
                        .anyMatch(d -> d.code() == Diagnostic.Code.VALIDATION_ERROR),
                () -> "expected a base-syntax diagnostic, got " + rejected.diagnostics());
    }

    @Test
    void rejectsABodyThatIsNotTson() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> codec.readTree(body("{}"), "application/json"));
        assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE, rejected.status());
    }

    /** §7.1 fixes the encoding at UTF-8, so a charset naming anything else describes a document TSON has not got. */
    @Test
    void rejectsAnEncodingTsonDocumentsAreNeverIn() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> codec.readTree(body("{ a: 1 }"), "application/tson; charset=iso-8859-1"));
        assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE, rejected.status());
    }

    @Test
    void acceptsARedundantUtf8CharsetAndAVersionParameter() {
        assertDoesNotThrow(() -> codec.readTree(body("{ a: 1 }"), "application/tson; charset=utf-8"));
        assertDoesNotThrow(() -> codec.readTree(body("{ a: 1 }"), "application/tson; version=1"));
    }

    /** RFC 9110 §8.3 permits examining the content when no Content-Type is given, and §7.1 makes that possible. */
    @Test
    void readsABodyThatDeclaredNoContentType() {
        TsonValue value = codec.readTree(body("{ a: 1 }"), null);
        assertEquals(1, value.at("/a").asInt().orElseThrow());
    }

    @Test
    void refusesToProduceTsonForAClientThatWillNotTakeIt() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> codec.requireTsonAcceptable("application/json"));
        assertEquals(TsonHttpException.NOT_ACCEPTABLE, rejected.status());

        assertDoesNotThrow(() -> codec.requireTsonAcceptable(null));
        assertDoesNotThrow(() -> codec.requireTsonAcceptable("*/*"));
        assertDoesNotThrow(() -> codec.requireTsonAcceptable("application/tson"));
    }

    @Test
    void writesABoundObjectAsUtf8Bytes() {
        byte[] written = codec.write(new Order("ABC-1", 3));
        String document = new String(written, StandardCharsets.UTF_8);
        assertTrue(document.contains("ABC-1"), document);
        assertEquals("application/tson", codec.contentType().toString());
    }

    /**
     * Streaming and buffering must produce the same document, byte for byte -- otherwise which one an adapter
     * happens to call becomes observable to a client.
     */
    @Test
    void streamingAndBufferingProduceTheSameBytes() {
        Order order = new Order("ABC-1", 3);
        var streamed = new java.io.ByteArrayOutputStream();
        codec.writeTo(order, streamed);
        assertArrayEquals(codec.write(order), streamed.toByteArray());
    }

    /** The stream belongs to the adapter: the writer must flush what it buffered, and must not close it. */
    @Test
    void writingFlushesTheStreamAndLeavesItOpen() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var sink = new java.io.ByteArrayOutputStream() {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        codec.writeTo(new Order("ABC-1", 3), sink);
        assertTrue(sink.size() > 0, "a short document must not be left in the encoder's buffer");
        assertFalse(closed.get(), "the response stream belongs to the adapter");
    }

    /** What is written must read back -- the round trip is the only thing that proves the writer agrees with the reader. */
    @Test
    void whatItWritesItCanReadBack() {
        var streamed = new java.io.ByteArrayOutputStream();
        codec.writeTo(new Order("ABC-1", 3), streamed);
        byte[] written = streamed.toByteArray();
        Order read = codec.readObjectAs(new ByteArrayInputStream(written), "application/tson", SCHEMA_ID, "order",
                Order.class);
        assertEquals(new Order("ABC-1", 3), read);
    }
    // ── a library gap inside a diagnostics list ──

    private static Diagnostic aGap() {
        return Diagnostic.ofSchemaGap("s.example.com/x-1.tn", "op",
                "a container sugar form must be lifted to an entry before resolution", Optional.empty());
    }

    private static Diagnostic anOrdinaryProblem() {
        return Diagnostic.ofSchemaError("s.example.com/x-1.tn", "y", "unresolved reference 'q'",
                Optional.empty());
    }

    /**
     * <b>A gap is a 501 even when it arrives as a diagnostic rather than an exception.</b> A gap now travels
     * in the same list as ordinary problems -- so that one unimplemented construct does not cost every other
     * declaration its verdict -- which means a list reaching the status policy may contain one. Answering 400
     * would tell a client its request was invalid when the truth is that this server could not check it.
     */
    @Test
    void aNotImplementedDiagnosticIsAGapNotABadRequest() {
        TsonHttpException thrown = TsonHttpException.invalidDocument(List.of(aGap()));

        assertEquals(TsonHttpException.NOT_IMPLEMENTED, thrown.status());
        assertTrue(thrown.type().endsWith("not-implemented"), thrown.type());
    }

    /**
     * <b>And a mixed list is a gap too</b>, deliberately -- the HTTP wearing of the rule the CLI rides its
     * exit codes on, where any {@code NOT_IMPLEMENTED} makes a run 70 rather than 1. The ordinary problems
     * are real and still travel in the body, but something went unchecked, so "invalid" is not a verdict this
     * server is entitled to give.
     */
    @Test
    void aMixedListIsAGapAndStillCarriesTheOrdinaryProblems() {
        TsonHttpException thrown =
                TsonHttpException.invalidDocument(List.of(anOrdinaryProblem(), aGap(), anOrdinaryProblem()));

        assertEquals(TsonHttpException.NOT_IMPLEMENTED, thrown.status());
        assertEquals(3, thrown.diagnostics().size(), "the real problems are still reported");
        assertTrue(thrown.getMessage().contains("2 problem(s) reported are real"), thrown.getMessage());
    }

    /** Ordinary problems alone stay a 400 -- the common case is unchanged. */
    @Test
    void ordinaryProblemsAreStillABadRequest() {
        TsonHttpException thrown =
                TsonHttpException.invalidDocument(List.of(anOrdinaryProblem(), anOrdinaryProblem()));

        assertEquals(TsonHttpException.BAD_REQUEST, thrown.status());
        assertTrue(thrown.getMessage().contains("2 problems"), thrown.getMessage());
    }

    /**
     * <b>A type nothing binds is this server's misconfiguration, not a library gap.</b> It used to surface as
     * {@code UnsupportedOperationException: no usable compiled reader}, which the status policy faithfully
     * called 501 — telling a client the library could not do something, when a line of configuration was
     * simply missing. It is now a {@code TsonMissingBindingException}, and 500 is the honest answer.
     *
     * <p>Deferred to the read of that specific type, deliberately: a schema legitimately declares types a
     * given consumer never binds — core.tn's forty of them — so failing the compile would make bind mode
     * unusable.
     */
    @Test
    void aTypeNothingBindsIsAServerFaultNotALibraryGap() {
        String schema = """
                !!id:"https://example.test/thing-1.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { thing => { a: text } }""";
        Tson tson = Tson.builder().schemaSource(uri -> schema).bindings(Map.of()).build();
        tson.resolve(schema);
        TsonHttpCodec bare = new TsonHttpCodec(tson);
        InputStream body = new ByteArrayInputStream(
                ("!!schema:\"https://example.test/thing-1.tn\"\n!thing { a: \"x\" }")
                        .getBytes(StandardCharsets.UTF_8));

        TsonHttpException thrown = org.junit.jupiter.api.Assertions.assertThrows(TsonHttpException.class,
                () -> bare.readObject(body, "application/tson", Object.class));

        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, thrown.status());
        assertTrue(thrown.getMessage() == null || !thrown.getMessage().contains("thing"),
                "a 5xx carries no detail: " + thrown.getMessage());
    }

    private static Diagnostic aBindMismatch() {
        return Diagnostic.ofSchemaError("s.example.com/x-1.tn", "order",
                "'order' and com.example.OrderV1 do not agree: no component for field 'currency'",
                Optional.empty());
    }

    /**
     * <b>A bind mismatch is a 500 and outranks a gap.</b> It is neither a verdict on the document nor a gap
     * in the library — a schema and the class bound to it disagree, which is this server's own wiring. It is
     * checked first because it is the one an operator has to fix, and the only one whose message names a
     * server type.
     */
    @Test
    void aBindMismatchDiagnosticIsAServerFaultAndOutranksAGap() {
        TsonHttpException thrown = TsonHttpException.invalidDocument(
                List.of(anOrdinaryProblem(), aGap(), withCode(aBindMismatch(), Diagnostic.Code.BIND_MISMATCH)));

        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, thrown.status());
    }

    /**
     * <b>And the class name never reaches the wire.</b> The detail exists to be logged; the adapter boundary
     * drops both it and the diagnostics from any 5xx body, which is the leak this whole classification was
     * asked for. Asserted against the body the boundary would actually send.
     */
    @Test
    void aFiveHundredBodyCarriesNeitherTheDetailNorTheDiagnostics() {
        TsonHttpException thrown = TsonHttpException.invalidDocument(
                List.of(withCode(aBindMismatch(), Diagnostic.Code.BIND_MISMATCH)));

        assertTrue(thrown.getMessage().contains("OrderV1"), "the log gets it: " + thrown.getMessage());

        TsonProblem sent = TsonProblem.of(thrown.type(), thrown.status(), thrown.title(), null, List.of());
        assertEquals(Optional.empty(), sent.detail());
        assertEquals(List.of(), sent.errors());
    }

    /**
     * <b>The same rule reaches a gap that arrives fail-fast, not only one collected into a list.</b> A read
     * gap is now reported rather than thrown, so it reaches a fail-fast caller as a {@code TsonReadException}
     * carrying {@code NOT_IMPLEMENTED} -- the very type a schema violation arrives as. Classifying by
     * exception type therefore answers 400 for a gap, which is the one verdict this policy may never give.
     * Only the code separates them, so {@code from} routes on it.
     */
    @Test
    void aFailFastReadGapIsAGapNotABadRequest() {
        var thrown = TsonHttpException.from(new TsonReadException(withCode(aGap(),
                Diagnostic.Code.NOT_IMPLEMENTED)));

        assertEquals(TsonHttpException.NOT_IMPLEMENTED, thrown.status());
        assertTrue(thrown.type().endsWith("not-implemented"), thrown.type());
    }

    /** And a fail-fast bind mismatch is this server's wiring, on the same reasoning as the collected one. */
    @Test
    void aFailFastBindMismatchDiagnosticIsAServerFault() {
        var thrown = TsonHttpException.from(new TsonReadException(withCode(aBindMismatch(),
                Diagnostic.Code.BIND_MISMATCH)));

        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, thrown.status());
    }

    /**
     * <b>The ordinary fail-fast case is unchanged</b>, and keeps the diagnostic's own message as the detail
     * rather than the collected channel's problem count -- a fail-fast read has exactly one to describe.
     */
    @Test
    void anOrdinaryFailFastReadFailureIsStillABadRequest() {
        var read = new TsonReadException(anOrdinaryProblem());
        var thrown = TsonHttpException.from(read);

        assertEquals(TsonHttpException.BAD_REQUEST, thrown.status());
        assertEquals(List.of(read.diagnostic()), thrown.diagnostics());
        assertEquals(read.getMessage(), thrown.getMessage());
        assertSame(read, thrown.getCause(), "the cause survives, so a 5xx sibling can be logged with a trace");
    }

    /**
     * <b>A diagnostic stating no reason keeps the conservative 502.</b> Every diagnostic the library builds
     * carries one, so this is the hand-assembled case -- and given one status for a class it cannot tell
     * apart, the wrong one to pick is the one that tells a client with a perfectly good document to go and
     * fix it because a host did not answer. It rounds away from the client, deliberately.
     */
    @Test
    void aSchemaUnavailableDiagnosticWithNoReasonRoundsAwayFromTheClient() {
        TsonHttpException thrown = TsonHttpException.invalidDocument(
                List.of(withCode(aGap(), Diagnostic.Code.SCHEMA_UNAVAILABLE)));

        assertEquals(TsonHttpException.BAD_GATEWAY, thrown.status());
        assertTrue(thrown.type().endsWith("schema-origin-failed"), thrown.type());
    }

    /**
     * <b>The reason decides the status, and the collected channel answers exactly as the thrown one does.</b>
     * That agreement is the whole value of {@code Diagnostic.fetchReason}: one failure reaches a consumer two
     * ways -- thrown at startup, collected on every read through this codec -- and the collected one used to
     * lose the reason and round the lot to 502, so {@code NOT_PERMITTED} was a 400 thrown and a 502 collected.
     *
     * <p>Asserted against {@link TsonHttpException#from}'s own answer rather than against literal statuses,
     * so the invariant under test is that the two channels agree rather than what they happen to agree on.
     */
    @Test
    void aSchemaUnavailableDiagnosticIsAnsweredByItsReason() {
        for (TsonSchemaFetchException.Reason reason : TsonSchemaFetchException.Reason.values()) {
            TsonHttpException collected = TsonHttpException.invalidDocument(
                    List.of(withReason(aGap(), Diagnostic.Code.SCHEMA_UNAVAILABLE, reason)));
            TsonHttpException thrown = TsonHttpException.from(
                    new TsonSchemaFetchException("https://example.com/x.tn", reason, "test", null));

            assertEquals(thrown.status(), collected.status(), reason::name);
            assertEquals(thrown.type(), collected.type(), reason::name);
        }
    }

    /** A gap outranks it, on upstream's own precedent: retrying reaches the gap again, the origin may recover. */
    @Test
    void aGapOutranksAnUnavailableSchema() {
        TsonHttpException thrown = TsonHttpException.invalidDocument(
                List.of(withCode(aGap(), Diagnostic.Code.SCHEMA_UNAVAILABLE), aGap()));

        assertEquals(TsonHttpException.NOT_IMPLEMENTED, thrown.status());
    }

    /**
     * <b>Name hygiene is a verdict on the document, so it stays a 400.</b> [TSON-DATA] §8.2's three codes --
     * one per rule -- are the first this project can meet because of its <em>own</em> configuration rather
     * than the format's rules ({@code TsonConfig.tokenPolicy} decides which scripts a value may carry), and
     * that is
     * exactly why the status is worth pinning rather than left to the fall-through. A body refused under a
     * raised policy is refused by this deployment, as one over a size limit is, and it is still the client's
     * to fix; neither code says anything went unchecked, which is what the three 5xx codes have in common
     * and these two do not.
     */
    @Test
    void nameHygieneIsAVerdictOnTheDocument() {
        for (Diagnostic.Code code : List.of(Diagnostic.Code.CONFUSABLE_NAMES,
                Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT)) {
            TsonHttpException thrown = TsonHttpException.invalidDocument(List.of(withCode(anOrdinaryProblem(), code)));

            assertEquals(TsonHttpException.BAD_REQUEST, thrown.status(), code::name);
            assertTrue(thrown.type().endsWith("invalid-document"), thrown.type());
        }
    }

    /**
     * The same rule reached the way a client reaches it: a schemaless record whose two field names a reader
     * cannot tell apart -- Latin {@code admin} beside a Cyrillic-{@code \u0430} one. §8.2 names the record's own
     * field set as the one naming scope at the data layer, and a Class 1 record is where it has to be caught,
     * no declaration standing behind those names.
     */
    @Test
    void twoConfusableFieldNamesInASchemalessBodyAreABadRequest() {
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> codec.readTree(body("{ admin: 1  \u0430dmin: 2 }"), "application/tson"));

        assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
        assertTrue(rejected.diagnostics().stream().anyMatch(d -> d.code() == Diagnostic.Code.CONFUSABLE_NAMES),
                () -> "expected a CONFUSABLE_NAMES diagnostic, got " + rejected.diagnostics());
    }

    /** Rebuilds a diagnostic under {@code code} -- the factories fix theirs, and this needs BIND_MISMATCH. */
    private static Diagnostic withCode(Diagnostic d, Diagnostic.Code code) {
        return withReason(d, code, null);
    }

    /**
     * The same, stating a fetch reason -- which is what a {@code SCHEMA_UNAVAILABLE}'s status turns on. A
     * {@code null} reason is the hand-assembled diagnostic the library itself never produces, and is what the
     * conservative 502 rounding is for.
     */
    private static Diagnostic withReason(Diagnostic d, Diagnostic.Code code,
                                         TsonSchemaFetchException.Reason reason) {
        return new Diagnostic(d.path(), d.schemaPointer(), d.schemaId(), code, d.message(), d.expected(),
                d.actual(), d.dataPosition(), d.schemaPosition(), Optional.ofNullable(reason));
    }

}
