package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * What a route is written as: a function from one {@link TsonExchange} to its response.
 *
 * <p>{@link #asHttpHandler} wraps it in the error boundary and hands back an ordinary
 * {@link HttpHandler}, so a route is registered on a plain {@code com.sun.net.httpserver.HttpServer} with
 * nothing else in between:
 *
 * <pre>{@code
 * server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
 *     exchange.requireMethod("POST");
 *     Order order = exchange.readObject(Order.class);       // validated, or 400 with every diagnostic
 *     exchange.respond(201, store(order));
 * }));
 * }</pre>
 *
 * <h2>What the boundary does, so a handler does not have to</h2>
 *
 * <ul>
 *   <li><b>Checks {@code Accept} before the handler runs</b>, not after. Every route here produces TSON, so a
 *       client that will not take it should be told before the work is done -- and putting the check in the
 *       boundary is the only way it cannot be forgotten.</li>
 *   <li><b>Turns a failure into a status and a TSON problem body</b>, through {@link TsonHttpException#from},
 *       which is the whole policy. Nothing here re-decides what a status should be.</li>
 *   <li><b>Never overwrites a response that has already been sent.</b> Status and headers go out in one call,
 *       so once a handler has committed, a later failure cannot become a 500 -- attempting it would throw
 *       inside the boundary and lose the original. The exchange is closed and the failure logged instead.</li>
 *   <li><b>Closes the exchange</b>, always.</li>
 * </ul>
 *
 * <h2>A body says nothing about this deployment</h2>
 *
 * <p>An internal message can name a bound Java class, a path, an internal host or a query, and a client is not
 * the audience for any of it. <b>That is a rule about content and {@link TsonHttpException#problem()} applies
 * it</b> -- this boundary writes whatever it returns and makes no judgement of its own, which is what stops
 * one security decision existing as three near-copies, one per adapter.
 *
 * <p>It is not a rule about 5xx, which is the tempting shortcut and gets a 501 wrong: that status says the
 * request was fine and this server could not check it, so the violations the read <em>did</em> find are the
 * client's to act on and are carried. A 500 and a 502/504 do answer with status, type and title alone.
 *
 * <p>The full exception goes to a {@link System.Logger} named after this class either way, which is where an
 * operator should look -- and using {@code System.Logger} keeps that true without this module taking a logging
 * dependency, which it is not allowed to have.
 *
 * <p>A 4xx is the opposite: its detail and diagnostics are the entire point, since the client is the one who
 * can act on them.
 */
@FunctionalInterface
public interface TsonHandler {

    /** Handles one request. Throwing {@link TsonHttpException} is the ordinary way to answer with a failure. */
    void handle(TsonExchange exchange) throws IOException;

    /** This handler as a JDK {@link HttpHandler}, with the error boundary in place. */
    static HttpHandler asHttpHandler(TsonHttpCodec codec, TsonHandler handler) {
        return new Boundary(codec, handler);
    }

    /** The error boundary. Package-private: it is reached through {@link #asHttpHandler}, never named. */
    final class Boundary implements HttpHandler {

        private static final Logger LOG = System.getLogger(TsonHandler.class.getName());

        private final TsonHttpCodec codec;
        private final TsonHandler handler;

        Boundary(TsonHttpCodec codec, TsonHandler handler) {
            this.codec = codec;
            this.handler = handler;
        }

        @Override
        public void handle(HttpExchange http) {
            TsonExchange exchange = new TsonExchange(http, codec);
            try (http) {
                try {
                    codec.requireTsonAcceptable(exchange.header("Accept"));
                    handler.handle(exchange);
                    if (!exchange.committed()) {
                        // A handler that returns without answering is a bug in the handler, not a request
                        // problem -- so it is a 500, and saying so beats a silent empty 200.
                        throw new IllegalStateException("handler returned without sending a response");
                    }
                } catch (Exception e) {
                    fail(exchange, e);
                }
            }
        }

        private void fail(TsonExchange exchange, Exception failure) {
            TsonHttpException mapped = classify(failure);
            if (exchange.committed()) {
                // Nothing can be sent: the status went out with the headers. Recording it is all that is left,
                // and it matters -- this is the case where a client sees a truncated body and no explanation.
                LOG.log(Level.ERROR, "failed after the response was already sent: " + exchange.uri(), failure);
                return;
            }
            if (mapped.status() >= 500) {
                LOG.log(Level.ERROR, mapped.status() + " handling " + exchange.uri(), failure);
            }
            exchange.respondBytes(mapped.status(), codec.writeProblem(mapped.problem()));
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
    }
}
