package io.ltr8.tson.http;

import io.ltr8.tson.TsonSchemaFetchException;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
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
 * <tr><td>{@link TsonReadException}</td><td>this document breaks its schema, unless its code says
 * otherwise</td><td>400</td></tr>
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
 * the CLI rides exit 1 against exit 70 on it. Answering 400 for a gap tells a client to fix a document that is
 * not wrong, and sends them round a retry loop that cannot terminate. 501 says what is true: the request was
 * fine and this server cannot do it yet.
 *
 * <p><b>Which is why the exception type is not enough, and the row above carries a caveat.</b> A read gap no
 * longer travels as its own exception type: it is reported like any other problem, so it reaches a collecting
 * caller as a {@code NOT_IMPLEMENTED} diagnostic among the rest and a fail-fast one as a {@link
 * TsonReadException} carrying that same code. Both channels therefore hand this class a verdict and a gap in
 * one shape, and only {@link Diagnostic#code()} separates them -- so {@link #invalidDocument} asks the code,
 * and {@link #from} routes every {@code TsonReadException} through it rather than assuming a verdict. The
 * {@code UnsupportedOperationException} row survives for the gaps still raised outside a read.
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

    /**
     * Where this project's problem-type identifiers live. They dereference to nothing yet; the point today is
     * that they are stable and matchable where a title is prose.
     */
    public static final String TYPES = "https://tson.io/2026/33/ltr8/http/problems/";

    /** RFC 9457's own default: no semantics beyond the status code. */
    public static final String ABOUT_BLANK = "about:blank";

    private final int status;
    private final String type;
    private final String title;
    private final transient List<Diagnostic> diagnostics;

    /**
     * A failure with no more specific type than its status code. Prefer the overload naming one: {@code type}
     * is what a client matches on, and defaulting it silently is how it never gets set.
     */
    public TsonHttpException(int status, String title, String detail, List<Diagnostic> diagnostics, Throwable cause) {
        this(status, ABOUT_BLANK, title, detail, diagnostics, cause);
    }

    /**
     * @param type a URI reference identifying the class of failure (RFC 9457 {@code type}) -- stable, unlike
     *             {@code title}, which is prose
     */
    public TsonHttpException(int status, String type, String title, String detail, List<Diagnostic> diagnostics,
                             Throwable cause) {
        super(detail == null || detail.isBlank() ? title : detail, cause);
        this.status = status;
        this.type = type;
        this.title = title;
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** The class of failure, as a URI reference. See {@link #TYPES}. */
    public String type() {
        return type;
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
        return TsonProblem.of(type, status, title, getMessage(), diagnostics);
    }

    /**
     * A document that read but did not validate -- the diagnostics say what was wrong with it.
     *
     * <p><b>Unless one of them is a library gap, in which case this is a 501 and not a 400.</b> A gap now
     * travels as {@link Diagnostic.Code#NOT_IMPLEMENTED} in the same list as ordinary problems rather than
     * only as an exception, so that one unimplemented construct no longer costs the author every other
     * declaration's verdict. That is a better design and it moves a decision here: a list this method is
     * handed may be mixed.
     *
     * <p>A mixed list is a gap, deliberately, and this is the HTTP wearing of the rule the CLI rides its exit
     * codes on -- any {@code NOT_IMPLEMENTED} makes a run 70 rather than 1. The ordinary problems are real
     * and are still carried in the body, but something in the document went unchecked, so <em>"your request
     * was invalid"</em> is not a verdict this server is entitled to give. Telling a client 400 would have
     * them fix a request that may be perfectly good.
     *
     * <p><b>{@code BIND_MISMATCH} outranks both</b>, and is a 500. It is neither a verdict on the document
     * nor a gap in the library: a schema and the Java class bound to it disagree, which is this server's own
     * wiring and nothing the client can act on. It is checked first because it is the one an operator has to
     * fix, and the only one whose message names a server type -- which is why 5xx drops the body's detail.
     */
    public static TsonHttpException invalidDocument(List<Diagnostic> diagnostics) {
        return invalidDocument(diagnostics, diagnostics.size() == 1 ? "the request body has 1 problem"
                : "the request body has " + diagnostics.size() + " problems", null);
    }

    /**
     * The rule itself, shared by both channels a diagnostic can arrive through: collected into a list, or
     * carried by a single {@link TsonReadException} on a fail-fast read. Asking the <em>code</em> is what lets
     * one rule serve both -- the exception type no longer distinguishes a gap from a verdict, because a read
     * gap now travels as a {@code NOT_IMPLEMENTED} diagnostic whichever receiver is in use.
     *
     * @param detail what to say for the 400 case; the two 5xx cases write their own, since theirs is for a log
     * @param cause  the exception this came from, or {@code null} when the diagnostics were collected
     */
    private static TsonHttpException invalidDocument(List<Diagnostic> diagnostics, String detail, Throwable cause) {
        // This server's own wiring, and the most serious of the three for whoever runs it. Its message names
        // a server class, so the detail here exists to be logged: the adapter boundary drops both detail and
        // diagnostics from any 5xx body, which is what keeps it off the wire.
        List<Diagnostic> mismatches = diagnostics.stream()
                .filter(d -> d.code() == Diagnostic.Code.BIND_MISMATCH).toList();
        if (!mismatches.isEmpty()) {
            return new TsonHttpException(INTERNAL_SERVER_ERROR, TYPES + "internal-error",
                    "Internal server error", "a schema and the class bound to it disagree: " + mismatches
                            .stream().map(Diagnostic::message).toList(), diagnostics, cause);
        }
        long gaps = diagnostics.stream().filter(d -> d.code() == Diagnostic.Code.NOT_IMPLEMENTED).count();
        if (gaps > 0) {
            return new TsonHttpException(NOT_IMPLEMENTED, TYPES + "not-implemented", "Not implemented",
                    "this server's TSON library has not implemented a construct the request body uses, so the "
                            + "body could not be checked" + (gaps == diagnostics.size() ? ""
                            : "; the other " + (diagnostics.size() - gaps) + " problem(s) reported are real"),
                    diagnostics, cause);
        }
        return new TsonHttpException(BAD_REQUEST, TYPES + "invalid-document", "Invalid TSON document", detail,
                diagnostics, cause);
    }

    /** The request body is not something this handler can read. */
    public static TsonHttpException unsupportedMediaType(String detail) {
        return new TsonHttpException(UNSUPPORTED_MEDIA_TYPE, TYPES + "unsupported-media-type",
                "Unsupported media type", detail, List.of(), null);
    }

    /** The client's {@code Accept} rules out the only representation this handler produces. */
    public static TsonHttpException notAcceptable(String detail) {
        return new TsonHttpException(NOT_ACCEPTABLE, TYPES + "not-acceptable", "Not acceptable", detail,
                List.of(), null);
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
                case NOT_PERMITTED, NOT_FOUND -> new TsonHttpException(BAD_REQUEST,
                        TYPES + "unusable-schema-reference", "Unusable schema reference", fetch.getMessage(),
                        List.of(), fetch);
                case TIMEOUT -> new TsonHttpException(GATEWAY_TIMEOUT, TYPES + "schema-origin-timeout",
                        "Schema origin timed out", fetch.getMessage(), List.of(), fetch);
                case TRANSPORT, TOO_LARGE -> new TsonHttpException(BAD_GATEWAY, TYPES + "schema-origin-failed",
                        "Schema origin failed", fetch.getMessage(), List.of(), fetch);
            };
            // A misconfiguration of this server, not a fault in the request: the schema is fine and the class
            // is fine, and they have been pointed at each other by mistake. The client's document may be
            // perfectly valid, so 400 would send them to fix something that is not wrong -- and the message
            // names a server class, which is not a client's business. 5xx carries no detail for that reason.
            case TsonBindMismatchException mismatch -> new TsonHttpException(INTERNAL_SERVER_ERROR,
                    TYPES + "internal-error", "Internal server error", null, List.of(), mismatch);
            // Not unconditionally a 400: a fail-fast read reports a library gap through this same type, with
            // the code as the only thing telling the two apart. Routing on it is what keeps the two channels
            // answering alike -- see invalidDocument.
            case TsonReadException read -> invalidDocument(List.of(read.diagnostic()), read.getMessage(), read);
            case TsonSchemaValidationException schema -> new TsonHttpException(BAD_REQUEST,
                    TYPES + "invalid-schema", "Invalid TSON schema", schema.getMessage(), List.of(), schema);
            case UnsupportedOperationException gap -> new TsonHttpException(NOT_IMPLEMENTED,
                    TYPES + "not-implemented", "Not implemented", gap.getMessage(), List.of(), gap);
            case IllegalStateException fault -> new TsonHttpException(INTERNAL_SERVER_ERROR,
                    TYPES + "internal-error", "Internal error", fault.getMessage(), List.of(), fault);
            // Not a fallthrough: ofBaseSyntaxError classifies the three base-syntax failures and rethrows
            // anything else, so an unclassified fault still leaves here as itself.
            default -> {
                Diagnostic syntax = Diagnostic.ofBaseSyntaxError(e);
                yield new TsonHttpException(BAD_REQUEST, TYPES + "malformed-document", "Malformed TSON document",
                        e.getMessage(), List.of(syntax), e);
            }
        };
    }
}
