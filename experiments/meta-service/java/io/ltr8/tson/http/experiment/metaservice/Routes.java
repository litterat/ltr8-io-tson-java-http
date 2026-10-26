package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.Top;
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
 * An {@code api} read into its route table -- the reader-side half of the map design, and every check that relates
 * two things rather than naming one.
 *
 * <p>Naming is the resolver's: {@code implements} and {@code extends} are {@code ref<interface>}, and a binding's
 * or an exemption's {@code method} is {@code ref<method>}, so by the time a description reaches here each names
 * an interface or a method that exists, and a qualified name ({@code orders.orders.methods.get_order}) says which
 * interface declares it. What is left is relational: a bound method belongs to an interface the api implements
 * (walking {@code extends}); each endpoint's {@link Placement} is computed against its signature's request
 * record; its verb is checked against what the method says about itself, and its status against the response.
 *
 * <p><b>Coverage is per (interface, method), never per name.</b> Two implemented interfaces may both declare
 * {@code get_order}, and binding one of them says nothing about the other -- so {@link Bound} is the unit
 * throughout, and it is what a reference spells.
 *
 * <p>{@link #requireComplete()} holds the api to its {@code implements} claim: every method of every
 * implemented interface is bound by some binding, or exempted in {@code not_bound} with a reason.
 */
record Routes(String apiName, List<Route> routes, Set<Bound> claimed, List<Exemption> notBound) {

    /**
     * One method of one interface: the unit the {@code implements} claim is held in. {@code owner} is the
     * interface's qualified name, and the string form is the reference that names the method.
     */
    record Bound(String owner, String name) {

        private static final String METHODS = ".methods.";

        /** The method a {@code ref<method>} names: the interface it walks through, and the key it ends at. */
        static Bound of(TypeRef reference) {
            String written = reference.name();
            int at = written.lastIndexOf(METHODS);
            if (at < 0) {
                throw new IllegalStateException("'" + written + "' reaches a method other than through an "
                        + "interface's methods, which this layer declares no way to do");
            }
            return new Bound(written.substring(0, at), written.substring(at + METHODS.length()));
        }

        @Override
        public String toString() {
            return owner + METHODS + name;
        }
    }

    /** One resolved endpoint. {@code method} is empty for an {@link Operation}. */
    record Route(HttpVerb verb, String path, Optional<Bound> method, Optional<TypeRef> request,
                 Optional<TypeRef> response, List<TypeRef> errors, int status, Placement placement) {
    }

    /**
     * {@code declared} holds every interface the api may reach, keyed by qualified name
     * ({@link Experiment#heldByName}).
     */
    static Routes of(String apiName, Api api, Map<String, Interface> declared, Function<String, Top> entries) {
        Map<String, Interface> interfaces = new LinkedHashMap<>();
        for (TypeRef implemented : api.implemented()) {
            collect(apiName, implemented.name(), declared, interfaces, new LinkedHashSet<>());
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
                    Bound bound = implementedMethod(label, b, interfaces);
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
     * <p>An exemption is held as hard as a binding: the resolver judged that it names a method, and one naming a
     * method of an interface the api does not implement exempts nothing it claims, so it is refused rather than
     * passing as an exemption that covers nothing.
     */
    Routes requireComplete() {
        Set<Bound> bound = new LinkedHashSet<>();
        routes.forEach(r -> r.method().ifPresent(bound::add));

        Set<Bound> exempt = new LinkedHashSet<>();
        for (Exemption exemption : notBound) {
            Bound method = Bound.of(exemption.method());
            if (!claimed.contains(method)) {
                throw new IllegalStateException("'" + apiName + "' exempts '" + method + "', which is not a method "
                        + "of an interface it implements -- claimed: " + claimed);
            }
            exempt.add(method);
        }

        List<Bound> missing = claimed.stream().filter(b -> !bound.contains(b) && !exempt.contains(b)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("'" + apiName + "' implements interfaces declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in not_bound with a reason");
        }
        return this;
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

    /**
     * The implemented interfaces, through {@code extends}. Each name is a reference the resolver judged, so a
     * missing one is an interface the caller did not hand over, not an author's typo; the cycle is the one thing
     * a reference cannot rule out.
     */
    private static void collect(String apiName, String name, Map<String, Interface> declared,
                                Map<String, Interface> into, Set<String> walking) {
        if (into.containsKey(name)) {
            return;
        }
        if (!walking.add(name)) {
            throw new IllegalArgumentException("'" + apiName + "': interface '" + name + "' extends itself through "
                    + walking);
        }
        Interface iface = declared.get(name);
        if (iface == null) {
            throw new IllegalStateException("'" + apiName + "' reaches interface '" + name + "', which is not among "
                    + "the interfaces handed to the reader: " + declared.keySet());
        }
        for (TypeRef parent : iface.extended()) {
            collect(apiName, parent.name(), declared, into, walking);
        }
        into.put(name, iface);
    }

    /** The method a binding names, which must belong to an interface the api implements. */
    private static Bound implementedMethod(String label, Binding binding, Map<String, Interface> interfaces) {
        Bound bound = Bound.of(binding.method());
        if (!interfaces.containsKey(bound.owner())) {
            throw new IllegalArgumentException("'" + label + "' binds '" + bound + "', a method of an interface "
                    + "this api does not implement -- implemented: " + interfaces.keySet());
        }
        return bound;
    }
}
