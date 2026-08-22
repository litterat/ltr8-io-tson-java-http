package io.ltr8.tson.http;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.util.List;

/**
 * A request that cannot be fulfilled, carrying the status it earns and the diagnostics that produced it. An
 * adapter catches this, writes {@link #problem()} as the body, and sets {@link #status()}.
 *
 * <p><b>{@link #from} is the whole status policy, in one place.</b> It mirrors the classification tson-java
 * already makes -- and must not invent a second one:
 *
 * <table border="1">
 * <caption>Library exception to HTTP status</caption>
 * <tr><th>Upstream</th><th>Means</th><th>Status</th></tr>
 * <tr><td>{@link TsonReadException}</td><td>this document breaks its schema</td><td>400</td></tr>
 * <tr><td>{@link TsonSchemaValidationException}</td><td>the schema it names is wrong or unavailable</td><td>400</td></tr>
 * <tr><td>{@link UnsupportedOperationException}</td><td>the library hasn't implemented that yet</td><td>501</td></tr>
 * <tr><td>{@link IllegalStateException}</td><td>an internal invariant broke</td><td>500</td></tr>
 * <tr><td>a base-syntax failure</td><td>the body doesn't lex, doesn't parse, or isn't data</td><td>400</td></tr>
 * <tr><td>{@link TsonSchemaFetchException}</td><td>see below -- 400, 502 or 504 by reason</td><td>4xx/5xx</td></tr>
 * </table>
 *
 * <p><b>A fetch failure splits by whose fault it is.</b> A document naming a schema this server will not load, or
 * one that does not exist, is the document's problem: 400. A permitted origin that is unreachable, oversized or
 * slow is this server's dependency failing while the request was perfectly good, which is what 502 and 504 are
 * for. Collapsing these into one status would either blame a client for an outage or hide an outage as a client
 * error -- and the retry advice differs: a 400 says fix the document, a 502 says try again.
 *
 * <p><b>The last row cannot be written as a {@code catch} here.</b> A document that fails before any reader sees
 * a value throws rather than reporting, and two of the three exception types involved live in {@code
 * tson-compiler}'s unexported {@code lexer} package -- no caller in another module can name them. {@link
 * Diagnostic#ofBaseSyntaxError} exists for exactly that reason: it classifies those three and rethrows anything
 * else, which is also what keeps an unexpected fault from being laundered into a false verdict about the request.
 *
 * <p><b>A gap must never be reported as a client error.</b> That is the load-bearing row. tson-java's
 * classification test is "a schema error's verdict doesn't change when this library improves; a gap's does", and
 * the CLI rides exit 1 against exit 70 on it. Answering 400 for an {@code UnsupportedOperationException} tells a
 * client to fix a document that is not wrong, and sends them round a retry loop that cannot terminate. 501 says
 * what is true: the request was fine and this server cannot do it yet.
 *
 * <p>Anything not in the table is not classified here at all -- {@link #from} rethrows it, so an unexpected fault
 * reaches the adapter's own handler with its stack trace intact rather than being laundered into a false verdict
 * about the request.
 */
public final class TsonHttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The document, or the request framing around it, is wrong. */
    public static final int BAD_REQUEST = 400;

    /** The request body is not TSON, or claims an encoding TSON documents are never in. */
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;

    /** The client will not take an {@code application/tson} response. */
    public static final int NOT_ACCEPTABLE = 406;

    /** A fault in this server or the library under it. */
    public static final int INTERNAL_SERVER_ERROR = 500;

    /** The request is well-formed and this implementation does not support it yet. */
    public static final int NOT_IMPLEMENTED = 501;

    /** A dependency of this server -- the origin holding a schema -- failed or answered with nonsense. */
    public static final int BAD_GATEWAY = 502;

    /** A dependency of this server did not answer in time. */
    public static final int GATEWAY_TIMEOUT = 504;

    private final int status;
    private final String title;
    private final transient List<Diagnostic> diagnostics;

    public TsonHttpException(int status, String title, String detail, List<Diagnostic> diagnostics, Throwable cause) {
        super(detail == null || detail.isBlank() ? title : detail, cause);
        this.status = status;
        this.title = title;
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** The status this failure earns. */
    public int status() {
        return status;
    }

    /** A short, stable phrase for the class of failure -- not the per-occurrence detail, which is the message. */
    public String title() {
        return title;
    }

    /** Every diagnostic that produced this failure, empty for one that produced none. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** This failure as the body to write. */
    public TsonProblem problem() {
        return TsonProblem.of(status, title, getMessage(), diagnostics);
    }

    /** A document that read but did not validate -- the diagnostics say what was wrong with it. */
    public static TsonHttpException invalidDocument(List<Diagnostic> diagnostics) {
        return new TsonHttpException(BAD_REQUEST, "Invalid TSON document",
                diagnostics.size() == 1 ? "the request body has 1 problem"
                        : "the request body has " + diagnostics.size() + " problems",
                diagnostics, null);
    }

    /** The request body is not something this handler can read. */
    public static TsonHttpException unsupportedMediaType(String detail) {
        return new TsonHttpException(UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", detail, List.of(), null);
    }

    /** The client's {@code Accept} rules out the only representation this handler produces. */
    public static TsonHttpException notAcceptable(String detail) {
        return new TsonHttpException(NOT_ACCEPTABLE, "Not acceptable", detail, List.of(), null);
    }

    /**
     * Classifies an exception from the library into a status. See the class note for the table and for why the
     * gap row is the one that matters.
     *
     * @throws RuntimeException {@code e} itself, unclassified, when it is none of the four known kinds
     */
    public static TsonHttpException from(RuntimeException e) {
        return switch (e) {
            case TsonSchemaFetchException fetch -> switch (fetch.reason()) {
                case NOT_PERMITTED, NOT_FOUND -> new TsonHttpException(BAD_REQUEST, "Unusable schema reference",
                        fetch.getMessage(), List.of(), fetch);
                case TIMEOUT -> new TsonHttpException(GATEWAY_TIMEOUT, "Schema origin timed out",
                        fetch.getMessage(), List.of(), fetch);
                case TRANSPORT, TOO_LARGE -> new TsonHttpException(BAD_GATEWAY, "Schema origin failed",
                        fetch.getMessage(), List.of(), fetch);
            };
            case TsonReadException read -> new TsonHttpException(BAD_REQUEST, "Invalid TSON document",
                    read.getMessage(), List.of(read.diagnostic()), read);
            case TsonSchemaValidationException schema -> new TsonHttpException(BAD_REQUEST, "Invalid TSON schema",
                    schema.getMessage(), List.of(), schema);
            case UnsupportedOperationException gap -> new TsonHttpException(NOT_IMPLEMENTED, "Not implemented",
                    gap.getMessage(), List.of(), gap);
            case IllegalStateException fault -> new TsonHttpException(INTERNAL_SERVER_ERROR, "Internal error",
                    fault.getMessage(), List.of(), fault);
            // Not a fallthrough: ofBaseSyntaxError classifies the three base-syntax failures and rethrows
            // anything else, so an unclassified fault still leaves here as itself.
            default -> {
                Diagnostic syntax = Diagnostic.ofBaseSyntaxError(e);
                yield new TsonHttpException(BAD_REQUEST, "Malformed TSON document", e.getMessage(),
                        List.of(syntax), e);
            }
        };
    }
}
