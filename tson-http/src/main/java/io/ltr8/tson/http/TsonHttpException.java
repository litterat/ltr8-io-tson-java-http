package io.ltr8.tson.http;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.util.List;
import java.util.Locale;

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
 * <tr><td>{@link TsonSchemaFetchException}</td><td>see below -- 400, 502 or 504 by code</td><td>4xx/5xx</td></tr>
 * </table>
 *
 * <p><b>A fetch failure splits by whose fault it is.</b> A document naming a schema this server will not load, or
 * one that does not exist, is the document's problem: 400. A permitted origin that is unreachable, oversized or
 * slow is this server's dependency failing while the request was perfectly good, which is what 502 and 504 are
 * for. Collapsing these into one status would either blame a client for an outage or hide an outage as a client
 * error -- and the retry advice differs: a 400 says fix the document, a 502 says try again.
 *
 * <p><b>That row is now reached mostly at startup.</b> Every read through {@link TsonHttpCodec} collects, so a
 * fetch failure during a request is reported as one of the five {@code SCHEMA_*} codes rather than thrown --
 * leaving this branch to {@code preload} and {@code prepareToRead}, which do fail fast. Both channels answer
 * from {@link #fetchFailure}, this one mapping its {@code Reason} with {@link Diagnostic.Code#of} first, so the
 * agreement is a property of one table rather than something two tables are held to by test.
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
     *
     * <p>Under {@code ltr8.io}, not {@code tson.io}: a problem type is a fact about this implementation's
     * behaviour, where a schema identity under {@code tson.io} is a fact about the format. The two hosts keep
     * that apart -- the specification's, and the implementation resource that stands beside it.
     */
    public static final String TYPES = "https://ltr8.io/2026/34/http/problems/";

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
     *
     * <p><b>The five {@code SCHEMA_*} fetch codes are the third kind that is not a verdict</b>, and the ones
     * whose status depends on <em>whose</em> doing it was: {@link #fetchFailure} splits them across 400, 502 and
     * 504, and {@link #from} answers a thrown fetch failure from that same table. Nobody would supply the
     * schema, so the body was never read against one and whether it would have passed is unknown -- but a
     * reference this deployment refuses is still the sender's to fix, where a host that timed out is not. They
     * rank below a gap on upstream's own precedent: the CLI takes the most permanent of three, 70 over 69 over
     * 1, since retrying reaches a gap again where an origin may recover. Among themselves they rank by
     * {@link #FETCH_RANKING}.
     *
     * <p><b>A [TSON-DATA] §8.2 refusal is a 400 of its own type</b>, one per code, ranked below those three
     * and above an ordinary violation. It is still the sender's to fix, but the fix may be a rename, a
     * character, or a look at what this deployment admits -- and the type is what tells a client which. The
     * body carries nothing about the policy itself, for the reason the diagnostic carries no data version:
     * the level and the version are the processor's, stated once at {@code /.well-known/tson-deployment},
     * which is where the type's documentation sends a client. {@link #policyRefusal} carries it out.
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
        // Ranked below a gap on upstream's own precedent: its CLI takes "the most permanent of three", 70 over
        // 69 over 1, because retrying reaches the gap again where the origin may well come back. Within the
        // five, FETCH_RANKING orders them; the list is scanned in that order rather than in the document's,
        // which is what keeps a mixed failure's status independent of which reference came first.
        for (Diagnostic.Code code : FETCH_RANKING) {
            List<Diagnostic> unavailable = diagnostics.stream().filter(d -> d.code() == code).toList();
            if (!unavailable.isEmpty()) {
                return fetchFailure(code, "the schema governing the request body could not be obtained, so the "
                        + "body was not checked: " + unavailable.stream().map(Diagnostic::message).toList(),
                        diagnostics, cause);
            }
        }
        List<Diagnostic> refused = diagnostics.stream().filter(d -> isRefusal(d.code())).toList();
        if (!refused.isEmpty()) {
            return policyRefusal(refused, diagnostics, cause);
        }
        return new TsonHttpException(BAD_REQUEST, TYPES + "invalid-document", "Invalid TSON document", detail,
                diagnostics, cause);
    }

    /** [TSON-DATA] §8.2's three refusal codes -- one per rule, which is what lets each have a type of its own. */
    private static boolean isRefusal(Diagnostic.Code code) {
        return switch (code) {
            case CONFUSABLE_NAMES, RESTRICTED_CHARACTER, RESTRICTED_SCRIPT -> true;
            default -> false;
        };
    }

    /**
     * A refusal under this deployment's §8.2 policy, typed by the rule that fired.
     *
     * <p>The type is the code's own name in the {@link #TYPES} namespace ({@code …/restricted-script}), so
     * a client matching on it matches on the same three-way split it would route on in the body. <b>The
     * first refusal found decides</b>, and unlike the fetch codes these are deliberately not ranked: two rules
     * firing on one document is possible, all three are the same status, and the fix differs by rule rather
     * than in severity -- so there is no ordering between them that is right in general, where among the fetch
     * codes there is one and {@link #FETCH_RANKING} states it. A
     * mixed list -- a refusal beside ordinary violations -- takes the refusal's type, since it is the one class
     * where the fix may not be in the document, and says in {@code detail} that the rest are real.
     */
    private static TsonHttpException policyRefusal(List<Diagnostic> refused, List<Diagnostic> diagnostics,
                                                   Throwable cause) {
        Diagnostic.Code code = refused.getFirst().code();
        String detail = "refused under this deployment's name policy, published at /.well-known/tson-deployment: "
                + refused.stream().map(Diagnostic::message).toList()
                + (refused.size() == diagnostics.size() ? ""
                : "; the other " + (diagnostics.size() - refused.size()) + " problem(s) reported are real");
        return new TsonHttpException(BAD_REQUEST, TYPES + code.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                refusalTitle(code), detail, diagnostics, cause);
    }

    private static String refusalTitle(Diagnostic.Code code) {
        return switch (code) {
            case CONFUSABLE_NAMES -> "Names refused as confusable";
            case RESTRICTED_CHARACTER -> "Name refused: character outside the identifier profile";
            case RESTRICTED_SCRIPT -> "Name refused under script policy";
            default -> throw new IllegalArgumentException(code + " is not a refusal");
        };
    }

    /**
     * How the five fetch codes rank against each other, most permanent first.
     *
     * <p>The two that are the world's doing come before the three that are the document's, so a mixed
     * failure is never blamed on the client -- the same rule the surrounding chain applies to a gap. Between
     * those two, an origin answering with something that is not a document is less likely to right itself
     * than one that was merely slow. The three at the tail share a status and a type and so do not rank
     * among themselves; they are listed in {@link Diagnostic.Code} order.
     *
     * <p><b>Scanning in this order is what replaced a first-wins pick.</b> The status used to come from
     * whichever reason was reported first, so a document naming two bad references got an answer that
     * depended on which one the reader reached first -- and a 400 could beat a 5xx that way.
     */
    private static final List<Diagnostic.Code> FETCH_RANKING = List.of(
            Diagnostic.Code.SCHEMA_UNREACHABLE, Diagnostic.Code.SCHEMA_TIMEOUT,
            Diagnostic.Code.SCHEMA_NOT_PERMITTED, Diagnostic.Code.SCHEMA_NOT_FOUND,
            Diagnostic.Code.SCHEMA_TOO_LARGE);

    /**
     * A schema that could not be obtained, answered by <b>whose doing it was</b>.
     *
     * <p><b>This is the only fetch-failure status table.</b> Both channels route through it: a collected
     * diagnostic arrives with its code already, and {@link #from} maps a thrown {@link
     * TsonSchemaFetchException}'s {@code Reason} with {@link Diagnostic.Code#of}. One failure reaches a
     * consumer two ways -- thrown at startup, collected on every read through the codec -- and a consumer
     * picking a status has to get the same answer from both. Two tables over one vocabulary is how they
     * drifted before, {@code NOT_PERMITTED} answering 400 thrown and 502 collected.
     *
     * <p><b>The split is who must act.</b> A reference this deployment will not fetch, one nothing serves,
     * and one whose document exceeds the size a schema may be are all the document's own doing: it named
     * them, and it is the sender who can name something else, so all three are 400. An origin that was
     * unreachable or slow is this server's dependency failing while the request was perfectly good, which
     * is what 502 and 504 are for.
     *
     * <p><b>{@code SCHEMA_TOO_LARGE} is a 400 even though nothing was checked</b>, and it is the case that
     * shows the axis. {@link Diagnostic.Code#verdict()} reports all five as non-verdicts, correctly -- the
     * body went unread. But {@code verdict()} answers <em>was the document judged</em> and a status answers
     * <em>who must act</em>, and for a bad reference those differ. Retrying shrinks a schema no more than it
     * conjures a missing one, so the retry a 502 advertises would be false.
     */
    private static TsonHttpException fetchFailure(Diagnostic.Code code, String detail,
                                                  List<Diagnostic> diagnostics, Throwable cause) {
        return switch (code) {
            case SCHEMA_NOT_PERMITTED, SCHEMA_NOT_FOUND, SCHEMA_TOO_LARGE -> new TsonHttpException(BAD_REQUEST,
                    TYPES + "unusable-schema-reference", "Unusable schema reference", detail, diagnostics, cause);
            case SCHEMA_TIMEOUT -> new TsonHttpException(GATEWAY_TIMEOUT, TYPES + "schema-origin-timeout",
                    "Schema origin timed out", detail, diagnostics, cause);
            case SCHEMA_UNREACHABLE -> new TsonHttpException(BAD_GATEWAY, TYPES + "schema-origin-failed",
                    "Schema origin failed", detail, diagnostics, cause);
            default -> throw new IllegalArgumentException(code + " is not a fetch failure");
        };
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
            // Through Code.of into fetchFailure, so this channel and the collected one read one table
            // rather than two that agree by test.
            case TsonSchemaFetchException fetch -> fetchFailure(Diagnostic.Code.of(fetch.reason()),
                    fetch.getMessage(), List.of(), fetch);
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
