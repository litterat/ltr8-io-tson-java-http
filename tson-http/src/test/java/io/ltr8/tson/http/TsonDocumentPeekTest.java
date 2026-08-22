package io.ltr8.tson.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-rolled scanner earns adversarial tests. The rule it must never break: it may answer "I could not
 * tell", but it may never answer with a schema the document does not name -- a wrong answer routes a request to
 * the wrong version, which is exactly what {@link TsonSchemaVersions} exists to prevent.
 */
class TsonDocumentPeekTest {

    private static final String ID = "https://schemas.example.com/2026/32/app/order-1.tn";

    private static TsonDocumentPeek peek(String document) {
        return TsonDocumentPeek.of(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsASchemaDirective() {
        assertEquals(Optional.of(ID), peek("!!schema:\"" + ID + "\"\n!order { sku: \"A\" }").schema());
    }

    /** §2.2 puts !!id first when present; both are read. */
    @Test
    void readsAnIdAndASchemaTogether() {
        TsonDocumentPeek peeked = peek("!!id:\"https://example.com/doc-1.tn\"\n!!schema:\"" + ID
                + "\"\n!order { sku: \"A\" }");
        assertEquals(Optional.of("https://example.com/doc-1.tn"), peeked.id());
        assertEquals(Optional.of(ID), peeked.schema());
    }

    @Test
    void aDocumentWithNoHeaderDeclaresNothing() {
        assertEquals(Optional.empty(), peek("{ sku: \"A\" quantity: 1 }").schema());
        assertEquals(Optional.empty(), peek("!order { sku: \"A\" }").schema());
    }

    /** §7.1: a BOM is stripped before parsing and never part of the content. */
    @Test
    void stripsAByteOrderMark() {
        assertEquals(Optional.of(ID), peek("﻿!!schema:\"" + ID + "\"\n!order { }").schema());
    }

    @Test
    void toleratesLeadingWhitespaceAndSpacesAroundTheArgument() {
        assertEquals(Optional.of(ID), peek("\n\n  !!schema:  \"" + ID + "\"\n!order { }").schema());
    }

    /** The body must still be readable in full afterwards -- the peek consumes nothing. */
    @Test
    void leavesTheStreamReadableFromTheStart() throws IOException {
        String document = "!!schema:\"" + ID + "\"\n!order { sku: \"A\" quantity: 1 }";
        TsonDocumentPeek peeked = peek(document);
        assertEquals(Optional.of(ID), peeked.schema());
        assertEquals(document, new String(peeked.body().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Including for a stream that does not support mark of its own -- it gets buffered. */
    @Test
    void buffersAStreamThatCannotMark() throws IOException {
        String document = "!!schema:\"" + ID + "\"\n!order { sku: \"A\" }";
        InputStream unmarkable = new InputStream() {
            private final InputStream delegate =
                    new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));

            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
        TsonDocumentPeek peeked = TsonDocumentPeek.of(unmarkable);
        assertEquals(Optional.of(ID), peeked.schema());
        assertEquals(document, new String(peeked.body().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** A body far larger than the peek window is untouched by it. */
    @Test
    void readsTheHeaderOfALargeBodyWithoutDisturbingIt() throws IOException {
        String document = "!!schema:\"" + ID + "\"\n!order { sku: \"" + "A".repeat(200_000) + "\" }";
        TsonDocumentPeek peeked = peek(document);
        assertEquals(Optional.of(ID), peeked.schema());
        assertEquals(document.length(),
                new String(peeked.body().readAllBytes(), StandardCharsets.UTF_8).length());
    }

    /** An unterminated argument is unreadable, not a truncated guess. */
    @Test
    void anUnterminatedArgumentIsAbsentRatherThanPartial() {
        assertEquals(Optional.empty(), peek("!!schema:\"https://example.com/unterminated").schema());
        assertEquals(Optional.empty(), peek("!!schema:\"https://example.com/x.tn\n!order { }").schema());
    }

    /** A directive cut off by the peek window is unreadable too -- never half a URL. */
    @Test
    void aHeaderPastTheWindowIsAbsentRatherThanTruncated() {
        String document = "!!schema:\"https://schemas.example.com/" + "d/".repeat(200) + "order-1.tn\"\n!order { }";
        assertEquals(Optional.empty(), TsonDocumentPeek.of(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)), 64).schema());
    }

    /** Scanning stops at the first thing that is not a header directive of a data document. */
    @Test
    void stopsAtAnUnknownDirectiveRatherThanHuntingPastIt() {
        assertEquals(Optional.empty(), peek("!!meta:\"https://example.com/m.tn\"\n!!schema:\"" + ID + "\"\n{}")
                .schema());
    }

    @Test
    void anEmptyOrBlankBodyDeclaresNothing() {
        assertEquals(Optional.empty(), peek("").schema());
        assertEquals(Optional.empty(), peek("   \n  ").schema());
    }

    /** Whatever it is handed, it either reads a real directive or answers absent -- it never invents one. */
    @Test
    void neverInventsASchemaForRubbish() {
        for (String rubbish : new String[] {"!!", "!!schema", "!!schema:", "!!!", "!! schema:\"x\"",
                "\0\0\0", "not tson at all", "{\"json\": true}", "!!schema:", "!!schema:\"\""}) {
            Optional<String> schema = peek(rubbish).schema();
            assertTrue(schema.isEmpty() || !schema.get().isBlank(),
                    "peek invented '" + schema.orElse("") + "' for: " + rubbish);
        }
    }
}
