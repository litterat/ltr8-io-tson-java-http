package io.ltr8.tson.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonMediaTypeTest {

    @Test
    void parsesTheBareTsonMediaType() {
        TsonMediaType parsed = TsonMediaType.parse("application/tson");
        assertTrue(parsed.isTson());
        assertEquals(TsonMediaType.APPLICATION_TSON, parsed);
    }

    @Test
    void readsSection7Point1sVersionParameter() {
        assertEquals("1", TsonMediaType.parse("application/tson; version=1").version().orElseThrow());
    }

    @Test
    void lowercasesTypeSubtypeAndParameterNamesButNotValues() {
        TsonMediaType parsed = TsonMediaType.parse("Application/TSON; Version=A1");
        assertTrue(parsed.isTson());
        assertEquals("A1", parsed.version().orElseThrow());
    }

    @Test
    void toleratesWhitespaceAndQuotedParameterValues() {
        TsonMediaType parsed = TsonMediaType.parse("  application/tson ;  version = \"1\"  ");
        assertTrue(parsed.isTson());
        assertEquals("1", parsed.version().orElseThrow());
    }

    /**
     * [TSON-DATA] §7.1 fixes the encoding at UTF-8, so saying so adds nothing and omitting it takes nothing
     * away. Only a charset naming something else describes a document TSON does not have.
     */
    @Test
    void onlyACharsetThatIsNotUtf8IsUnsupported() {
        assertFalse(TsonMediaType.parse("application/tson").hasUnsupportedCharset());
        assertFalse(TsonMediaType.parse("application/tson; charset=utf-8").hasUnsupportedCharset());
        assertFalse(TsonMediaType.parse("application/tson; charset=UTF-8").hasUnsupportedCharset());
        assertTrue(TsonMediaType.parse("application/tson; charset=iso-8859-1").hasUnsupportedCharset());
    }

    @Test
    void aRangeMatchesAnythingItsWildcardsCover() {
        assertTrue(TsonMediaType.ANY.matches(TsonMediaType.APPLICATION_TSON));
        assertTrue(TsonMediaType.parse("application/*").matches(TsonMediaType.APPLICATION_TSON));
        assertFalse(TsonMediaType.parse("text/*").matches(TsonMediaType.APPLICATION_TSON));
    }

    /** A range's parameters must all be met; a candidate's extra parameters are ignored. */
    @Test
    void parameterMatchingIsOneWay() {
        TsonMediaType bare = TsonMediaType.APPLICATION_TSON;
        TsonMediaType versioned = TsonMediaType.parse("application/tson; version=1");
        assertTrue(bare.matches(versioned));
        assertFalse(versioned.matches(bare));
        assertFalse(TsonMediaType.parse("application/tson; version=2").matches(versioned));
    }

    @Test
    void rendersBackToAHeaderItCanReparse() {
        String header = TsonMediaType.APPLICATION_TSON.withParameter(TsonMediaType.VERSION, "1").toString();
        assertEquals("application/tson; version=1", header);
        assertEquals("1", TsonMediaType.parse(header).version().orElseThrow());
    }

    /**
     * A comma-separated list is an Accept header, not a media type. Without the token check the subtype would
     * silently become "tson, text/plain" and the value would still claim to be TSON.
     */
    @Test
    void refusesAListWhereOneMediaTypeIsExpected() {
        assertThrows(IllegalArgumentException.class, () -> TsonMediaType.parse("application/tson, text/plain"));
    }

    @Test
    void refusesWhatIsNotAMediaTypeAtAll() {
        assertThrows(IllegalArgumentException.class, () -> TsonMediaType.parse("application"));
        assertThrows(IllegalArgumentException.class, () -> TsonMediaType.parse("application/tson; version"));
        assertThrows(IllegalArgumentException.class, () -> TsonMediaType.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> TsonMediaType.parse(null));
    }
}
