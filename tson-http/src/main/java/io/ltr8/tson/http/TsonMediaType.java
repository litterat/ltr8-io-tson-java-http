package io.ltr8.tson.http;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One media type -- a type, a subtype and its parameters -- as it appears in a {@code Content-Type} header or a
 * single entry of an {@code Accept} header.
 *
 * <p><b>TSON's media type is {@code application/tson}</b> ([TSON-DATA] §7.1, intended for IANA registration).
 * Version information is not encoded in the media type; where an HTTP context needs to disambiguate, §7.1 allows
 * {@code application/tson; version=1}, which is what {@link #version()} reads.
 *
 * <p><b>A TSON document is UTF-8, so {@code charset} is not a transcoding instruction.</b> §7.1 fixes the
 * encoding at UTF-8; there is no other encoding a TSON document may be in. A {@code charset} parameter naming
 * UTF-8 is therefore redundant but harmless, and one naming anything else describes a document this format does
 * not have -- a client error, not a request to transcode. {@link #hasUnsupportedCharset()} draws that line, and
 * deliberately does not offer a "decode as whatever they said" path.
 *
 * <p>Type, subtype and parameter names are lowercased on construction, since HTTP defines them case-insensitively;
 * parameter <em>values</em> are preserved as written, because in general they are case-sensitive. {@code charset}
 * is the exception HTTP itself calls out, and {@link #charset()} lowercases it for that reason.
 *
 * <p>Parsing rejects malformed input with {@link IllegalArgumentException} rather than any HTTP-flavoured type:
 * this is a value class, and which status code a malformed header earns is {@link TsonHttpCodec}'s policy, not
 * this type's.
 */
public record TsonMediaType(String type, String subtype, Map<String, String> parameters) {

    /** The media type of every TSON document, parameterless: {@code application/tson}. */
    public static final TsonMediaType APPLICATION_TSON = new TsonMediaType("application", "tson", Map.of());

    /** {@code *&#47;*} -- the range an absent or empty {@code Accept} header is treated as. */
    public static final TsonMediaType ANY = new TsonMediaType("*", "*", Map.of());

    /** §7.1's optional disambiguating parameter name, as in {@code application/tson; version=1}. */
    public static final String VERSION = "version";

    /** The encoding parameter name. Present only to be checked, never to be honoured -- see the class note. */
    public static final String CHARSET = "charset";

    /** The one encoding a TSON document may be in ([TSON-DATA] §7.1). */
    public static final String UTF_8 = "utf-8";

    public TsonMediaType {
        type = requireToken(type, "type").toLowerCase(Locale.ROOT);
        subtype = requireToken(subtype, "subtype").toLowerCase(Locale.ROOT);
        Map<String, String> copy = new LinkedHashMap<>();
        parameters.forEach((name, value) -> copy.put(requireToken(name, "parameter name").toLowerCase(Locale.ROOT),
                value));
        parameters = Map.copyOf(copy);
    }

    /**
     * Parses one media type, with parameters.
     *
     * @param header a single media type such as {@code application/tson; version=1} -- not a comma-separated
     *               list, which is {@link TsonAcceptHeader}'s job
     * @throws IllegalArgumentException if {@code header} is null, blank, or not a media type
     */
    public static TsonMediaType parse(String header) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("no media type given");
        }
        String[] parts = header.split(";");
        String[] typeAndSubtype = parts[0].trim().split("/", -1);
        if (typeAndSubtype.length != 2) {
            throw new IllegalArgumentException("'" + header.trim() + "' is not a media type: expected type/subtype");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            if (parameter.isEmpty()) {
                continue;
            }
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("parameter '" + parameter + "' in '" + header.trim()
                        + "' has no value");
            }
            parameters.put(parameter.substring(0, equals).trim(), unquote(parameter.substring(equals + 1).trim()));
        }
        return new TsonMediaType(typeAndSubtype[0].trim(), typeAndSubtype[1].trim(), parameters);
    }

    /** Whether this is {@code application/tson}, whatever its parameters. */
    public boolean isTson() {
        return "application".equals(type) && "tson".equals(subtype);
    }

    /** This type's {@code version} parameter (§7.1), absent if it carries none. */
    public Optional<String> version() {
        return parameter(VERSION);
    }

    /** This type's {@code charset} parameter, lowercased, absent if it carries none. */
    public Optional<String> charset() {
        return parameter(CHARSET).map(value -> value.toLowerCase(Locale.ROOT));
    }

    /** One parameter by name, absent if this type carries none by that name. */
    public Optional<String> parameter(String name) {
        return Optional.ofNullable(parameters.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Whether this type names an encoding a TSON document cannot be in -- a {@code charset} parameter that is
     * present and is not UTF-8. Absent and {@code utf-8} both answer {@code false}: TSON is UTF-8 either way,
     * so saying so adds nothing and omitting it takes nothing away.
     */
    public boolean hasUnsupportedCharset() {
        return charset().filter(charset -> !UTF_8.equals(charset)).isPresent();
    }

    /**
     * Whether {@code candidate} falls within this type read as a media <em>range</em> -- {@code *} matching any
     * type or subtype. Parameters on the range must all be present and equal on the candidate; parameters on the
     * candidate that the range does not mention are ignored, so {@code application/tson} matches
     * {@code application/tson; version=1} but not the other way round.
     */
    public boolean matches(TsonMediaType candidate) {
        if (!"*".equals(type) && !type.equals(candidate.type)) {
            return false;
        }
        if (!"*".equals(subtype) && !subtype.equals(candidate.subtype)) {
            return false;
        }
        return parameters.entrySet().stream()
                .allMatch(parameter -> parameter.getValue().equals(candidate.parameters.get(parameter.getKey())));
    }

    /** This type with one parameter added or replaced. */
    public TsonMediaType withParameter(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(parameters);
        merged.put(name, value);
        return new TsonMediaType(type, subtype, merged);
    }

    /** The header form -- {@code type/subtype} followed by each parameter, suitable for a {@code Content-Type}. */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder(type).append('/').append(subtype);
        parameters.forEach((name, value) -> rendered.append("; ").append(name).append('=').append(quoteIfNeeded(value)));
        return rendered.toString();
    }

    private static String requireToken(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("media type " + what + " is empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // RFC 9110's tchar, minus the separators a media type can never contain unquoted. Checked so a
            // header like `application/tson, text/plain` fails here rather than silently becoming a subtype
            // named "tson, text/plain".
            if (c <= ' ' || c >= 0x7F || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                throw new IllegalArgumentException("media type " + what + " '" + value + "' contains an illegal "
                        + "character at index " + i);
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static String quoteIfNeeded(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c >= 0x7F || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
        }
        return value;
    }
}
