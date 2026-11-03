package io.ltr8.tson.http;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.compiler.TsonDocumentPeek;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The {@code TSON-Schema} header: the schemas governing a message body, carried where something that will not
 * parse the body can still read them.
 *
 * <pre>{@code
 * TSON-Schema: "https://schemas.example.com/2026/39/app/order-1.tn"
 * TSON-Schema: "https://schemas.example.com/2026/39/app/order-1.tn", "https://example.com/2026/39/money.tn"
 * }</pre>
 *
 * <p><b>It is a projection of the body's binding, not an alternative to it.</b> The body's {@code !!import}s remain
 * the format's own mechanism; this exists so that a gateway routing between two versions of a service, or a JSON
 * body that carries no binding of its own, has something to go on. [TSON-JSON] §3.5 defines the field for every
 * TSON-carrying body; {@code SCHEMA-HEADER.md} holds the design record behind it.
 *
 * <p>The rules this implements, each §3.5's:
 *
 * <ol>
 *   <li>The value is an <b>RFC 9651 List of Strings</b>, each member a schema reference exactly as an
 *       {@code !!import} holds one, and each quoted, always. A member carries no parameters. A List of one String
 *       is spelt exactly as a single String Item, so a one-schema message reads the same either way.</li>
 *   <li>It may appear on a request or a response, and on a body of any media type.</li>
 *   <li>If the body imports schemas too, the two MUST name the same <b>set of canonical identities</b> (§3.4;
 *       [TSON-DATA] §2.2.1 -- scheme and any {@code ?sha256=} pin do not count, nor do order and repetition). A
 *       mismatch is an error, never a precedence question, and the sets are compared whole: a body importing one
 *       schema more than the field names would be read under a wider namespace than the one the field claims.</li>
 *   <li>Where the body carries no binding, the field is the only channel and is authoritative.</li>
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

    /**
     * What a message says governs its body, and the body itself -- as the peek the header was read from, which
     * is what a reader continues on. {@link TsonDocumentPeek} is the whole body: the header has been read, and
     * a read handed this one carries on from just past it.
     *
     * @param schemas the governing binding: one reference per canonical identity, in the order first named, and
     *                empty for a body naming no schema by either channel
     * @param body    the body, as the peek its header was read from
     */
    public record Governing(List<String> schemas, TsonDocumentPeek body) {

        public Governing {
            schemas = List.copyOf(schemas);
        }
    }

    /**
     * Parses a field value as an RFC 9651 List of Strings.
     *
     * <p>An absent or blank field is an empty List, which RFC 9651 serialises by leaving the field out; a field
     * that is present and holds no member (a lone comma) is malformed.
     *
     * @param fieldValue the raw header value, or {@code null} if the message carried none
     * @return the schema references it names, in the order written, or empty if the message carried no such header
     * @throws TsonHttpException 400 if the value is present but is not a List of Strings without parameters
     */
    public static List<String> parse(String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return List.of();
        }
        Function<String, TsonHttpException> malformed = why -> malformed(fieldValue, why);
        List<String> references = new ArrayList<>();
        for (String member : members(fieldValue, malformed)) {
            int end = endOfString(member, malformed);
            String after = member.substring(end + 1).trim();
            if (after.startsWith(";")) {
                throw malformed(fieldValue, "a " + NAME + " member carries no parameters");
            }
            if (!after.isEmpty()) {
                throw malformed(fieldValue, "members are separated by commas");
            }
            references.add(string(member, malformed));
        }
        return List.copyOf(references);
    }

    /** {@code schemaReference} as a field value -- quoted, with anything an sf-string escapes escaped. */
    public static String format(String schemaReference) {
        return '"' + schemaReference.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** {@code schemaReferences} as a field value: an RFC 9651 List, in the order given, each member quoted. */
    public static String format(List<String> schemaReferences) {
        if (schemaReferences.isEmpty()) {
            throw new IllegalArgumentException("an empty List is written by leaving the field out");
        }
        return schemaReferences.stream().map(TsonSchemaHeader::format).collect(Collectors.joining(", "));
    }

    /**
     * What governs this message, from the header and the body's own {@code !!import}s together, with rule 3
     * enforced.
     *
     * <p>A data document binds the schemas it imports, so the body's claim is its import list, read as a set of
     * canonical identities ([TSON-JSON] §3.4: several imports are one namespace, and order carries nothing). The
     * header is the same claim made in the transport. What governs is then:
     *
     * <ul>
     *   <li>where both are present and name the same set, that set, spelled as the body spells it;</li>
     *   <li>where both are present and the sets differ, nothing: a 400 {@code conflicting-schema}, since at most
     *       one of the two describes what the body is;</li>
     *   <li>the header's set, where the body imports nothing;</li>
     *   <li>the body's imports, where there is no header;</li>
     *   <li>nothing, where neither names a schema.</li>
     * </ul>
     *
     * <p>Which member of the set is a contract's version is not this method's question; {@link
     * TsonSchemaVersions#route} answers it.
     *
     * <p>The body is peeked, never consumed; {@link Governing#body()} is what to read it from.
     *
     * <p><b>The caller opens the peek</b>, with {@link TsonHttpCodec#begin} or {@code Tson.begin}, because a
     * peek belongs to the processor policy it was opened under: §9.1's limits and §8.2's token policy were
     * applied to the tokens the header is made of, and a reader that disagrees is refused rather than allowed
     * to read the rest of one document under a policy that never governed its start. Nothing here could pick
     * that policy correctly on the caller's behalf.
     *
     * @param body        the message body, already opened as a peek
     * @param fieldValue  the {@code TSON-Schema} header value, or {@code null}
     * @throws TsonHttpException 400 if the header is malformed, names a reference that is no schema identity, or
     *                           names a different set of schemas from the body's
     */
    public static Governing resolve(TsonDocumentPeek body, String fieldValue) {
        List<String> fromHeader = distinct(parse(fieldValue));
        List<String> imports = body.header().imports();
        if (imports.isEmpty()) {
            return new Governing(fromHeader, body);
        }
        if (fromHeader.isEmpty()) {
            return new Governing(distinctAsSpelled(imports), body);
        }
        List<String> imported = distinct(imports);
        if (!identities(fromHeader).equals(identities(imported))) {
            // §2.2.1's own rule for conflicting content hashes, applied to the same shape of problem: at most
            // one of them describes what this body really is, so report it rather than choose.
            throw new TsonHttpException(TsonHttpException.BAD_REQUEST,
                    TsonHttpException.TYPES + "conflicting-schema", "Conflicting schema",
                    "the " + NAME + " header names " + quoted(fromHeader) + " and the body imports "
                            + quoted(imports) + "; at most one of them governs it", List.of(), null);
        }
        return new Governing(imported, body);
    }

    /**
     * {@code references} with a repeated canonical identity dropped, keeping the first spelling of each.
     *
     * @throws TsonHttpException 400 {@code unusable-schema-reference} for a member that is no schema identity
     */
    static List<String> distinct(List<String> references) {
        Map<String, String> byIdentity = new LinkedHashMap<>();
        references.forEach(reference -> byIdentity.putIfAbsent(identityOf(reference), reference));
        return List.copyOf(byIdentity.values());
    }

    /** The canonical identities {@code references} name, as a set. */
    static Set<String> identities(List<String> references) {
        Set<String> identities = new HashSet<>();
        references.forEach(reference -> identities.add(identityOf(reference)));
        return identities;
    }

    /**
     * A body's imports with a repeated identity dropped, where no header asks for the comparison. A reference
     * that is no identity is kept as spelt rather than refused here: the read that follows reports it with the
     * body's other problems.
     */
    private static List<String> distinctAsSpelled(List<String> imports) {
        Map<String, String> byIdentity = new LinkedHashMap<>();
        for (String reference : imports) {
            String identity;
            try {
                identity = CanonicalIdentity.canonicalize(reference);
            } catch (RuntimeException notAnIdentity) {
                identity = reference;
            }
            byIdentity.putIfAbsent(identity, reference);
        }
        return List.copyOf(byIdentity.values());
    }

    /** References as a message lists them: one quoted, several in brackets. */
    static String quoted(List<String> references) {
        if (references.size() == 1) {
            return "'" + references.getFirst() + "'";
        }
        return references.stream().map(reference -> "'" + reference + "'")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Canonical identity, or a 400 -- a reference that is not a legal identity governs nothing. */
    static String identityOf(String reference) {
        try {
            return CanonicalIdentity.canonicalize(reference);
        } catch (RuntimeException notAnIdentity) {
            throw new TsonHttpException(TsonHttpException.BAD_REQUEST,
                    TsonHttpException.TYPES + "unusable-schema-reference", "Unusable schema reference",
                    "'" + reference + "' is not a schema identity: " + notAnIdentity.getMessage(), List.of(),
                    notAnIdentity);
        }
    }

    // ── RFC 9651 List parsing, shared with TsonAcceptSchemaHeader ──────────────────────────────────────

    /**
     * Splits a List field value into its members, on commas outside quoted strings -- a schema URI holds no
     * comma, but an escape may sit beside one. Each member is trimmed.
     *
     * @throws TsonHttpException from {@code malformed} for a present field holding no member, or an empty one
     */
    static List<String> members(String fieldValue, Function<String, TsonHttpException> malformed) {
        List<String> members = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0; i < fieldValue.length(); i++) {
            char c = fieldValue.charAt(i);
            if (c == '\\' && inQuotes) {
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                members.add(fieldValue.substring(start, i).trim());
                start = i + 1;
            }
        }
        members.add(fieldValue.substring(start).trim());
        if (members.stream().anyMatch(String::isEmpty)) {
            throw malformed.apply(members.stream().allMatch(String::isEmpty)
                    ? "a present field names at least one schema" : "a List has no empty member");
        }
        return members;
    }

    /**
     * The index of the quote closing the sf-string {@code member} opens.
     *
     * @throws TsonHttpException from {@code malformed} if the member does not open with a quoted string
     */
    static int endOfString(String member, Function<String, TsonHttpException> malformed) {
        if (member.isEmpty() || member.charAt(0) != '"') {
            throw malformed.apply("an RFC 9651 sf-string is quoted; an unquoted URI parses only until someone "
                    + "pins a schema");
        }
        for (int i = 1; i < member.length(); i++) {
            char c = member.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        throw malformed.apply("an sf-string is closed by a quote");
    }

    /**
     * The value of the sf-string {@code quoted}, which is the whole of it, quotes included.
     *
     * @throws TsonHttpException from {@code malformed} if it is not an sf-string, or holds the empty string
     */
    static String string(String quoted, Function<String, TsonHttpException> malformed) {
        String trimmed = quoted.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            throw malformed.apply("an RFC 9651 sf-string is quoted; an unquoted URI parses only until someone "
                    + "pins a schema");
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (c == '\\') {
                if (i + 1 >= trimmed.length() - 1) {
                    throw malformed.apply("a trailing backslash escapes nothing");
                }
                char escaped = trimmed.charAt(++i);
                if (escaped != '"' && escaped != '\\') {
                    throw malformed.apply("an sf-string escapes only \" and \\, not '" + escaped + "'");
                }
                value.append(escaped);
            } else if (c == '"') {
                throw malformed.apply("an unescaped quote ends the string early");
            } else if (c < 0x20 || c > 0x7E) {
                throw malformed.apply("an sf-string holds printable ASCII only; a URI outside it is "
                        + "percent-encoded");
            } else {
                value.append(c);
            }
        }
        if (value.isEmpty()) {
            throw malformed.apply("an empty schema reference names no schema");
        }
        return value.toString();
    }

    private static TsonHttpException malformed(String fieldValue, String why) {
        return new TsonHttpException(TsonHttpException.BAD_REQUEST,
                TsonHttpException.TYPES + "malformed-schema-header", "Malformed " + NAME + " header",
                "'" + fieldValue + "' is not a valid " + NAME + " value: " + why, List.of(), null);
    }
}
