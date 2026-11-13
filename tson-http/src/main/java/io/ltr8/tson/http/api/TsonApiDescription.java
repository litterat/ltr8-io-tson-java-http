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
import io.ltr8.tson.schema.meta.ValueRef;

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
 * service.http}, each resolved into a {@link Route}.
 *
 * <h2>What the resolver checked, and what this does</h2>
 *
 * <p>Naming is the resolver's, and it is done before this class sees anything: a payload type nothing declares, a
 * binding's or an exemption's exchange the protocol does not hold, and a name in an http projection's {@code binds}
 * that is no protocol are refused at schema load, because {@code service.tn} types those slots {@code
 * meta.type_ref}, {@code meta.ref<service.exchange>} and {@code meta.ref<service.protocol>}. A qualified name says
 * which protocol it means ({@code orders.exchanges.place_order}: the entry, its {@code exchanges} field, then the
 * key), so no name is ambiguous. Each such reference is delivered its referent when the description links, so a
 * bound protocol is read from {@link Http#binds} and a binding's exchange from {@link Binding#exchange}.
 *
 * <p>Every name the layer declares sits under its one root map, {@code service}, so the reader matches annotations
 * by their qualified names, reported as written: {@code service.safe}, {@code service.summary}.
 *
 * <p>What relates two things is this class's, and it is checked when a description is read, so an unsound
 * description has no model -- a startup failure, as a payload type nothing declares is:
 *
 * <ul>
 *   <li>a binding's or an exemption's exchange belongs to a protocol its projection binds;</li>
 *   <li>the {@code binds} claim holds -- every exchange of every bound protocol is bound, or exempted in {@code
 *       not_bound} with a reason -- held per {@link ExchangeName}, never per name;</li>
 *   <li>each request record's fields are placed over the path, query, headers and body exactly once
 *       ({@link RequestPlacement});</li>
 *   <li>the verb agrees with what the exchange says about itself (GET safe, PUT and DELETE idempotent), and a
 *       response-less endpoint answers a status that carries no body;</li>
 *   <li>each business error fixes its status, which is how a response is told from an error by status alone;</li>
 *   <li>a {@code document} lists at least one media type, since none is implied for it, and its body is bytes, so
 *       any type labels it; every other endpoint writes a TSON value, so it lists only media types a codec this
 *       library holds can write one in, {@code application/json} and {@code application/tson+json} beside {@code
 *       application/tson};</li>
 *   <li>no two endpoints share a verb and path, across every {@code service.http} the description holds;</li>
 *   <li>a bound request record's dependent field lets a sender name only types this server binds
 *       ({@link ChosenTypes});</li>
 *   <li>each response header's template expands from the response value: every variable names a scalar field of
 *       the endpoint's response type, and an endpoint with no response type, a document among them, declares
 *       literals only.</li>
 * </ul>
 *
 * <p>The security schemes in force for an endpoint are resolved here too, {@link Route#security()}: the endpoint's
 * own {@code security} when it writes one, an empty one meaning open, else its projection's. The resolver judged
 * every name a scheme; nothing here or anywhere in this library enforces one.
 *
 * @see TsonApiCoverage for holding a server to the routes
 */
public final class TsonApiDescription {

    /**
     * The spec's own bundled meta directory. Every implementation has these; a service does not publish them,
     * and a client resolving this description already has them.
     */
    private static final String BUNDLED = "https://tson.io/2026/40/m/";

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
        List<Route> routes = new ArrayList<>();
        Map<String, Route> byKey = new LinkedHashMap<>();
        projections(entries).forEach((name, http) -> {
            for (Route route : read(name, http, entries::get)) {
                Route earlier = byKey.putIfAbsent(route.key(), route);
                if (earlier != null) {
                    throw new IllegalArgumentException("'" + route.key() + "' is declared twice, and a server serves "
                            + "one handler for a verb and a path");
                }
                routes.add(route);
            }
        });
        for (Route route : routes) {
            route.request().ifPresent(request ->
                    ChosenTypes.requireBound(route.key(), request, entries::get, tson.dataBindContext()));
        }
        return new TsonApiDescription(tson, schemaId, routes);
    }

    /**
     * Every value entry applying {@code service.http}, by its name, in the order written. Such an entry is a value,
     * not a type: its {@link ValueEntry} holds the bound {@link Http}.
     */
    private static Map<String, Http> projections(Namespace<Top> entries) {
        Map<String, Http> found = new LinkedHashMap<>();
        entries.forEach((entry, value) -> {
            if (value instanceof ValueEntry(_, Http http)) {
                found.put(entry, http);
            }
        });
        return found;
    }

    private static List<Route> read(String httpName, Http http, Function<String, Top> entries) {
        Map<String, Protocol> protocols = bound(http);
        List<Route> routes = new ArrayList<>();
        Set<ExchangeName> bound = new LinkedHashSet<>();
        http.resources().forEach((path, resource) -> {
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
                        Exchange e = b.exchange().get();
                        exchange = Optional.of(named);
                        bound.add(named);
                        request = e.request();
                        response = e.response();
                        errors = e.errors();
                        // An exchange's facts are its own; its documentation is its own unless the verb key says more.
                        facts = protocols.get(named.owner()).exchanges().getAnnotations(named.name());
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
                requireTemplatesExpand(label, endpoint.responseHeaders(), response, entries);
                // An endpoint's own list replaces the projection's, an empty one included: `security: []` is open.
                List<SecurityScheme> security = endpoint.security().orElse(http.security()).stream()
                        .map(ValueRef::get).toList();
                Annotations fallback = docs;
                routes.add(new Route(verb, path, exchange, request, response, errorStatuses(label, errors, entries),
                        endpoint.status(), document, mediaTypes, placement,
                        text(onVerb, "service.summary").or(() -> text(fallback, "service.summary")),
                        text(onVerb, "doc").or(() -> text(fallback, "doc")), security, endpoint.responseHeaders()));
            });
        });
        requireComplete(httpName, http, protocols, bound);
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
                        + new TreeSet<>(SPOKEN) + "; an endpoint answering bytes is a !service.document");
            }
            spoken.add(folded);
        }
        return spoken;
    }

    /**
     * Each response header's template expands from the response value: every variable names a field of the response
     * type, and that field is one scalar, as a path segment's is. An endpoint with no response type -- a document, or
     * one answering no body -- has nothing to expand a variable from, so its headers are literals.
     */
    private static void requireTemplatesExpand(String label, Map<String, String> headers, Optional<TypeRef> response,
                                               Function<String, Top> entries) {
        headers.forEach((header, template) -> {
            for (String variable : HeaderTemplate.variables(label, header, template)) {
                String declared = "'" + label + "' declares the response header '" + header + ": " + template + "'";
                if (response.isEmpty()) {
                    throw new IllegalArgumentException(declared + ", but the endpoint has no response type to expand '"
                            + variable + "' from -- a document's header, or one answering no body, is a literal");
                }
                String type = response.get().name();
                if (!(entries.apply(type) instanceof RecordBody record)) {
                    throw new IllegalArgumentException(declared + ", but the response type '" + type + "' is not a "
                            + "record, so it has no field '" + variable + "'");
                }
                RecordField field = record.fields().get(variable);
                if (field == null) {
                    throw new IllegalArgumentException(declared + ", but the response type '" + type + "' declares no "
                            + "field '" + variable + "' -- it declares " + record.fields().keySet());
                }
                RequestPlacement.notOneScalar(field, entries).ifPresent(because -> {
                    throw new IllegalArgumentException(declared + ", but '" + variable + "' of '" + type + "' is "
                            + because + ", and a header's variable expands from one scalar");
                });
            }
        });
    }

    private static Optional<String> text(Annotations annotations, String name) {
        return annotations.value(name, String.class);
    }

    /**
     * The protocols an http projection's {@code binds} names, by the entry name each reference wrote, which is the
     * first segment of a qualified name reaching one of its exchanges. The resolver judged each name a protocol, and
     * the reference is delivered the {@link Protocol} it names.
     */
    private static Map<String, Protocol> bound(Http http) {
        Map<String, Protocol> protocols = new LinkedHashMap<>();
        for (ValueRef<Protocol> reference : http.binds()) {
            protocols.put(reference.name(), reference.get());
        }
        return protocols;
    }

    /** The exchange a binding names, which must belong to a protocol its projection binds. */
    private static ExchangeName boundExchange(String label, Binding binding, Map<String, Protocol> protocols) {
        ExchangeName named = ExchangeName.of(binding.exchange());
        if (!protocols.containsKey(named.owner())) {
            throw new IllegalArgumentException("'" + label + "' binds '" + named + "', an exchange of a protocol "
                    + "this projection does not bind -- bound: " + protocols.keySet());
        }
        return named;
    }

    /**
     * Every exchange of every bound protocol is bound or exempted. An exemption is held as hard as a binding: one
     * naming an exchange of a protocol the projection does not bind exempts nothing it claims, so it is refused.
     */
    private static void requireComplete(String httpName, Http http, Map<String, Protocol> protocols,
                                        Set<ExchangeName> bound) {
        Set<ExchangeName> claimed = new LinkedHashSet<>();
        protocols.forEach((name, protocol) ->
                protocol.exchanges().keySet().forEach(e -> claimed.add(new ExchangeName(name, e))));
        Set<ExchangeName> exempt = new LinkedHashSet<>();
        for (Exemption exemption : http.notBound()) {
            ExchangeName exchange = ExchangeName.of(exemption.exchange());
            if (!claimed.contains(exchange)) {
                throw new IllegalArgumentException("'" + httpName + "' exempts '" + exchange + "', which is not an "
                        + "exchange of a protocol it binds -- claimed: " + claimed);
            }
            exempt.add(exchange);
        }
        List<ExchangeName> missing = claimed.stream().filter(e -> !bound.contains(e) && !exempt.contains(e))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("'" + httpName + "' binds protocols declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in not_bound with a reason");
        }
    }

    /**
     * The projection checks its verb against what the exchange says about itself, and never derives one from the
     * other ({@code @service.safe} implies {@code @service.idempotent}, so a safe exchange may be bound to PUT or
     * DELETE). A verb whose contract the exchange does not claim is the one mismatch a client cannot see and a cache
     * will act on.
     */
    private static void requireVerbAgrees(String label, HttpVerb verb, Annotations facts) {
        boolean safe = facts.has("service.safe");
        boolean idempotent = safe || facts.has("service.idempotent");
        switch (verb) {
            case GET, HEAD, OPTIONS -> require(safe, label, verb, "safe");
            case PUT, DELETE -> require(idempotent, label, verb, "idempotent");
            case POST, PATCH -> { }
        }
    }

    private static void require(boolean holds, String label, HttpVerb verb, String what) {
        if (!holds) {
            throw new IllegalArgumentException("'" + label + "': " + verb + " is " + what + ", but the exchange is "
                    + "not marked @service." + what + " -- mark it, or bind it to a verb that claims less");
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
     * Each business error by the status its type fixes ({@code sku_not_found => problem.details & { status: = 404
     * }}), which is what tells an error from the response by status alone. An error fixing none, or two errors
     * fixing one, would leave a client unable to say which type a failure is.
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
     * transitively, {@code service.tn} among them, minus the bundled standard library.
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
     * {@code problem.details} this library writes itself, may be named here and mapped by nobody. {@link #boundClasses} is
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
