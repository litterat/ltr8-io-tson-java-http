package io.ltr8.tson.http.javalin;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblem;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * What a Javalin route is written as: a function from one {@link TsonContext} to its response.
 *
 * <pre>{@code
 * app.post("/orders", TsonHandler.asHandler(codec, tson -> {
 *     Order order = tson.readObject(Order.class);       // validated, or 400 with every diagnostic
 *     tson.respond(201, store(order));
 * }));
 * }</pre>
 *
 * <p>The boundary does the same four things as {@code tson-http-jdk}'s, and for the same reasons: it checks
 * {@code Accept} <b>before</b> the handler runs, maps a failure through {@link TsonHttpException#from} and
 * nowhere else, never overwrites a response the handler has already sent, and gives a 5xx a body carrying
 * status and title and no detail -- an internal message can name a class, a path or an internal host, and a
 * client is not the audience. The exception goes to a {@link System.Logger}, so this module needs no logging
 * dependency of its own beyond the one Javalin already brings.
 *
 * <p><b>{@link #install} is for the routes that are not written this way.</b> A real application mixes TSON
 * routes with plain Javalin ones, and a {@link TsonHttpException} thrown from a service layer inside a plain
 * handler would otherwise get Javalin's own error page. Installing maps it to the same TSON problem body, so
 * one application answers failures one way.
 */
@FunctionalInterface
public interface TsonHandler {

    /** Handles one request. Throwing {@link TsonHttpException} is the ordinary way to answer with a failure. */
    void handle(TsonContext context) throws Exception;

    /** This handler as a Javalin {@link Handler}, with the error boundary in place. */
    static Handler asHandler(TsonHttpCodec codec, TsonHandler handler) {
        return context -> {
            TsonContext tson = new TsonContext(context, codec);
            try {
                codec.requireTsonAcceptable(tson.header("Accept"));
                handler.handle(tson);
                if (!tson.committed()) {
                    // A handler that returns without answering is a bug in the handler, not a request problem.
                    // Javalin would otherwise send an empty 200, which hides it.
                    throw new IllegalStateException("handler returned without sending a response");
                }
            } catch (Exception e) {
                Boundary.fail(codec, tson, e);
            }
        };
    }

    /**
     * Registers this adapter's failure mapping on {@code app}, so a {@link TsonHttpException} escaping any
     * handler -- including a plain Javalin one -- becomes a TSON problem body rather than Javalin's own error
     * page. Routes built with {@link #asHandler} already handle their own failures and are unaffected.
     */
    static void install(Javalin app, TsonHttpCodec codec) {
        app.exception(TsonHttpException.class, (failure, context) ->
                Boundary.fail(codec, new TsonContext(context, codec), failure));
    }

    /** The shared failure rendering. Package-private: reached through {@link #asHandler} and {@link #install}. */
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
            // The type survives redaction: it classifies the failure and carries nothing internal, so a client
            // can still tell a schema-origin outage from a bug in this server.
            return failure.status() >= 500
                    ? TsonProblem.of(failure.type(), failure.status(), failure.title(), null, List.of())
                    : failure.problem();
        }
    }
}
