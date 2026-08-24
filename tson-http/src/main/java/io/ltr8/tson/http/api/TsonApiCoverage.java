package io.ltr8.tson.http.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
 *
 * <h2>The opinion has a way out</h2>
 *
 * <p>"Everything declared is served" is an opinion, and a fixed one would be the wrong kind of helper. The
 * exceptions are real — an operation documented ahead of being built, or one another process answers behind
 * the same proxy — and a check that cannot express them gets turned off wholesale, taking with it the
 * operations it was right about. {@link #notServedHere} is the way out, and it takes a reason: an exemption
 * that has to be justified in a string somebody can grep is a decision, where a boolean is a hole.
 */
public final class TsonApiCoverage {

    private final TsonApiDescription description;
    private final Set<String> served = new LinkedHashSet<>();
    private final Map<String, String> exempt = new LinkedHashMap<>();

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
        Operation operation = requireDeclared(operationName);
        if (exempt.containsKey(operationName)) {
            throw new IllegalStateException("'" + operationName + "' was declared not served here ("
                    + exempt.get(operationName) + "); it cannot also be served");
        }
        if (!served.add(operationName)) {
            throw new IllegalStateException("'" + operationName + "' is already served -- two handlers for "
                    + "one operation, and only one of them can be reached");
        }
        return operation;
    }

    private Operation requireDeclared(String operationName) {
        Operation operation = description.operations().get(operationName);
        if (operation == null) {
            throw new IllegalArgumentException("'" + operationName + "' is not an operation "
                    + description.schemaId() + " declares; it declares "
                    + new TreeSet<>(description.operations().keySet()));
        }
        return operation;
    }

    /**
     * Declares that this server deliberately does not serve {@code operationName}, and why.
     *
     * <p><b>The opinion here is that a declared operation is served, and this is the way out of it.</b> A
     * fixed opinion with no way out is the wrong kind of helper: the cases are real — an endpoint documented
     * ahead of being built, or one another process answers behind the same proxy — and a check that cannot
     * express them gets switched off wholesale instead, taking the operations it was right about with it.
     *
     * <p>{@code reason} is required and is not decoration. It is what makes an exemption greppable and
     * reviewable, and what distinguishes a decision from a hole someone punched to get a build green. The
     * exemptions are readable back through {@link #exemptions()}, so a server can publish or log what it
     * declares and does not serve rather than leaving a client to discover it by 404.
     *
     * @throws IllegalArgumentException if the description declares no such operation, or {@code reason} is
     *                                  blank
     * @throws IllegalStateException    if it has already been claimed by {@link #serving}
     */
    public TsonApiCoverage notServedHere(String operationName, String reason) {
        requireDeclared(operationName);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("exempting '" + operationName + "' needs a reason -- an "
                    + "exemption without one is indistinguishable from a hole punched to get a build green");
        }
        if (served.contains(operationName)) {
            throw new IllegalStateException("'" + operationName + "' is served; it cannot also be exempt");
        }
        exempt.put(operationName, reason);
        return this;
    }

    /** What this server declares and deliberately does not serve, with the reason given for each. */
    public Map<String, String> exemptions() {
        return Map.copyOf(exempt);
    }

    /** The operations this description declares that nothing has claimed and nothing has exempted. */
    public Set<String> unserved() {
        Set<String> missing = new LinkedHashSet<>(description.operations().keySet());
        missing.removeAll(served);
        missing.removeAll(exempt.keySet());
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
                    + " -- publishing a description promises them. Serve them, or say why not with "
                    + "notServedHere(name, reason)");
        }
    }
}
