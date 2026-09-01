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
 * <p>For each path and verb: the operation's signature is resolved -- inline, or from the method it names on an
 * implemented interface (walking {@code extends}; {@code interface} disambiguates when two declare the name) --
 * and its {@link Placement} computed against that signature's request record. An operation with both a method
 * and an inline signature, or neither, is refused; so is a method name no implemented interface declares.
 *
 * <p>{@link #requireComplete()} holds the api to its {@code implements} claim: every method of every implemented
 * interface is bound by some operation, or exempted in {@code not_bound} with a reason.
 */
record Routes(String apiName, List<Route> routes, Set<String> claimed, Map<String, String> notBound) {

    /** One resolved operation. {@code method} is empty for an inline one. */
    record Route(HttpVerb verb, String path, Optional<String> method, Optional<TypeRef> request,
                 Optional<TypeRef> response, List<TypeRef> errors, int status, Placement placement) {
    }

    static Routes of(String apiName, Api api, Function<String, TypeDefinition> entries) {
        Map<String, Interface> interfaces = new LinkedHashMap<>();
        for (String name : api.implemented()) {
            collect(apiName, name, entries, interfaces, new LinkedHashSet<>());
        }
        List<Route> routes = new ArrayList<>();
        api.resources().forEach((path, resource) -> resource.operations().forEach((verbKey, op) -> {
            String label = apiName + " " + verbKey + " " + path;
            HttpVerb verb;
            try {
                verb = HttpVerb.valueOf(verbKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + label + "': '" + verbKey + "' is not an HTTP verb", e);
            }
            if (op.method().isPresent() == op.isInline()) {
                throw new IllegalArgumentException("'" + label + "' must name a method or carry a signature "
                        + "inline, and not both: method=" + op.method() + ", inline request=" + op.request());
            }
            Optional<TypeRef> request = op.request();
            Optional<TypeRef> response = op.response();
            List<TypeRef> errors = op.errors();
            if (op.method().isPresent()) {
                Method method = methodNamed(label, op, interfaces);
                request = method.request();
                response = method.response();
                errors = method.errors();
            }
            Placement placement = Placement.of(label, verb, path, op.query(), op.headers(), op.body(), request,
                    entries);
            routes.add(new Route(verb, path, op.method(), request, response, errors, op.status(), placement));
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
                    + " but binds no operation to them; bind each, or exempt it in not_bound with a reason");
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

    private static Method methodNamed(String label, Operation op, Map<String, Interface> interfaces) {
        String name = op.method().orElseThrow();
        Map<String, Method> found = new LinkedHashMap<>();
        interfaces.forEach((ifaceName, iface) -> {
            Method m = iface.methods().get(name);
            if (m != null && op.owner().map(ifaceName::equals).orElse(true)) {
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
