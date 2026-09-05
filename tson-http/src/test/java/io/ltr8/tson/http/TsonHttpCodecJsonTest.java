package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@link TsonHttpCodec#acceptingJson()} actually admits, now that [TSON-DATA] §6 no longer makes TSON a
 * JSON superset.
 *
 * <p>Revision 35 withdrew the superset claim and put JSON compatibility in a separate <b>JSON reader</b> --
 * "a second encoding of the same model rather than a mode of this notation" -- which tson-java has not built.
 * So the gate admits JSON as the <em>TSON</em> reader reads it, and these are the places the two differ.
 *
 * <p><b>Written to fail when a real JSON reader lands.</b> That failure is the feature arriving, not a
 * regression: at that point this class becomes the check that the codec routes a JSON body to it. Until then
 * it is the honest statement of what an endpoint opts into, which the method's own Javadoc repeats because a
 * caller reads that and not this.
 */
class TsonHttpCodecJsonTest {

    private final TsonHttpCodec codec =
            new TsonHttpCodec(Tson.builder().schemaSource(uri -> null).build()).acceptingJson();

    /** The four divergences, most dangerous first. */
    @Test
    void theTsonReaderIsNotAJsonReader() {
        // 1. Silent, and the only one that corrupts rather than refuses: JSON `null` is the string "null".
        //    §4.4 removed the null keyword, so the token is text; §6's reader is what maps it to absence.
        TsonValue read = codec.readTree(json("{\"a\": null}"), "application/json");
        assertEquals("null", read.get("a").asString().orElseThrow(),
                "JSON null reads as the four-character string, which no diagnostic reports");
        assertFalse(read.get("a").isAbsent(), "and it is not absence, which is what §6's reader would give");

        // 2. A key that is not an identifier. §2.5 makes a field name an identifier whichever spelling carried
        //    it, so ordinary JSON keys are refused; §6's reader maps such an object to a map instead.
        assertRefused("{\"first name\": 1}");
        assertRefused("{\"a.b\": 1}");

        // 3. A surrogate-pair escape -- how JSON must write a non-BMP character. §7.2.2 asks whether the value
        //    denoted is a scalar value, so it refuses on the first half and there is nothing to pair.
        assertRefused("{\"a\": \"\\uD83D\\uDE00\"}");

        // 4. RFC 8259 permits `\/`; TSON's escape table does not list it.
        assertRefused("{\"a\": \"x\\/y\"}");
    }

    /**
     * What does survive, so the divergence list above is read as a list and not as "JSON does not work". The
     * shapes both notations share -- objects with identifier keys, arrays, strings, numbers, booleans -- read
     * as they look, which is why the gate is worth keeping rather than deleting.
     */
    @Test
    void theSharedShapesReadAsTheyLook() {
        TsonValue read = codec.readTree(
                json("{\"a\": [1, 2, 3], \"b\": {\"c\": true}, \"d\": \"x\"}"), "application/json");

        assertEquals(List.of(1L, 2L, 3L), read.at("/a").elements().stream()
                .map(e -> e.asLong().orElseThrow()).toList());
        assertEquals(Boolean.TRUE, read.at("/b/c").asBoolean().orElseThrow());
        assertEquals("x", read.at("/d").asString().orElseThrow());
    }

    /** A body an endpoint that did not opt in never sees: the gate is still a gate. */
    @Test
    void aJsonBodyIsA415WithoutTheOptIn() {
        TsonHttpCodec tsonOnly = new TsonHttpCodec(Tson.builder().schemaSource(uri -> null).build());

        assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE,
                assertThrows(TsonHttpException.class,
                        () -> tsonOnly.readTree(json("{\"a\": 1}"), "application/json")).status());
    }

    private void assertRefused(String document) {
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> codec.readTree(json(document), "application/json"), () -> "expected a refusal for " + document);
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status(), document);
    }

    private static InputStream json(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
