package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An {@code api} read into its route table -- the reader-side half of the map design, and every check the
 * resolver cannot make because the api relates things by identifier.
 *
 * <p>For each path and verb: the endpoint's signature is resolved -- an {@link Operation}'s is inline, a
 * {@link Binding}'s is the method it names on an implemented interface (walking {@code extends};
 * {@code interface} disambiguates when two declare the name) -- its {@link Placement} is computed against that
 * signature's request record, and its verb is checked against what the method says about itself. A method name
 * no implemented interface declares is refused.
 *
 * <p><b>Coverage is per (interface, method), never per name.</b> Two implemented interfaces may both declare
 * {@code get_order}, and binding one of them says nothing about the other -- so {@link Bound} is the unit
 * throughout, and an {@code exemption} that does not name its interface is refused where the name is
 * ambiguous rather than quietly covering both. The flat-name version of this check is the cost the map design
 * exists to remove, reappearing in the reader.
 *
 * <p>{@link #requireComplete()} holds the api to its {@code implements} claim: every method of every
 * implemented interface is bound by some binding, or exempted in {@code not_bound} with a reason.
 */
record Routes(String apiName, List<Route> routes, Set<Bound> claimed, Map<String, Exemption> notBound) {

    /** One method of one interface: the unit the {@code implements} claim is held in. */
    record Bound(String owner, String name) {
        @Override
        public String toString() {
            return owner + "." + name;
        }
    }

    /** One resolved endpoint. {@code method} is empty for an {@link Operation}. */
    record Route(HttpVerb verb, String path, Optional<Bound> method, Optional<TypeRef> request,
                 Optional<TypeRef> response, List<TypeRef> errors, int status, Placement placement) {
    }

    static Routes of(String apiName, Api api, Function<String, TypeDefinition> entries) {
        Map<String, Interface> interfaces = new LinkedHashMap<>();
        for (String name : api.implemented()) {
            collect(apiName, name, entries, interfaces, new LinkedHashSet<>());
        }
        List<Route> routes = new ArrayList<>();
        api.resources().forEach((path, resource) -> resource.endpoints().forEach((verbKey, endpoint) -> {
            String label = apiName + " " + verbKey + " " + path;
            HttpVerb verb;
            try {
                verb = HttpVerb.valueOf(verbKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + label + "': '" + verbKey + "' is not an HTTP verb", e);
            }
            // The tag decided which this is; the sealed type carries that decision here.
            Optional<Bound> method;
            Optional<TypeRef> request;
            Optional<TypeRef> response;
            List<TypeRef> errors;
            Annotations facts;
            switch (endpoint) {
                case Operation op -> {
                    method = Optional.empty();
                    request = op.request();
                    response = op.response();
                    errors = op.errors();
                    // An operation has no method to carry them, so its facts ride on its own verb key.
                    facts = resource.endpoints().getAnnotations(verbKey);
                }
                case Binding b -> {
                    Bound bound = methodNamed(label, b, interfaces);
                    Method m = interfaces.get(bound.owner()).methods().get(bound.name());
                    method = Optional.of(bound);
                    request = m.request();
                    response = m.response();
                    errors = m.errors();
                    facts = interfaces.get(bound.owner()).methods().getAnnotations(bound.name());
                }
            }
            Placement placement = Placement.of(label, verb, path, endpoint.query(), endpoint.headers(),
                    endpoint.body(), request, entries);
            requireVerbAgrees(label, verb, facts);
            requireStatusAgrees(label, endpoint.status(), response);
            routes.add(new Route(verb, path, method, request, response, errors, endpoint.status(), placement));
        }));
        Set<Bound> claimed = new LinkedHashSet<>();
        interfaces.forEach((name, iface) -> iface.methods().keySet().forEach(m -> claimed.add(new Bound(name, m))));
        return new Routes(apiName, List.copyOf(routes), claimed, api.notBound());
    }

    /**
     * Every claimed method is bound or exempted -- the api's {@code implements} held to.
     *
     * <p>An exemption is checked as hard as a binding: one naming a method no implemented interface declares is
     * a typo the reader catches, and one whose name two of them declare must say which with {@code interface:}.
     * Both would otherwise pass as an exemption that covers nothing, or one that covers more than was meant.
     */
    Routes requireComplete() {
        Set<Bound> bound = new LinkedHashSet<>();
        routes.forEach(r -> r.method().ifPresent(bound::add));

        notBound.forEach((name, exemption) -> {
            List<Bound> matching = claimed.stream().filter(b -> b.name().equals(name))
                    .filter(b -> exemption.owner().map(b.owner()::equals).orElse(true)).toList();
            if (matching.isEmpty()) {
                throw new IllegalStateException("'" + apiName + "' exempts '" + name + "', which no implemented "
                        + "interface declares -- claimed: " + claimed);
            }
            if (matching.size() > 1) {
                throw new IllegalStateException("'" + apiName + "' exempts '" + name + "', which " + matching
                        + " all declare; say which with `interface:`");
            }
        });

        List<Bound> missing = claimed.stream().filter(b -> !bound.contains(b) && !exempt(b)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("'" + apiName + "' implements interfaces declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in not_bound with a reason");
        }
        return this;
    }

    private boolean exempt(Bound method) {
        Exemption exemption = notBound.get(method.name());
        return exemption != null && exemption.owner().map(method.owner()::equals).orElse(true);
    }

    Optional<Route> route(HttpVerb verb, String path) {
        return routes.stream().filter(r -> r.verb() == verb && r.path().equals(path)).findFirst();
    }

    /**
     * The projection checks its verb against what the method says about itself, and never derives one from the
     * other ({@code @safe} implies {@code @idempotent}, so a safe method may be bound to PUT or DELETE). A verb
     * whose contract the method does not claim is the one mismatch a client cannot see and a cache will act on.
     */
    private static void requireVerbAgrees(String label, HttpVerb verb, Annotations facts) {
        boolean safe = facts.has("safe");
        boolean idempotent = safe || facts.has("idempotent");
        switch (verb) {
            case GET, HEAD, OPTIONS -> require(safe, label, verb, "safe");
            case PUT, DELETE -> require(idempotent, label, verb, "idempotent");
            case POST, PATCH -> { }
        }
    }

    private static void require(boolean holds, String label, HttpVerb verb, String what) {
        if (!holds) {
            throw new IllegalArgumentException("'" + label + "': " + verb + " is " + what + ", but the method is "
                    + "not marked @" + what + " -- mark it, or bind it to a verb that claims less");
        }
    }

    /** A response-less endpoint answers with no body, and 200 says otherwise. */
    private static void requireStatusAgrees(String label, int status, Optional<TypeRef> response) {
        if (response.isEmpty() && !EMPTY_STATUSES.contains(status)) {
            throw new IllegalArgumentException("'" + label + "': the signature declares no response, so the status "
                    + "must be one that carries no body " + EMPTY_STATUSES + ", not " + status);
        }
    }

    private static final Set<Integer> EMPTY_STATUSES = Set.of(204, 205, 304);

    private static void collect(String apiName, String name, Function<String, TypeDefinition> entries,
                                Map<String, Interface> into, Set<String> walking) {
        if (into.containsKey(name)) {
            return;
        }
        if (!walking.add(name)) {
            throw new IllegalArgumentException("'" + apiName + "': interface '" + name + "' extends itself through "
                    + walking);
        }
        TypeDefinition definition = entries.apply(name);
        if (definition == null || !(definition.body() instanceof Interface iface)) {
            throw new IllegalArgumentException("'" + apiName + "' names '" + name
                    + "', which is not an interface in this namespace");
        }
        for (String parent : iface.extended()) {
            collect(apiName, parent, entries, into, walking);
        }
        into.put(name, iface);
    }

    private static Bound methodNamed(String label, Binding binding, Map<String, Interface> interfaces) {
        String name = binding.method();
        List<Bound> found = new ArrayList<>();
        interfaces.forEach((ifaceName, iface) -> {
            if (iface.methods().get(name) != null && binding.owner().map(ifaceName::equals).orElse(true)) {
                found.add(new Bound(ifaceName, name));
            }
        });
        if (found.isEmpty()) {
            throw new IllegalArgumentException("'" + label + "' names method '" + name + "', which no implemented "
                    + "interface declares -- implemented: " + interfaces.keySet());
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException("'" + label + "' names method '" + name + "', which " + found
                    + " all declare; say which with `interface:`");
        }
        return found.getFirst();
    }
}
