package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaHeaderTest {

    private static final String V1 = "https://schemas.example.com/2026/36/app/order-1.tn";
    private static final String V2 = "https://schemas.example.com/2026/36/app/order-2.tn";

    private static final String V1_SCHEMA = """
            !!id:"https://schemas.example.com/2026/36/app/order-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            { order => { sku: text  quantity: int32 } }
            """;

    /**
     * One instance for the whole class, and the peeks all come from it -- a peek belongs to the processor
     * policy it was opened under, and a reader that disagrees is refused rather than allowed to read the rest
     * of a document under a policy that never governed its start.
     */
    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(V1, V1_SCHEMA)))));

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    /** A body opened for routing: the header read, the rest still to come on the same stream. */
    private static TsonDocumentPeek peek(String document) {
        return TSON.begin(body(document));
    }

    @Test
    void readsAQuotedReference() {
        assertEquals(Optional.of(V1), TsonSchemaHeader.parse("\"" + V1 + "\""));
    }

    @Test
    void anAbsentHeaderIsAbsentNotAnError() {
        assertEquals(Optional.empty(), TsonSchemaHeader.parse(null));
        assertEquals(Optional.empty(), TsonSchemaHeader.parse(""));
        assertEquals(Optional.empty(), TsonSchemaHeader.parse("   "));
    }

    /**
     * The reason sf-string is mandated rather than assumed. RFC 9651's sf-token production admits every
     * character of an unpinned https URL, so a lax parser accepts this and every test anyone writes passes --
     * until a schema gets pinned. Rejecting it from the start is what stops that being discovered in
     * production.
     */
    @Test
    void refusesAnUnquotedReferenceEvenThoughItLooksFine() {
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(V1));
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        assertTrue(refused.getMessage().contains("quoted"), refused.getMessage());
    }

    /** And the case that would have exposed it later: a pinned reference is not a token at all. */
    @Test
    void aPinnedReferenceRoundTripsThroughTheQuotedForm() {
        String pinned = V1 + "?sha256=abc123";
        assertEquals(Optional.of(pinned), TsonSchemaHeader.parse(TsonSchemaHeader.format(pinned)));
        assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(pinned));
    }

    @Test
    void formatsAndReparses() {
        assertEquals("\"" + V1 + "\"", TsonSchemaHeader.format(V1));
        assertEquals(Optional.of(V1), TsonSchemaHeader.parse(TsonSchemaHeader.format(V1)));
    }

    @Test
    void refusesWhatIsNotAnSfString() {
        for (String bad : new String[] {"\"unterminated", "trailing\"", "\"bad\\escape\"", "\"a\"b\"",
                "\"" + V1 + "\", \"" + V2 + "\"", "\"\\\""}) {
            assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(bad), bad);
        }
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(Optional.of(V1), TsonSchemaHeader.parse("  \"" + V1 + "\"  "));
    }

    // ── resolve ──────────────────────────────────────────────────────────

    @Test
    void theHeaderAloneGovernsABodyThatNamesNothing() {
        var governing = TsonSchemaHeader.resolve(peek("{ \"sku\": \"A\" }"), TsonSchemaHeader.format(V1));
        assertEquals(Optional.of(V1), governing.schema());
    }

    @Test
    void theDirectiveAloneGovernsAMessageWithNoHeader() {
        var governing = TsonSchemaHeader.resolve(peek("!!schema:\"" + V1 + "\"\n!order { }"), null);
        assertEquals(Optional.of(V1), governing.schema());
    }

    /** Both are permitted, so a message can be routable and self-contained at once. */
    @Test
    void bothMayAppearWhenTheyAgree() {
        var governing = TsonSchemaHeader.resolve(peek("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1));
        assertEquals(Optional.of(V1), governing.schema());
    }

    /** §2.2.1's own rule for conflicting content hashes: report it, never choose between them. */
    @Test
    void aDisagreementIsAnErrorNotAPrecedenceQuestion() {
        TsonHttpException conflict = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.resolve(peek("!!schema:\"" + V1 + "\"\n!order { }"),
                        TsonSchemaHeader.format(V2)));
        assertEquals(TsonHttpException.BAD_REQUEST, conflict.status());
        assertTrue(conflict.getMessage().contains("order-1"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("order-2"), conflict.getMessage());
    }

    /** Agreement is by canonical identity, so a pin or a different scheme is not a disagreement. */
    @Test
    void agreementIsByCanonicalIdentityNotBySpelling() {
        assertEquals(Optional.of(V1), TsonSchemaHeader.resolve(peek("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1 + "?sha256=abc123")).schema());

        assertEquals(Optional.of(V1), TsonSchemaHeader.resolve(peek("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1.replace("https://", "http://"))).schema());
    }

    @Test
    void aMessageNamingNothingGovernsNothing() {
        assertEquals(Optional.empty(), TsonSchemaHeader.resolve(peek("{ a: 1 }"), null).schema());
    }

    /** The body must still be readable in full after resolution -- nothing is consumed. */
    @Test
    void leavesTheBodyReadable() {
        String document = "!!schema:\"" + V1 + "\"\n!order { sku: \"A\" quantity: 1 }";

        var governing = TsonSchemaHeader.resolve(peek(document), TsonSchemaHeader.format(V1));

        var order = TSON.treeReader().read(governing.body());
        assertEquals("A", order.get("sku").asString().orElseThrow());
        assertEquals(1, order.get("quantity").asInt().orElseThrow());
    }
    /**
     * <b>A one-shot body survives being looked at.</b> An HTTP request body has no mark and no rewind, and
     * the routing decision needs the header before the read that consumes it. Nothing is rewound because
     * nothing is re-read: the header is the stream's first event, so the read continues from just past it.
     *
     * <p>Pinned because the hand-rolled peek this replaced got it wrong in exactly the way a test over
     * {@code ByteArrayInputStream} could not see: that stream supports {@code mark}/{@code reset}, so the
     * body came back intact and the bug stayed invisible. Over a stream that does not, the whole body was
     * gone -- which is why the fixture below refuses {@code reset} rather than merely reporting no mark.
     */
    @Test
    void aBodyWithNoMarkSupportIsStillReadableAfterTheLook() {
        String document = """
                !!schema:"https://schemas.example.com/2026/36/app/order-1.tn"
                !order { sku: "ABC-1"  quantity: 3 }""";

        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(TSON.begin(oneShot(document)), null);

        assertEquals(Optional.of("https://schemas.example.com/2026/36/app/order-1.tn"), governing.schema());
        var order = TSON.treeReader().read(governing.body());
        assertEquals("ABC-1", order.get("sku").asString().orElseThrow(),
                "the value half of the document, read after the header was taken off it");
        assertEquals(3, order.get("quantity").asInt().orElseThrow());
    }

    /** And with no schema to find, the body is still whole. */
    @Test
    void aSchemalessOneShotBodyIsAlsoIntact() {
        // No type annotation either: with no schema in scope there is nothing to resolve `!order` against,
        // and an unknown type-ref would fail the read for a reason that is not what this is about.
        String document = "{ sku: \"ABC-1\"  quantity: 3 }";

        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(TSON.begin(oneShot(document)), null);

        assertEquals(Optional.empty(), governing.schema());
        var order = TSON.treeReader().read(governing.body());
        assertEquals("ABC-1", order.get("sku").asString().orElseThrow());
        assertEquals(3, order.get("quantity").asInt().orElseThrow());
    }

    /** What an HTTP request body actually is: no mark, no rewind. */
    private static InputStream oneShot(String text) {
        return new java.io.FilterInputStream(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void mark(int limit) {
            }

            @Override
            public synchronized void reset() throws java.io.IOException {
                throw new java.io.IOException("mark/reset not supported");
            }
        };
    }

}
