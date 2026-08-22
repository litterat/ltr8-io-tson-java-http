package io.ltr8.tson.http.helidon;

import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblem;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * What a Helidon route is written as: a function from one {@link TsonContext} to its response.
 *
 * <pre>{@code
 * routing.post("/orders", TsonHandler.asHandler(codec, tson -> {
 *     Order order = tson.readObject(Order.class);       // validated, or 400 with every diagnostic
 *     tson.respond(201, store(order));
 * }));
 * }</pre>
 *
 * <p>The boundary does the same four things as the JDK and Javalin adapters', and for the same reasons: it
 * checks {@code Accept} <b>before</b> the handler runs, maps a failure through {@link TsonHttpException#from}
 * and nowhere else, never overwrites a response the handler has already sent, and gives a 5xx a body carrying
 * status and title and no detail. The exception goes to a {@link System.Logger}.
 *
 * <p><b>{@link #install} is for the routes that are not written this way</b> -- including one using
 * {@link TsonMediaSupport}, where the read happens inside Helidon's own entity machinery rather than in a
 * handler. It registers the same failure mapping as a routing error handler, so one application answers
 * failures one way.
 */
@FunctionalInterface
public interface TsonHandler {

    /** Handles one request. Throwing {@link TsonHttpException} is the ordinary way to answer with a failure. */
    void handle(TsonContext context) throws Exception;

    /** This handler as a Helidon {@link Handler}, with the error boundary in place. */
    static Handler asHandler(TsonHttpCodec codec, TsonHandler handler) {
        return (ServerRequest request, ServerResponse response) -> {
            TsonContext tson = new TsonContext(request, response, codec);
            try {
                codec.requireTsonAcceptable(tson.header("Accept"));
                handler.handle(tson);
                if (!tson.committed()) {
                    // A handler that returns without answering is a bug in the handler, not a request problem.
                    throw new IllegalStateException("handler returned without sending a response");
                }
            } catch (Exception e) {
                Boundary.fail(codec, tson, e);
            }
        };
    }

    /**
     * Registers this adapter's failure mapping on {@code routing}, so a {@link TsonHttpException} escaping any
     * handler -- including a plain Helidon one, or one reading through {@link TsonMediaSupport} -- becomes a
     * TSON problem body rather than Helidon's own error page. Routes built with {@link #asHandler} already
     * handle their own failures and are unaffected.
     */
    static void install(HttpRouting.Builder routing, TsonHttpCodec codec) {
        routing.error(TsonHttpException.class, (request, response, failure) ->
                Boundary.fail(codec, new TsonContext(request, response, codec), failure));
    }

    /** The shared failure rendering. Reached through {@link #asHandler} and {@link #install}. */
    final class Boundary {

        private static final Logger LOG = System.getLogger(TsonHandler.class.getName());

        private Boundary() {
        }

        static void fail(TsonHttpCodec codec, TsonContext tson, Exception failure) {
            TsonHttpException mapped = classify(failure);
            if (tson.committed()) {
                // Nothing can be sent: the handler already answered. Recording it is all that is left, and it
                // matters -- this is the case where a client sees a truncated body and no explanation.
                LOG.log(Level.ERROR, "failed after the response was already sent: " + tson.path(), failure);
                return;
            }
            if (mapped.status() >= 500) {
                LOG.log(Level.ERROR, mapped.status() + " handling " + tson.path(), failure);
            }
            tson.respondBytes(mapped.status(), codec.writeProblem(bodyFor(mapped)));
        }

        /**
         * {@code failure} as a status, through the codec's own policy. Anything the policy declines to classify
         * is a fault in this server, which is what it means for {@code from} to rethrow rather than guess.
         */
        private static TsonHttpException classify(Exception failure) {
            if (failure instanceof TsonHttpException already) {
                return already;
            }
            if (failure instanceof RuntimeException runtime) {
                try {
                    return TsonHttpException.from(runtime);
                } catch (RuntimeException unclassified) {
                    return internal(unclassified);
                }
            }
            return internal(failure);
        }

        private static TsonHttpException internal(Throwable cause) {
            return new TsonHttpException(TsonHttpException.INTERNAL_SERVER_ERROR, "Internal error", null,
                    List.of(), cause);
        }

        /** A 5xx body carries status and title only -- see the interface note on why the detail is dropped. */
        private static TsonProblem bodyFor(TsonHttpException failure) {
            return failure.status() >= 500
                    ? TsonProblem.of(failure.status(), failure.title(), null, List.of())
                    : failure.problem();
        }
    }
}
