package io.ltr8.tson.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaHeaderTest {

    private static final String V1 = "https://schemas.example.com/2026/34/app/order-1.tn";
    private static final String V2 = "https://schemas.example.com/2026/34/app/order-2.tn";

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
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
        var governing = TsonSchemaHeader.resolve(body("{ \"sku\": \"A\" }"), TsonSchemaHeader.format(V1));
        assertEquals(Optional.of(V1), governing.schema());
    }

    @Test
    void theDirectiveAloneGovernsAMessageWithNoHeader() {
        var governing = TsonSchemaHeader.resolve(body("!!schema:\"" + V1 + "\"\n!order { }"), null);
        assertEquals(Optional.of(V1), governing.schema());
    }

    /** Both are permitted, so a message can be routable and self-contained at once. */
    @Test
    void bothMayAppearWhenTheyAgree() {
        var governing = TsonSchemaHeader.resolve(body("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1));
        assertEquals(Optional.of(V1), governing.schema());
    }

    /** §2.2.1's own rule for conflicting content hashes: report it, never choose between them. */
    @Test
    void aDisagreementIsAnErrorNotAPrecedenceQuestion() {
        TsonHttpException conflict = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.resolve(body("!!schema:\"" + V1 + "\"\n!order { }"),
                        TsonSchemaHeader.format(V2)));
        assertEquals(TsonHttpException.BAD_REQUEST, conflict.status());
        assertTrue(conflict.getMessage().contains("order-1"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("order-2"), conflict.getMessage());
    }

    /** Agreement is by canonical identity, so a pin or a different scheme is not a disagreement. */
    @Test
    void agreementIsByCanonicalIdentityNotBySpelling() {
        assertEquals(Optional.of(V1), TsonSchemaHeader.resolve(body("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1 + "?sha256=abc123")).schema());

        assertEquals(Optional.of(V1), TsonSchemaHeader.resolve(body("!!schema:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1.replace("https://", "http://"))).schema());
    }

    @Test
    void aMessageNamingNothingGovernsNothing() {
        assertEquals(Optional.empty(), TsonSchemaHeader.resolve(body("{ a: 1 }"), null).schema());
    }

    /** The body must still be readable in full after resolution -- nothing is consumed. */
    @Test
    void leavesTheBodyReadable() throws Exception {
        String document = "!!schema:\"" + V1 + "\"\n!order { sku: \"A\" quantity: 1 }";
        var governing = TsonSchemaHeader.resolve(body(document), TsonSchemaHeader.format(V1));
        assertEquals(document, new String(governing.body().readAllBytes(), StandardCharsets.UTF_8));
    }
    /**
     * <b>A one-shot body survives being looked at.</b> An HTTP request body has no mark and no rewind, and
     * the routing decision needs the header before the read that consumes it. {@code peekResumable} records
     * what the lexer pulled and hands back the document from its first byte.
     *
     * <p>Pinned because the hand-rolled peek this replaced got it wrong in exactly the way a test over
     * {@code ByteArrayInputStream} could not see: that stream supports {@code mark}/{@code reset}, so the
     * body came back intact and the bug stayed invisible. Over a stream that does not, the whole body was
     * gone.
     */
    @Test
    void aBodyWithNoMarkSupportIsStillReadableAfterTheLook() throws Exception {
        String document = """
                !!schema:"https://schemas.example.com/2026/34/app/order-1.tn"
                !order { sku: "ABC-1"  quantity: 3 }""";

        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(oneShot(document), null);

        assertEquals(Optional.of("https://schemas.example.com/2026/34/app/order-1.tn"), governing.schema());
        assertEquals(document, new String(governing.body().readAllBytes(), StandardCharsets.UTF_8),
                "the document, from its first byte -- directives included");
    }

    /** And with no schema to find, the body is still whole. */
    @Test
    void aSchemalessOneShotBodyIsAlsoIntact() throws Exception {
        String document = "!order { sku: \"ABC-1\"  quantity: 3 }";

        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(oneShot(document), null);

        assertEquals(Optional.empty(), governing.schema());
        assertEquals(document, new String(governing.body().readAllBytes(), StandardCharsets.UTF_8));
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
