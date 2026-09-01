package io.ltr8.tson.http.experiment.metaservice;

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
 * {@code interface} disambiguates when two declare the name) -- and its {@link Placement} computed against that
 * signature's request record. A method name no implemented interface declares is refused.
 *
 * <p>{@link #requireComplete()} holds the api to its {@code implements} claim: every method of every implemented
 * interface is bound by some binding, or exempted in {@code not_bound} with a reason.
 */
record Routes(String apiName, List<Route> routes, Set<String> claimed, Map<String, String> notBound) {

    /** One resolved endpoint. {@code method} is empty for an {@link Operation}. */
    record Route(HttpVerb verb, String path, Optional<String> method, Optional<TypeRef> request,
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
            Optional<String> method;
            Optional<TypeRef> request;
            Optional<TypeRef> response;
            List<TypeRef> errors;
            switch (endpoint) {
                case Operation op -> {
                    method = Optional.empty();
                    request = op.request();
                    response = op.response();
                    errors = op.errors();
                }
                case Binding b -> {
                    Method m = methodNamed(label, b, interfaces);
                    method = Optional.of(b.method());
                    request = m.request();
                    response = m.response();
                    errors = m.errors();
                }
            }
            Placement placement = Placement.of(label, verb, path, endpoint.query(), endpoint.headers(),
                    endpoint.body(), request, entries);
            routes.add(new Route(verb, path, method, request, response, errors, endpoint.status(), placement));
        }));
        Set<String> claimed = new LinkedHashSet<>();
        interfaces.values().forEach(i -> claimed.addAll(i.methods().keySet()));
        return new Routes(apiName, List.copyOf(routes), claimed, api.notBound());
    }

    /** Every claimed method is bound or exempted -- the api's {@code implements} held to. */
    Routes requireComplete() {
        Set<String> bound = new LinkedHashSet<>();
        routes.forEach(r -> r.method().ifPresent(bound::add));
        List<String> missing = claimed.stream()
                .filter(m -> !bound.contains(m) && !notBound.containsKey(m)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("'" + apiName + "' implements interfaces declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in not_bound with a reason");
        }
        return this;
    }

    Optional<Route> route(HttpVerb verb, String path) {
        return routes.stream().filter(r -> r.verb() == verb && r.path().equals(path)).findFirst();
    }

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

    private static Method methodNamed(String label, Binding binding, Map<String, Interface> interfaces) {
        String name = binding.method();
        Map<String, Method> found = new LinkedHashMap<>();
        interfaces.forEach((ifaceName, iface) -> {
            Method m = iface.methods().get(name);
            if (m != null && binding.owner().map(ifaceName::equals).orElse(true)) {
                found.put(ifaceName, m);
            }
        });
        if (found.isEmpty()) {
            throw new IllegalArgumentException("'" + label + "' names method '" + name + "', which no implemented "
                    + "interface declares -- implemented: " + interfaces.keySet());
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException("'" + label + "' names method '" + name + "', which " + found.keySet()
                    + " all declare; say which with `interface:`");
        }
        return found.values().iterator().next();
    }
}
