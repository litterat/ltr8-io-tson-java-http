package io.ltr8.tson.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A parsed {@code Accept} header -- the media ranges a client will take, each with its quality value -- and the
 * question a handler actually asks of it: <em>would this client accept a TSON response?</em>
 *
 * <p><b>An absent, empty or unparseable {@code Accept} is "anything".</b> RFC 9110 §12.5.1 says a request with no
 * {@code Accept} indicates the client accepts any media type, and a header this fails to parse is a malformed
 * statement of preference rather than a statement of refusal -- treating it as a refusal would turn a client's
 * typo into a 406 it cannot diagnose. {@link #parse} therefore never throws.
 *
 * <p><b>Specificity beats order, and {@code q=0} is a refusal.</b> Quality for a candidate is taken from the most
 * specific matching range, not the first or the highest: {@code application/*;q=0.9, application/tson;q=0.1} scores
 * TSON at 0.1, because the client named it exactly. That is RFC 9110 §12.5.1's precedence -- exact type and
 * subtype with parameters, then exact type and subtype, then {@code type/&#42;}, then {@code *&#47;*} -- and it is the
 * whole reason ranges are ranked rather than scanned. A matched {@code q=0} means "not this one", so
 * {@link #accepts} is a strict {@code > 0} test.
 */
public record TsonAcceptHeader(List<Range> ranges) {

    /** One entry of an {@code Accept} header: a media range and the quality the client attached to it. */
    public record Range(TsonMediaType mediaType, double quality) {

        /** How specific this range is, for §12.5.1's precedence: higher wins when two ranges both match. */
        int precedence() {
            if ("*".equals(mediaType.type())) {
                return 0;
            }
            if ("*".equals(mediaType.subtype())) {
                return 1;
            }
            // A range naming parameters beyond `q` is more specific than the bare type/subtype it extends.
            return mediaType.parameters().isEmpty() ? 2 : 3;
        }
    }

    /** An {@code Accept} of {@code *&#47;*} -- what an absent or unparseable header is read as. */
    public static final TsonAcceptHeader ANY = new TsonAcceptHeader(List.of(new Range(TsonMediaType.ANY, 1.0)));

    public TsonAcceptHeader {
        ranges = List.copyOf(ranges);
    }

    /**
     * Parses a comma-separated {@code Accept} header. Never throws: a null, blank or malformed header, and any
     * individual entry that is not a media range, is read as {@link #ANY} or dropped respectively -- see the class
     * note on why a malformed preference is not a refusal.
     */
    public static TsonAcceptHeader parse(String header) {
        if (header == null || header.isBlank()) {
            return ANY;
        }
        List<Range> parsed = new ArrayList<>();
        for (String entry : header.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                TsonMediaType mediaType = TsonMediaType.parse(entry);
                // `q` separates the accept-params from the media range's own parameters; it is a property of the
                // preference, not of the type, so it is stripped rather than left to distort `matches`.
                double quality = mediaType.parameter("q").map(TsonAcceptHeader::quality).orElse(1.0);
                parsed.add(new Range(withoutQuality(mediaType), quality));
            } catch (IllegalArgumentException malformed) {
                // One bad entry among good ones is dropped; the client's other stated preferences still stand.
            }
        }
        return parsed.isEmpty() ? ANY : new TsonAcceptHeader(parsed);
    }

    /**
     * The quality this client attached to {@code candidate}, from the most specific matching range, or {@code 0}
     * if nothing matches it -- which is the same answer as an explicit {@code q=0} and means the same thing.
     */
    public double quality(TsonMediaType candidate) {
        return ranges.stream()
                .filter(range -> range.mediaType().matches(candidate))
                .max(Comparator.comparingInt(Range::precedence).thenComparingDouble(Range::quality))
                .map(Range::quality)
                .orElse(0.0);
    }

    /** Whether this client would accept {@code candidate} at all. */
    public boolean accepts(TsonMediaType candidate) {
        return quality(candidate) > 0.0;
    }

    /** Whether this client would accept an {@code application/tson} response. */
    public boolean acceptsTson() {
        return accepts(TsonMediaType.APPLICATION_TSON);
    }

    private static double quality(String value) {
        try {
            // Clamped rather than rejected: q is a preference, and an out-of-range one is still a preference.
            return Math.clamp(Double.parseDouble(value), 0.0, 1.0);
        } catch (NumberFormatException notANumber) {
            return 1.0;
        }
    }

    private static TsonMediaType withoutQuality(TsonMediaType mediaType) {
        if (!mediaType.parameters().containsKey("q")) {
            return mediaType;
        }
        var remaining = new java.util.LinkedHashMap<>(mediaType.parameters());
        remaining.remove("q");
        return new TsonMediaType(mediaType.type(), mediaType.subtype(), remaining);
    }
}
