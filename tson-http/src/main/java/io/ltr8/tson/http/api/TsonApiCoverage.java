package io.ltr8.tson.http.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Holds a server to its own description at startup: every endpoint the description declares must have a handler,
 * and every handler must answer to an endpoint the description declares.
 *
 * <h2>Why this is a startup failure and not a test</h2>
 *
 * <p>A server that publishes a description of itself is publishing a contract. An endpoint declared there and
 * served by nobody is a promise the server does not keep, and it fails at the worst moment — for a client that
 * read the contract and believed it. Both halves are known before a request exists, so the mistake is
 * knowable at startup, which is the only place worth finding it.
 *
 * <p>The reverse catches drift the other way: a route added to the server and never described, or an endpoint
 * moved in the description while the handler still claims the old one. {@link #serving} refuses an endpoint the
 * description does not declare, so that one fails where it is written.
 *
 * <h2>A claim names the declared verb and path, and is not path equality</h2>
 *
 * <p>An endpoint has no name of its own -- it is a value under a verb under a path -- so a handler claims it by
 * both, spelled as the description wrote them: {@code serving(POST, "/orders")}. That is a claim on the
 * description's own template string, not a comparison with the path the framework registers, which is allowed to
 * differ and often must. The JDK demo serves {@code /{+schemaPath}} from a {@code createContext("/")} prefix;
 * Javalin needs {@code /<path>}, an identity path having slashes that only the angle form matches across.
 * Checking registered paths for equality would demand a translation table per framework, and a wrong entry there
 * is a route that silently never matches -- a worse failure than the one being prevented.
 *
 * <pre>{@code
 * TsonApiCoverage coverage = TsonApiCoverage.of(described);
 *
 * Route create = coverage.serving(HttpVerb.POST, "/orders");
 * server.createContext(create.path(), handler);          // the description's own path, where it fits
 *
 * coverage.serving(HttpVerb.GET, "/{+schemaPath}");
 * server.createContext("/", schemaHandler);              // a prefix, where it does not
 *
 * coverage.requireComplete();
 * }</pre>
 *
 * <p>Whether every method of an implemented interface has an endpoint is a different question, about the
 * description rather than the server, and {@link TsonApiDescription} answers it when it reads one.
 *
 * <h2>The opinion has a way out</h2>
 *
 * <p>"Everything declared is served" is an opinion, and a fixed one would be the wrong kind of helper. The
 * exceptions are real — an endpoint documented ahead of being built, or one another process answers behind
 * the same proxy — and a check that cannot express them gets turned off wholesale, taking with it the
 * endpoints it was right about. {@link #notServedHere} is the way out, and it takes a reason: an exemption
 * that has to be justified in a string somebody can grep is a decision, where a boolean is a hole.
 */
public final class TsonApiCoverage {

    private final TsonApiDescription description;
    private final Set<String> served = new LinkedHashSet<>();
    private final Map<String, String> exempt = new LinkedHashMap<>();

    private TsonApiCoverage(TsonApiDescription description) {
        this.description = description;
    }

    /** Holds {@code description}'s endpoints to the handlers a server is about to register. */
    public static TsonApiCoverage of(TsonApiDescription description) {
        return new TsonApiCoverage(description);
    }

    /**
     * Claims the endpoint at {@code verb} and the declared {@code path}, and hands it back so its path and its
     * encodings can drive the registration rather than being written out again.
     *
     * @throws IllegalArgumentException if the description declares no such endpoint — a typo, or a handler
     *                                  outliving the endpoint it was written for
     * @throws IllegalStateException    if it has already been claimed, which means two handlers answer for
     *                                  one endpoint and only one of them can be reached
     */
    public Route serving(HttpVerb verb, String path) {
        Route route = requireDeclared(verb, path);
        String key = route.key();
        if (exempt.containsKey(key)) {
            throw new IllegalStateException("'" + key + "' was declared not served here (" + exempt.get(key)
                    + "); it cannot also be served");
        }
        if (!served.add(key)) {
            throw new IllegalStateException("'" + key + "' is already served -- two handlers for one endpoint, "
                    + "and only one of them can be reached");
        }
        return route;
    }

    private Route requireDeclared(HttpVerb verb, String path) {
        return description.route(verb, path).orElseThrow(() -> new IllegalArgumentException("'"
                + Route.key(verb, path) + "' is not an endpoint " + description.schemaId() + " declares; it "
                + "declares " + declared()));
    }

    private Set<String> declared() {
        Set<String> keys = new TreeSet<>();
        description.routes().forEach(route -> keys.add(route.key()));
        return keys;
    }

    /**
     * Declares that this server deliberately does not serve the endpoint at {@code verb} and {@code path}, and why.
     *
     * <p>{@code reason} is required and is not decoration. It is what makes an exemption greppable and
     * reviewable, and what distinguishes a decision from a hole someone punched to get a build green. The
     * exemptions are readable back through {@link #exemptions()}, so a server can publish or log what it
     * declares and does not serve rather than leaving a client to discover it by 404.
     *
     * @throws IllegalArgumentException if the description declares no such endpoint, or {@code reason} is blank
     * @throws IllegalStateException    if it has already been claimed by {@link #serving}
     */
    public TsonApiCoverage notServedHere(HttpVerb verb, String path, String reason) {
        String key = requireDeclared(verb, path).key();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("exempting '" + key + "' needs a reason -- an exemption without one "
                    + "is indistinguishable from a hole punched to get a build green");
        }
        if (served.contains(key)) {
            throw new IllegalStateException("'" + key + "' is served; it cannot also be exempt");
        }
        exempt.put(key, reason);
        return this;
    }

    /** What this server declares and deliberately does not serve, by verb and path, with the reason for each. */
    public Map<String, String> exemptions() {
        return Map.copyOf(exempt);
    }

    /** The endpoints this description declares that nothing has claimed and nothing has exempted. */
    public Set<String> unserved() {
        Set<String> missing = new LinkedHashSet<>();
        description.routes().forEach(route -> missing.add(route.key()));
        missing.removeAll(served);
        missing.removeAll(exempt.keySet());
        return missing;
    }

    /**
     * Fails unless every declared endpoint has been claimed — the last line of a server's startup.
     *
     * @throws IllegalStateException naming what is declared and unserved
     */
    public void requireComplete() {
        Set<String> missing = unserved();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(description.schemaId() + " declares " + missing.size()
                    + " endpoint(s) this server does not handle: " + new TreeSet<>(missing)
                    + " -- publishing a description promises them. Serve them, or say why not with "
                    + "notServedHere(verb, path, reason)");
        }
    }
}
