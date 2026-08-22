package io.ltr8.tson.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonAcceptHeaderTest {

    /** RFC 9110 §12.5.1: no Accept means the client accepts anything. */
    @Test
    void anAbsentOrEmptyAcceptIsAnything() {
        assertTrue(TsonAcceptHeader.parse(null).acceptsTson());
        assertTrue(TsonAcceptHeader.parse("").acceptsTson());
        assertTrue(TsonAcceptHeader.parse("   ").acceptsTson());
    }

    @Test
    void wildcardsAcceptTson() {
        assertTrue(TsonAcceptHeader.parse("*/*").acceptsTson());
        assertTrue(TsonAcceptHeader.parse("application/*").acceptsTson());
        assertTrue(TsonAcceptHeader.parse("application/tson").acceptsTson());
    }

    @Test
    void aClientThatNamesOnlyOtherTypesDoesNotAcceptTson() {
        assertFalse(TsonAcceptHeader.parse("application/json").acceptsTson());
        assertFalse(TsonAcceptHeader.parse("text/*, application/json").acceptsTson());
    }

    @Test
    void readsQualityValues() {
        assertEquals(0.5, TsonAcceptHeader.parse("application/tson;q=0.5").quality(TsonMediaType.APPLICATION_TSON));
        assertEquals(1.0, TsonAcceptHeader.parse("application/tson").quality(TsonMediaType.APPLICATION_TSON));
    }

    /** A matched q=0 is a refusal, not a low preference. */
    @Test
    void qualityZeroIsARefusal() {
        assertFalse(TsonAcceptHeader.parse("*/*, application/tson;q=0").acceptsTson());
        assertFalse(TsonAcceptHeader.parse("application/tson;q=0").acceptsTson());
    }

    /**
     * §12.5.1's precedence: the most specific matching range wins, not the first or the highest. A client that
     * names application/tson exactly has said what it thinks of TSON, whatever it said about application/*.
     */
    @Test
    void specificityBeatsOrderAndMagnitude() {
        TsonAcceptHeader header = TsonAcceptHeader.parse("application/*;q=0.9, application/tson;q=0.1");
        assertEquals(0.1, header.quality(TsonMediaType.APPLICATION_TSON));

        TsonAcceptHeader reversed = TsonAcceptHeader.parse("application/tson;q=0.1, application/*;q=0.9");
        assertEquals(0.1, reversed.quality(TsonMediaType.APPLICATION_TSON));

        assertFalse(TsonAcceptHeader.parse("*/*;q=1.0, application/tson;q=0").acceptsTson());
    }

    /** A range naming parameters is more specific than the bare type/subtype it extends. */
    @Test
    void aParameterisedRangeOutranksTheBareOne() {
        TsonAcceptHeader header = TsonAcceptHeader.parse("application/tson;q=0.2, application/tson;version=1;q=0.8");
        assertEquals(0.8, header.quality(TsonMediaType.parse("application/tson; version=1")));
        assertEquals(0.2, header.quality(TsonMediaType.APPLICATION_TSON));
    }

    /** q is a property of the preference, not of the media type, so it must not distort matching. */
    @Test
    void qualityIsStrippedFromTheRangeItQualifies() {
        assertTrue(TsonAcceptHeader.parse("application/tson;q=0.5").accepts(TsonMediaType.APPLICATION_TSON));
    }

    /**
     * A header this cannot parse is a malformed statement of preference, not a refusal -- reading it as one
     * would turn a client's typo into a 406 it has no way to diagnose.
     */
    @Test
    void aMalformedAcceptIsNotARefusal() {
        assertTrue(TsonAcceptHeader.parse("!!!").acceptsTson());
        assertTrue(TsonAcceptHeader.parse(",,,").acceptsTson());
    }

    /** One bad entry among good ones is dropped; the client's other stated preferences still stand. */
    @Test
    void oneBadEntryDoesNotDiscardTheRest() {
        assertTrue(TsonAcceptHeader.parse("garbage, application/tson").acceptsTson());
        assertFalse(TsonAcceptHeader.parse("garbage, application/json").acceptsTson());
    }

    @Test
    void anOutOfRangeQualityIsClampedRatherThanRejected() {
        assertEquals(1.0, TsonAcceptHeader.parse("application/tson;q=5").quality(TsonMediaType.APPLICATION_TSON));
        assertEquals(0.0, TsonAcceptHeader.parse("application/tson;q=-1").quality(TsonMediaType.APPLICATION_TSON));
    }
}
