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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaHeaderTest {

    private static final String V1 = "https://schemas.example.com/2026/40/app/order-1.tn";
    private static final String V2 = "https://schemas.example.com/2026/40/app/order-2.tn";
    private static final String MONEY = "https://schemas.example.com/2026/40/app/money.tn";

    private static final String V1_SCHEMA = """
            !!id:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema { order => { sku: text  quantity: int32 } }
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
        assertEquals(List.of(V1), TsonSchemaHeader.parse("\"" + V1 + "\""));
    }

    @Test
    void anAbsentHeaderIsAbsentNotAnError() {
        assertEquals(List.of(), TsonSchemaHeader.parse(null));
        assertEquals(List.of(), TsonSchemaHeader.parse(""));
        assertEquals(List.of(), TsonSchemaHeader.parse("   "));
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
        assertEquals(List.of(pinned), TsonSchemaHeader.parse(TsonSchemaHeader.format(pinned)));
        assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(pinned));
    }

    @Test
    void formatsAndReparses() {
        assertEquals("\"" + V1 + "\"", TsonSchemaHeader.format(V1));
        assertEquals(List.of(V1), TsonSchemaHeader.parse(TsonSchemaHeader.format(V1)));
    }

    @Test
    void refusesWhatIsNotAnSfString() {
        for (String bad : new String[] {"\"unterminated", "trailing\"", "\"bad\\escape\"", "\"a\"b\"",
                "\"\\\"", "\"\"", ",", "\"" + V1 + "\",", "\"" + V1 + "\" \"" + V2 + "\""}) {
            assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(bad), bad);
        }
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(List.of(V1), TsonSchemaHeader.parse("  \"" + V1 + "\"  "));
    }

    /** [TSON-JSON] §3.5: the field is a List of Strings, read in the order written. */
    @Test
    void readsAListOfReferences() {
        assertEquals(List.of(V1, MONEY), TsonSchemaHeader.parse(TsonSchemaHeader.format(V1) + ",  "
                + TsonSchemaHeader.format(MONEY)));
        assertEquals(List.of(V1, MONEY), TsonSchemaHeader.parse(TsonSchemaHeader.format(List.of(V1, MONEY))));
    }

    /** A List of one String is spelt exactly as the single String Item, so the one-schema message is unchanged. */
    @Test
    void aListOfOneIsTheSingleString() {
        assertEquals(TsonSchemaHeader.format(V1), TsonSchemaHeader.format(List.of(V1)));
    }

    /** A member is a String and nothing else: {@code q} belongs to {@code TSON-Accept-Schema}, not this field. */
    @Test
    void aMemberCarryingAParameterIsMalformed() {
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.parse(TsonSchemaHeader.format(V1) + ";q=0.5"));
        assertEquals(TsonHttpException.TYPES + "malformed-schema-header", refused.problem().type().orElseThrow());
        assertTrue(refused.getMessage().contains("parameters"), refused.getMessage());

        assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.parse(TsonSchemaHeader.format(V1) + ", "
                + TsonSchemaHeader.format(MONEY) + ";pinned"));
    }

    /** One malformed member spoils the field: there is no partial binding to fall back on. */
    @Test
    void aMalformedMemberSpoilsTheField() {
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.parse(TsonSchemaHeader.format(V1) + ", " + MONEY));
        assertEquals(TsonHttpException.TYPES + "malformed-schema-header", refused.problem().type().orElseThrow());
    }

    // ── resolve ──────────────────────────────────────────────────────────

    @Test
    void theHeaderAloneGovernsABodyThatNamesNothing() {
        var governing = TsonSchemaHeader.resolve(peek("{ \"sku\": \"A\" }"), TsonSchemaHeader.format(V1));
        assertEquals(List.of(V1), governing.schemas());
    }

    @Test
    void theDirectiveAloneGovernsAMessageWithNoHeader() {
        var governing = TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"), null);
        assertEquals(List.of(V1), governing.schemas());
    }

    /** Both are permitted, so a message can be routable and self-contained at once. */
    @Test
    void bothMayAppearWhenTheyAgree() {
        var governing = TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1));
        assertEquals(List.of(V1), governing.schemas());
    }

    /** §2.2.1's own rule for conflicting content hashes: report it, never choose between them. */
    @Test
    void aDisagreementIsAnErrorNotAPrecedenceQuestion() {
        TsonHttpException conflict = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"),
                        TsonSchemaHeader.format(V2)));
        assertEquals(TsonHttpException.BAD_REQUEST, conflict.status());
        assertTrue(conflict.getMessage().contains("order-1"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("order-2"), conflict.getMessage());
    }

    /** Agreement is by canonical identity, so a pin or a different scheme is not a disagreement. */
    @Test
    void agreementIsByCanonicalIdentityNotBySpelling() {
        assertEquals(List.of(V1), TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1 + "?sha256=abc123")).schemas());

        assertEquals(List.of(V1), TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"),
                TsonSchemaHeader.format(V1.replace("https://", "http://"))).schemas());
    }

    // ── the binding is a set ──────────────────────────────────────────────

    private static final String TWO_IMPORTS = "!!import:\"" + V1 + "\"\n!!import:\"" + MONEY + "\"\n!order { }";

    /**
     * [TSON-JSON] §3.5: field and body agree as sets of canonical identities, so order carries nothing. What
     * governs is the set, spelt as the body spells it.
     */
    @Test
    void aListAgreesWithTheSameImportsInAnotherOrder() {
        var governing = TsonSchemaHeader.resolve(peek(TWO_IMPORTS), TsonSchemaHeader.format(List.of(MONEY, V1)));
        assertEquals(List.of(V1, MONEY), governing.schemas());
    }

    /** Repetition carries nothing either: a member named twice is one identity. */
    @Test
    void repetitionInTheHeaderCarriesNothing() {
        assertEquals(List.of(V1, MONEY), TsonSchemaHeader.resolve(peek(TWO_IMPORTS),
                TsonSchemaHeader.format(List.of(V1, MONEY, V1))).schemas());
        assertEquals(List.of(V1), TsonSchemaHeader.resolve(peek("{ }"),
                TsonSchemaHeader.format(List.of(V1, V1.replace("https://", "http://")))).schemas());
    }

    /**
     * <b>Membership is not agreement.</b> A body importing a schema the header does not name would be read under
     * a wider namespace than the one the header claims, so a header naming one of two imports is refused, not
     * read as "the one that matched".
     */
    @Test
    void aBodyImportingMoreThanTheHeaderNamesIsRefused() {
        TsonHttpException conflict = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.resolve(peek(TWO_IMPORTS), TsonSchemaHeader.format(V1)));
        assertEquals(TsonHttpException.BAD_REQUEST, conflict.status());
        assertEquals(TsonHttpException.TYPES + "conflicting-schema", conflict.problem().type().orElseThrow());
        assertTrue(conflict.getMessage().contains("money"), "both sides are listed: " + conflict.getMessage());
    }

    /** And the other way round: a header naming a schema the body does not import. */
    @Test
    void aHeaderNamingMoreThanTheBodyImportsIsRefused() {
        TsonHttpException conflict = assertThrows(TsonHttpException.class,
                () -> TsonSchemaHeader.resolve(peek("!!import:\"" + V1 + "\"\n!order { }"),
                        TsonSchemaHeader.format(List.of(V1, MONEY))));
        assertEquals(TsonHttpException.TYPES + "conflicting-schema", conflict.problem().type().orElseThrow());
    }

    /** Scheme and pin are excluded from the comparison member by member, as for one. */
    @Test
    void pinsAndSchemesAreIgnoredInTheSetComparison() {
        var governing = TsonSchemaHeader.resolve(peek(TWO_IMPORTS), TsonSchemaHeader.format(List.of(
                MONEY.replace("https://", "http://"), V1 + "?sha256=abc123")));
        assertEquals(List.of(V1, MONEY), governing.schemas(), "spelt as the body spells them");
    }

    /** With no header the body's imports govern, all of them: which is the version is the route's question. */
    @Test
    void severalImportsWithNoHeaderAllGovern() {
        assertEquals(List.of(V1, MONEY), TsonSchemaHeader.resolve(peek(TWO_IMPORTS), null).schemas());
    }

    /** A header alone governs as its set, repetition dropped. */
    @Test
    void aListHeaderAloneGovernsABodyThatNamesNothing() {
        assertEquals(List.of(V1, MONEY), TsonSchemaHeader.resolve(peek("{ }"),
                TsonSchemaHeader.format(List.of(V1, MONEY))).schemas());
    }

    /** A member that is no schema identity governs nothing, and says so under its own type. */
    @Test
    void aMemberThatIsNoIdentityIsUnusable() {
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.resolve(
                peek("{ }"), TsonSchemaHeader.format(List.of(V1, "not a uri"))));
        assertEquals(TsonHttpException.TYPES + "unusable-schema-reference", refused.problem().type().orElseThrow());
    }

    @Test
    void aMessageNamingNothingGovernsNothing() {
        assertEquals(List.of(), TsonSchemaHeader.resolve(peek("{ a: 1 }"), null).schemas());
    }

    /** The body must still be readable in full after resolution -- nothing is consumed. */
    @Test
    void leavesTheBodyReadable() {
        String document = "!!import:\"" + V1 + "\"\n!order { sku: \"A\" quantity: 1 }";

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
                !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
                !order { sku: "ABC-1"  quantity: 3 }""";

        TsonSchemaHeader.Governing governing = TsonSchemaHeader.resolve(TSON.begin(oneShot(document)), null);

        assertEquals(List.of("https://schemas.example.com/2026/40/app/order-1.tn"), governing.schemas());
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

        assertEquals(List.of(), governing.schemas());
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
