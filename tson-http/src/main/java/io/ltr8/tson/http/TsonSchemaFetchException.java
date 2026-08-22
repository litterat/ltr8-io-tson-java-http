package io.ltr8.tson.http;

/**
 * A schema reference that could not be turned into schema text, and why. The {@link Reason} is what separates a
 * client's mistake from this server's -- a distinction {@link TsonHttpException#from} needs and cannot recover
 * once a fetch failure has been flattened into a generic exception.
 *
 * <p><b>Upstream's three-way classification has no slot for this.</b> tson-java splits a failure into "the
 * author's schema is wrong", "this library hasn't implemented that yet", and "an internal invariant broke". A
 * schema host that is down is none of the three: nobody's document is wrong, nothing is unimplemented, and no
 * invariant broke. Fetching is a capability tson-java deliberately does not have (see {@code TsonSchemaSource}'s
 * own note), so the failure modes that come with it are classified here instead.
 */
public final class TsonSchemaFetchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a fetch failed -- and, through {@link TsonHttpException#from}, whose fault it is. */
    public enum Reason {

        /** Policy refused it: not an allowed origin, not an allowed scheme, or no content-hash pin where one is required. */
        NOT_PERMITTED,

        /** The origin was reached and does not have it. */
        NOT_FOUND,

        /** The origin could not be reached, or answered with something other than a document. */
        TRANSPORT,

        /** The origin did not answer in time. */
        TIMEOUT,

        /** The origin answered with more bytes than a schema document is allowed to be. */
        TOO_LARGE
    }

    private final String uri;
    private final Reason reason;

    public TsonSchemaFetchException(String uri, Reason reason, String message, Throwable cause) {
        super("cannot fetch schema '" + uri + "': " + message, cause);
        this.uri = uri;
        this.reason = reason;
    }

    /** The schema reference that could not be fetched, as written. */
    public String uri() {
        return uri;
    }

    /** Why it could not be. */
    public Reason reason() {
        return reason;
    }
}
