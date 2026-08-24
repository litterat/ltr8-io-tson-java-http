package io.ltr8.tson.http;

import io.ltr8.tson.schema.TsonCanonicalIdentity;

import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonDocumentPeek;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The {@code TSON-Schema} header: the identity of the schema governing a message body, carried where something
 * that will not parse the body can still read it.
 *
 * <pre>{@code
 * TSON-Schema: "https://schemas.example.com/2026/32/app/order-1.tn"
 * }</pre>
 *
 * <p><b>It is a projection of {@code !!schema}, not an alternative to it.</b> The body directive remains the
 * format's own mechanism; this exists so that a gateway routing between two versions of a service, or a JSON
 * body that cannot carry a directive at all, has something to go on. See {@code SCHEMA-HEADER.md} for the
 * design and the reasoning.
 *
 * <p>The rules this implements:
 *
 * <ol>
 *   <li>The value is an <b>RFC 9651 sf-string</b> -- quoted, always. Same rule as the directive's own argument,
 *       which must be quoted because a URI falls outside [TSON-DATA] §7.1's unquoted-token profile.</li>
 *   <li>It may appear on a request or a response, and on a body of any media type.</li>
 *   <li>If the body carries {@code !!schema} too, the two MUST agree by <b>canonical identity</b> (§2.2.1 --
 *       scheme and any {@code ?sha256=} pin do not count). A mismatch is an error, never a precedence
 *       question.</li>
 *   <li>Where the body cannot carry a directive, the header is the only channel and is authoritative.</li>
 * </ol>
 *
 * <p><b>Why the quotes are not decoration.</b> RFC 9651's {@code sf-token} production is
 * {@code ( ALPHA / "*" ) *( tchar / ":" / "/" )}, which an unpinned {@code https://} URL satisfies completely --
 * so an unquoted value parses as a token and works in every test anyone writes. It stops working the moment
 * someone pins a schema, because {@code ?sha256=} introduces {@code ?} and {@code =}, neither a tchar. And a
 * pinned reference here is the ordinary case, not a corner: the whole point of permitting the header alongside
 * the directive is that they carry the same string.
 */
public final class TsonSchemaHeader {

    /** The field name. Case-insensitive, as all HTTP field names are. */
    public static final String NAME = "TSON-Schema";

    private TsonSchemaHeader() {
    }

    /** What a message says governs its body, and the body still positioned at the start. */
    public record Governing(Optional<String> schema, InputStream body) {
    }

    /**
     * Parses a field value as an RFC 9651 sf-string.
     *
     * @param fieldValue the raw header value, or {@code null} if the message carried none
     * @return the schema reference it names, or empty if the message carried no such header
     * @throws TsonHttpException 400 if the value is present but is not an sf-string
     */
    public static Optional<String> parse(String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return Optional.empty();
        }
        String trimmed = fieldValue.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            throw malformed(fieldValue, "an RFC 9651 sf-string is quoted; an unquoted URI parses only until "
                    + "someone pins a schema");
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (c == '\\') {
                if (i + 1 >= trimmed.length() - 1) {
                    throw malformed(fieldValue, "a trailing backslash escapes nothing");
                }
                char escaped = trimmed.charAt(++i);
                if (escaped != '"' && escaped != '\\') {
                    throw malformed(fieldValue, "an sf-string escapes only \" and \\, not '" + escaped + "'");
                }
                value.append(escaped);
            } else if (c == '"') {
                throw malformed(fieldValue, "an unescaped quote ends the string early");
            } else if (c < 0x20 || c > 0x7E) {
                throw malformed(fieldValue, "an sf-string holds printable ASCII only; a URI outside it is "
                        + "percent-encoded");
            } else {
                value.append(c);
            }
        }
        return value.isEmpty() ? Optional.empty() : Optional.of(value.toString());
    }

    /** {@code schemaReference} as a field value -- quoted, with anything an sf-string escapes escaped. */
    public static String format(String schemaReference) {
        return '"' + schemaReference.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * What governs this message, from the header and the body's own directive together, with rule 3 enforced.
     *
     * <p>The body is peeked, never consumed; {@link Governing#body()} is the stream to read it from.
     *
     * @param body        the message body
     * @param fieldValue  the {@code TSON-Schema} header value, or {@code null}
     * @throws TsonHttpException 400 if the header is malformed, or names a different schema from the body's own
     *                           {@code !!schema}
     */
    public static Governing resolve(InputStream body, String fieldValue) {
        Optional<String> fromHeader = parse(fieldValue);
        // The library's own header peek, over the real lexer: it buffers what the lexer pulled to reach the
        // end of the header and hands back the document from its first byte, so a one-shot request body
        // survives the look (UPSTREAM.md #9).
        TsonDocumentPeek peek = TsonDocumentHeader.peekResumable(body);
        Optional<String> fromBody = peek.header().schema();

        if (fromHeader.isPresent() && fromBody.isPresent()
                && !identityOf(fromHeader.get()).equals(identityOf(fromBody.get()))) {
            // §2.2.1's own rule for conflicting content hashes, applied to the same shape of problem: at most
            // one of them describes what this body really is, so report it rather than choose.
            throw new TsonHttpException(TsonHttpException.BAD_REQUEST,
                    TsonHttpException.TYPES + "conflicting-schema", "Conflicting schema",
                    "the " + NAME + " header names '" + fromHeader.get() + "' and the body's !!schema names '"
                            + fromBody.get() + "'; at most one of them governs it", List.of(), null);
        }
        // Either, since where both are present they agree. The body's is preferred when it has one, so a value
        // read back out matches what the document itself says.
        return new Governing(fromBody.or(() -> fromHeader), peek.document());
    }

    /** Canonical identity, or a 400 -- a reference that is not a legal identity governs nothing. */
    private static String identityOf(String reference) {
        try {
            return TsonCanonicalIdentity.canonicalize(reference);
        } catch (RuntimeException notAnIdentity) {
            throw new TsonHttpException(TsonHttpException.BAD_REQUEST,
                    TsonHttpException.TYPES + "unusable-schema-reference", "Unusable schema reference",
                    "'" + reference + "' is not a schema identity: " + notAnIdentity.getMessage(), List.of(),
                    notAnIdentity);
        }
    }

    private static TsonHttpException malformed(String fieldValue, String why) {
        return new TsonHttpException(TsonHttpException.BAD_REQUEST,
                TsonHttpException.TYPES + "malformed-schema-header", "Malformed " + NAME + " header",
                "'" + fieldValue + "' is not a valid " + NAME + " value: " + why, List.of(), null);
    }
}
