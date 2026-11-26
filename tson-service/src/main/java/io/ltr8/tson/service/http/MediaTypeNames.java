package io.ltr8.tson.service.http;

/**
 * The media type names a description's reader compares an endpoint's media types against: the names
 * {@code TsonMediaType} holds in {@code tson-http}, written out here because this module takes no dependency on it.
 * Each is the bare {@code type/subtype}, as {@code TsonMediaType.toString()} renders a constant with no parameters.
 */
final class MediaTypeNames {

    /** TSON text, {@code TsonMediaType.APPLICATION_TSON}. */
    static final String APPLICATION_TSON = "application/tson";

    /** TSON's JSON encoding under its plain label, {@code TsonMediaType.APPLICATION_JSON}. */
    static final String APPLICATION_JSON = "application/json";

    /** TSON's JSON encoding under its own label, {@code TsonMediaType.APPLICATION_TSON_JSON}. */
    static final String APPLICATION_TSON_JSON = "application/tson+json";

    private MediaTypeNames() {
    }
}
