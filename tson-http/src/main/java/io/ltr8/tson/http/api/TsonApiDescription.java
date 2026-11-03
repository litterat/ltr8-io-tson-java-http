package io.ltr8.tson.http.api;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The service a schema describes, read back from its resolved entries: every endpoint of every entry applying {@code
 * http}, each resolved into a {@link Route}.
 *
 * <h2>What the resolver checked, and what this does</h2>
 *
 * <p>Naming is the resolver's where it can judge a name, and it is done before this class sees anything: a payload
 * type nothing declares, or a binding's exchange the protocol does not hold, is refused at schema load, because
 * {@code meta-service.tn} types those slots {@code meta.type_ref} and {@code meta.ref<exchange>}. A qualified name
 * says which protocol it means ({@code orders.place_order}: the entry, then the key), so no name is ambiguous.
 *
 * <p>An http projection's {@code @binds} and {@code @not_bound} are annotations on its entry, since a value of a
 * namespace type is the map alone, and the resolver does not judge a reference inside an annotation's value. So
 * naming there is this class's: each {@code @binds} name must be an entry of the description applying {@code
 * protocol}, and each exemption's exchange a key of a protocol the projection binds.
 *
 * <p>What relates two things is this class's too, and it is checked when a description is read, so an unsound
 * description has no model -- a startup failure, as a payload type nothing declares is:
 *
 * <ul>
 *   <li>a binding's exchange belongs to a protocol its projection binds;</li>
 *   <li>the {@code @binds} claim holds -- every exchange of every bound protocol is bound, or exempted in
 *       {@code @not_bound} with a reason -- held per {@link ExchangeName}, never per name;</li>
 *   <li>each request record's fields are placed over the path, query, headers and body exactly once
 *       ({@link RequestPlacement});</li>
 *   <li>the verb agrees with what the exchange says about itself (GET safe, PUT and DELETE idempotent), and a
 *       response-less endpoint answers a status that carries no body;</li>
 *   <li>each business error fixes its status, which is how a response is told from an error by status alone;</li>
 *   <li>a {@code document} lists at least one media type, since none is implied for it, and its body is bytes, so
 *       any type labels it; every other endpoint writes a TSON value, so it lists only media types a codec this
 *       library holds can write one in, {@code application/json} and {@code application/tson+json} beside {@code
 *       application/tson};</li>
 *   <li>no two endpoints share a verb and path, across every {@code http} the description holds.</li>
 * </ul>
 *
 * @see TsonApiCoverage for holding a server to the routes
 */
public final class TsonApiDescription {

    /**
     * The spec's own bundled meta directory. Every implementation has these; a service does not publish them,
     * and a client resolving this description already has them.
     */
    private static final String BUNDLED = "https://tson.io/2026/39/m/";

    private static final Set<Integer> EMPTY_STATUSES = Set.of(204, 205, 304);

    private static final String TSON = TsonMediaType.APPLICATION_TSON.toString();

    /** What a codec this library holds can speak: TSON text, and the JSON encoding under either label. */
    private static final Set<String> SPOKEN = Set.of(TSON, TsonMediaType.APPLICATION_JSON.toString(),
            TsonMediaType.APPLICATION_TSON_JSON.toString());

    private final Tson tson;
    private final String schemaId;
    private final List<Route> routes;

    private TsonApiDescription(Tson tson, String schemaId, List<Route> routes) {
        this.tson = tson;
        this.schemaId = schemaId;
        this.routes = List.copyOf(routes);
    }

    static TsonApiDescription of(Tson tson, String schemaId) {
        var compiled = tson.schemaRegistry().get(schemaId).orElseThrow(() -> new IllegalArgumentException(
                "'" + schemaId + "' is not resolved -- resolve the description before reading it"));
        Namespace<Top> entries = compiled.schema().entries();
        Map<String, Namespace<?>> protocols = applying(entries, "protocol");
        List<Route> routes = new ArrayList<>();
        Map<String, Route> byKey = new LinkedHashMap<>();
        applying(entries, "http").forEach((name, http) -> {
            for (Route route : read(name, http, entries.getAnnotations(name), protocols, entries::get)) {
                Route earlier = byKey.putIfAbsent(route.key(), route);
                if (earlier != null) {
                    throw new IllegalArgumentException("'" + route.key() + "' is declared twice, and a server serves "
                            + "one handler for a verb and a path");
                }
                routes.add(route);
            }
        });
        return new TsonApiDescription(tson, schemaId, routes);
    }

    /**
     * Every value entry of the namespace type {@code type}, by its name -- which is what a qualified name spells
     * first ({@code orders} in {@code orders.place_order}). Such an entry is a value, not a type: its {@link
     * ValueEntry} names the type it is a value of, and holds the map as a {@link Namespace}.
     */
    private static Map<String, Namespace<?>> applying(Namespace<Top> entries, String type) {
        Map<String, Namespace<?>> found = new LinkedHashMap<>();
        entries.forEach((entry, value) -> {
            if (value instanceof ValueEntry(String applied, Namespace<?> namespace) && applied.equals(type)) {
                found.put(entry, namespace);
            }
        });
        return found;
    }

    private static List<Route> read(String httpName, Namespace<?> http, Annotations claims,
                                    Map<String, Namespace<?>> declared, Function<String, Top> entries) {
        Map<String, Namespace<?>> protocols = bound(httpName, claims, declared);
        List<Route> routes = new ArrayList<>();
        Set<ExchangeName> bound = new LinkedHashSet<>();
        http.forEach((path, held) -> {
            Resource resource = (Resource) held;
            resource.endpoints().forEach((verbKey, endpoint) -> {
                String label = httpName + " " + verbKey + " " + path;
                HttpVerb verb = HttpVerb.valueOf(verbKey);
                Annotations onVerb = resource.endpoints().getAnnotations(verbKey);
                Optional<ExchangeName> exchange = Optional.empty();
                Optional<TypeRef> request;
                Optional<TypeRef> response = Optional.empty();
                List<TypeRef> errors;
                Annotations facts = onVerb;
                Annotations docs = onVerb;
                switch (endpoint) {
                    case Operation op -> {
                        request = op.request();
                        response = op.response();
                        errors = op.errors();
                    }
                    case Document doc -> {
                        request = doc.request();
                        errors = doc.errors();
                    }
                    case Binding b -> {
                        ExchangeName named = boundExchange(label, b, protocols);
                        Namespace<?> owner = protocols.get(named.owner());
                        Exchange e = (Exchange) owner.get(named.name());
                        exchange = Optional.of(named);
                        bound.add(named);
                        request = e.request();
                        response = e.response();
                        errors = e.errors();
                        // An exchange's facts are its own; its documentation is its own unless the verb key says more.
                        facts = owner.getAnnotations(named.name());
                        docs = facts;
                    }
                }
                RequestPlacement placement = RequestPlacement.of(label, verb, path, endpoint.query(),
                        endpoint.headers(), endpoint.body(), request, entries);
                requireVerbAgrees(label, verb, facts);
                boolean document = endpoint instanceof Document;
                if (!document) {
                    requireStatusAgrees(label, endpoint.status(), response);
                }
                Set<String> mediaTypes = mediaTypes(label, endpoint);
                Annotations fallback = docs;
                routes.add(new Route(verb, path, exchange, request, response, errorStatuses(label, errors, entries),
                        endpoint.status(), document, mediaTypes, placement,
                        text(onVerb, "summary").or(() -> text(fallback, "summary")),
                        text(onVerb, "doc").or(() -> text(fallback, "doc"))));
            });
        });
        requireComplete(httpName, claims, protocols, bound);
        return routes;
    }

    /**
     * The media types an endpoint speaks, folded to lower case as RFC 9110 §8.3.1 compares them: {@code
     * application/tson} and those listed, or for a document exactly those listed. A document must list one, since
     * none is implied for it; any other endpoint writes a TSON value, so each type it lists must name an encoding a
     * codec here can write it in.
     */
    private static Set<String> mediaTypes(String label, Endpoint endpoint) {
        Set<String> spoken = new LinkedHashSet<>();
        if (endpoint instanceof Document) {
            if (endpoint.mediaTypes().isEmpty()) {
                throw new IllegalArgumentException("'" + label + "' is a document listing no media type -- a "
                        + "document's body is bytes, so media_types is the whole of its contract; list one");
            }
        } else {
            spoken.add(TSON);
        }
        for (String listed : endpoint.mediaTypes()) {
            String folded = listed.toLowerCase(Locale.ROOT);
            if (!(endpoint instanceof Document) && !SPOKEN.contains(folded)) {
                throw new IllegalArgumentException("'" + label + "' lists the media type '" + listed + "', which no "
                        + "codec this server holds can speak -- an endpoint answering a TSON value speaks "
                        + new TreeSet<>(SPOKEN) + "; an endpoint answering bytes is a !document");
            }
            spoken.add(folded);
        }
        return spoken;
    }

    private static Optional<String> text(Annotations annotations, String name) {
        return annotations.value(name, String.class);
    }

    /**
     * The protocols an http projection's {@code @binds} names, each of which must be an entry of this description
     * applying {@code protocol}. The resolver does not judge a reference inside an annotation's value, so a name
     * nothing declares, or one naming an entry of another kind, reaches this check rather than failing at schema load.
     */
    private static Map<String, Namespace<?>> bound(String httpName, Annotations claims,
                                                   Map<String, Namespace<?>> declared) {
        Map<String, Namespace<?>> protocols = new LinkedHashMap<>();
        for (List<?> names : claims.values("binds", List.class)) {
            for (Object element : names) {
                String name = element instanceof TypeRef reference ? reference.name() : String.valueOf(element);
                Namespace<?> protocol = declared.get(name);
                if (protocol == null) {
                    throw new IllegalArgumentException("'" + httpName + "' binds '" + name + "', which is no "
                            + "entry of the description applying protocol -- protocols: " + declared.keySet());
                }
                protocols.put(name, protocol);
            }
        }
        return protocols;
    }

    /** The exchange a binding names, which must belong to a protocol its projection binds. */
    private static ExchangeName boundExchange(String label, Binding binding, Map<String, Namespace<?>> protocols) {
        ExchangeName named = ExchangeName.of(binding.exchange());
        if (!protocols.containsKey(named.owner())) {
            throw new IllegalArgumentException("'" + label + "' binds '" + named + "', an exchange of a protocol "
                    + "this projection does not bind -- bound: " + protocols.keySet());
        }
        return named;
    }

    /**
     * Every exchange of every bound protocol is bound or exempted. An exemption is held as hard as a binding: one
     * naming no exchange of a protocol the projection binds -- another protocol's, or a key no protocol holds --
     * exempts nothing it claims, so it is refused.
     */
    private static void requireComplete(String httpName, Annotations claims, Map<String, Namespace<?>> protocols,
                                        Set<ExchangeName> bound) {
        Set<ExchangeName> claimed = new LinkedHashSet<>();
        protocols.forEach((name, protocol) -> protocol.keySet().forEach(e -> claimed.add(new ExchangeName(name, e))));
        Set<ExchangeName> exempt = new LinkedHashSet<>();
        for (List<?> exemptions : claims.values("not_bound", List.class)) {
            for (Object element : exemptions) {
                ExchangeName exchange = ExchangeName.of(((Exemption) element).exchange());
                if (!claimed.contains(exchange)) {
                    throw new IllegalArgumentException("'" + httpName + "' exempts '" + exchange + "', which is not "
                            + "an exchange of a protocol it binds -- claimed: " + claimed);
                }
                exempt.add(exchange);
            }
        }
        List<ExchangeName> missing = claimed.stream().filter(e -> !bound.contains(e) && !exempt.contains(e))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("'" + httpName + "' binds protocols declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in @not_bound with a reason");
        }
    }

    /**
     * The projection checks its verb against what the exchange says about itself, and never derives one from the
     * other ({@code @safe} implies {@code @idempotent}, so a safe exchange may be bound to PUT or DELETE). A verb
     * whose contract the exchange does not claim is the one mismatch a client cannot see and a cache will act on.
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
            throw new IllegalArgumentException("'" + label + "': " + verb + " is " + what + ", but the exchange is "
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

    /**
     * Each business error by the status its type fixes ({@code sku_not_found => problem & { status: = 404 }}), which
     * is what tells an error from the response by status alone. An error fixing none, or two errors fixing one, would
     * leave a client unable to say which type a failure is.
     */
    private static Map<Integer, TypeRef> errorStatuses(String label, List<TypeRef> errors,
                                                       Function<String, Top> entries) {
        Map<Integer, TypeRef> byStatus = new LinkedHashMap<>();
        for (TypeRef error : errors) {
            int status = fixedStatus(entries.apply(error.name())).orElseThrow(() -> new IllegalArgumentException(
                    "'" + label + "' names the error '" + error.name() + "', which fixes no status -- write "
                            + "`status: = <code>` on it, so a client can tell it from the response by status"));
            TypeRef earlier = byStatus.putIfAbsent(status, error);
            if (earlier != null) {
                throw new IllegalArgumentException("'" + label + "' names '" + earlier.name() + "' and '"
                        + error.name() + "', which both fix status " + status);
            }
        }
        return byStatus;
    }

    private static Optional<Integer> fixedStatus(Top type) {
        if (!(type instanceof RecordBody record)) {
            return Optional.empty();
        }
        RecordField status = record.fields().get("status");
        if (status == null || status.role() != FieldRole.FIXED) {
            return Optional.empty();
        }
        return status.value().map(value -> Integer.parseInt(value.text()));
    }

    /** The description schema's identity. */
    public String schemaId() {
        return schemaId;
    }

    /** Every endpoint, in the order the description's http projections declare them. */
    public List<Route> routes() {
        return routes;
    }

    /** The endpoint serving {@code verb} at the declared {@code path}, if this description declares one. */
    public Optional<Route> route(HttpVerb verb, String path) {
        return routes.stream().filter(route -> route.verb() == verb && route.path().equals(path)).findFirst();
    }

    /**
     * Every schema identity a client needs in order to resolve this description — itself and its imports
     * transitively, {@code meta-service.tn} among them, minus the bundled standard library.
     *
     * <p><b>This is the set a server must publish</b>, and deriving it is the point: a description that
     * references a schema its own server does not serve is a contract nobody can act on, and hand-listing
     * what to publish is how that happens.
     *
     * <p>Ordered with this description first, then in resolution order.
     */
    public Set<String> referencedSchemas() {
        Set<String> found = new LinkedHashSet<>();
        collect(schemaId, found);
        return found;
    }

    private void collect(String id, Set<String> found) {
        if (id == null || id.startsWith(BUNDLED) || !found.add(id)) {
            return;
        }
        tson.loader().resolveLinked(id).schema().imports().forEach(imported -> collect(imported, found));
    }

    /**
     * Every payload type this description's endpoints name — requests, responses and errors, in the order the
     * endpoints declare them.
     *
     * <p>Not every one is a type the application binds: a parameter record bound only to be placed, or the
     * {@code problem} this library writes itself, may be named here and mapped by nobody. {@link #boundClasses} is
     * the filtered form, which is what a warm-up wants.
     */
    public Set<String> payloadTypes() {
        Set<String> types = new LinkedHashSet<>();
        routes.forEach(route -> route.payloadTypes().forEach(reference -> types.add(reference.name())));
        return types;
    }

    /**
     * The Java classes {@code bindings} maps this description's payload types to — what to warm at startup,
     * derived rather than listed.
     *
     * <p>Hand-listing them is how a response type added to a description never gets warmed: nothing connects
     * the two lists, and the omission costs only latency, so nothing reports it. A type {@code bindings} does
     * not map is skipped rather than refused.
     */
    public List<Class<?>> boundClasses(Map<String, Class<?>> bindings) {
        return payloadTypes().stream().map(bindings::get).filter(Objects::nonNull).distinct().toList();
    }
}
