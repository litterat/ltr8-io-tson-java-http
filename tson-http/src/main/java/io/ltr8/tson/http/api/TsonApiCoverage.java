package io.ltr8.tson.http.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Holds a server to its own description at startup: every operation the description declares must have a
 * handler, and every handler must answer to an operation the description declares.
 *
 * <h2>Why this is a startup failure and not a test</h2>
 *
 * <p>A server that publishes a description of itself is publishing a contract. An operation declared there and
 * served by nobody is a promise the server does not keep, and it fails at the worst moment — for a client that
 * read the contract and believed it. Both halves are known before a request exists, so the mistake is
 * knowable at startup, which is the only place worth finding it.
 *
 * <p>The reverse catches drift the other way: a route added to the server and never described, or an operation
 * renamed in the description while the handler still names the old one. {@link #serving} refuses a name the
 * description does not declare, so that one fails where it is written.
 *
 * <h2>Coverage, not path equality — which is what keeps this framework-agnostic</h2>
 *
 * <p><b>The registered path and the declared path are allowed to differ, and often must.</b> The JDK demo
 * serves {@code /{schemaPath}} from a {@code createContext("/")} prefix; Javalin needs {@code /<path>} where
 * the description says {@code /{schemaPath}}, an identity path having slashes that only the angle form matches
 * across. Checking that the paths are *equal* would demand a translation table per framework, and a wrong
 * entry there means a route that silently never matches — a worse failure than the one being prevented.
 *
 * <p>So this checks only that each declared operation was claimed by somebody. The claim is made where the
 * route is registered, in whatever spelling that framework wants:
 *
 * <pre>{@code
 * TsonApiCoverage coverage = TsonApiCoverage.of(described);
 *
 * Operation create = coverage.serving("create_order");
 * server.createContext(create.path(), handler);          // the description's own path, where it fits
 *
 * coverage.serving("get_schema");
 * server.createContext("/", schemaHandler);              // a prefix, where it does not
 *
 * coverage.requireComplete();
 * }</pre>
 *
 * <p>{@link #serving} returning the operation is what stops the path being written twice: the description is
 * the source of truth for it wherever the framework can take it directly.
 */
public final class TsonApiCoverage {

    private final TsonApiDescription description;
    private final Set<String> served = new LinkedHashSet<>();

    private TsonApiCoverage(TsonApiDescription description) {
        this.description = description;
    }

    /** Holds {@code description}'s operations to the handlers a server is about to register. */
    public static TsonApiCoverage of(TsonApiDescription description) {
        return new TsonApiCoverage(description);
    }

    /**
     * Claims {@code operationName}, and hands back the operation so its method and path can drive the
     * registration rather than being written out again.
     *
     * @throws IllegalArgumentException if the description declares no such operation — a typo, or a handler
     *                                  outliving the operation it was written for
     * @throws IllegalStateException    if it has already been claimed, which means two handlers answer for
     *                                  one operation and only one of them can be reached
     */
    public Operation serving(String operationName) {
        Operation operation = description.operations().get(operationName);
        if (operation == null) {
            throw new IllegalArgumentException("'" + operationName + "' is not an operation "
                    + description.schemaId() + " declares; it declares "
                    + new TreeSet<>(description.operations().keySet()));
        }
        if (!served.add(operationName)) {
            throw new IllegalStateException("'" + operationName + "' is already served -- two handlers for "
                    + "one operation, and only one of them can be reached");
        }
        return operation;
    }

    /** The operations this description declares that nothing has claimed. */
    public Set<String> unserved() {
        Set<String> missing = new LinkedHashSet<>(description.operations().keySet());
        missing.removeAll(served);
        return missing;
    }

    /**
     * Fails unless every declared operation has been claimed — the last line of a server's startup.
     *
     * @throws IllegalStateException naming what is declared and unserved
     */
    public void requireComplete() {
        Set<String> missing = unserved();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(description.schemaId() + " declares " + missing.size()
                    + " operation(s) this server does not handle: " + new TreeSet<>(missing)
                    + " -- publishing a description promises them");
        }
    }
}
