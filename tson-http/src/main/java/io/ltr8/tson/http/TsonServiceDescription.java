package io.ltr8.tson.http;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.schema.ApiKey;
import io.ltr8.tson.http.schema.ApiKeyIn;
import io.ltr8.tson.http.schema.Binding;
import io.ltr8.tson.http.schema.Document;
import io.ltr8.tson.http.schema.Endpoint;
import io.ltr8.tson.http.schema.Exemption;
import io.ltr8.tson.http.schema.HttpProjection;
import io.ltr8.tson.http.schema.HttpVerb;
import io.ltr8.tson.http.schema.Operation;
import io.ltr8.tson.http.schema.SecurityScheme;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.service.TsonExchangeName;
import io.ltr8.tson.service.internal.Landed;
import io.ltr8.tson.service.schema.Exchange;
import io.ltr8.tson.service.schema.Protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The service a schema describes, read back from its resolved entries: every endpoint of every entry applying {@code
 * http.projection}, each resolved into a {@link TsonRoute}.
 *
 * <h2>What the resolver checked, and what this does</h2>
 *
 * <p>Naming is the resolver's, and it is done before this class sees anything: a payload type nothing declares, a
 * binding's or an exemption's exchange the protocol does not hold, and a name in an http projection's {@code binds}
 * that is no protocol are refused at schema load, because {@code service.tn} and {@code http.tn} type those slots
 * {@code meta.type_ref}, {@code meta.ref<service.exchange>} and {@code meta.ref<service.protocol>}. A qualified name says
 * which protocol it means ({@code orders.exchanges.place_order}: the entry, its {@code exchanges} field, then the
 * key), so no name is ambiguous. Each such reference is delivered its referent when the description links, so a
 * bound protocol is read from {@link HttpProjection#binds} and a binding's exchange from {@link Binding#exchange}.
 *
 * <p>Every name {@code service.tn} declares sits under its one root map, {@code service}, and every name {@code
 * http.tn} declares under {@code http}, so the reader matches annotations by their qualified names, reported as
 * written: {@code service.safe}, {@code service.summary}, even before a verb key in a projection.
 *
 * <p>What relates two things is this class's, and it is checked when a description is read, so an unsound
 * description has no model -- a startup failure, as a payload type nothing declares is:
 *
 * <ul>
 *   <li>a binding's or an exemption's exchange belongs to a protocol its projection binds;</li>
 *   <li>the {@code binds} claim holds -- every exchange of every bound protocol is bound, or exempted in {@code
 *       not_bound} with a reason -- held per {@link TsonExchangeName}, never per name;</li>
 *   <li>each request record's fields are placed over the path, query, headers and body exactly once
 *       ({@link TsonRequestPlacement});</li>
 *   <li>no exchange of a protocol a projection binds, and no verb key of an endpoint binding no exchange, is
 *       marked both {@code @service.safe} and {@code @service.destructive}, which exclude each other; a protocol no
 *       projection binds is not judged here;</li>
 *   <li>the verb agrees with what the exchange says about itself (GET safe, PUT and DELETE idempotent, and nothing
 *       destructive at GET or HEAD), and an operation or a binding with no response type writes a status that
 *       carries no body, 204, 205 or 304, since the default 200 is for an endpoint with a response;</li>
 *   <li>each business error fixes its status, which is how a response is told from an error by status alone;</li>
 *   <li>a {@code document} lists at least one media type, since none is implied for it, and its body is bytes, so
 *       any type labels it; every other endpoint writes a TSON value, so it lists only media types naming an
 *       encoding of one, {@code application/json} and {@code application/tson+json} beside {@code
 *       application/tson}, the encodings {@link TsonHttpCodec} speaks;</li>
 *   <li>no two endpoints share a verb and path, across every {@code http.projection} the description holds;</li>
 *   <li>a bound request record's dependent field lets a sender name only types this server binds
 *       ({@link ChosenTypes});</li>
 *   <li>each response header's template expands from the response value: every variable names a scalar field of
 *       the endpoint's response type, and an endpoint with no response type, a document among them, declares
 *       literals only;</li>
 *   <li>no class is bound by the local name of an extern the description declares and a route names, where it
 *       differs from the entry the extern lands on and no schema of the closure declares that name as its own: a
 *       reader asks for the entry, so such a binding serves no read.</li>
 * </ul>
 *
 * <p><b>A payload type named through an extern</b> ([TSON-SCHEMA] §5.13) is judged as the foreign entry it lands on:
 * an error's status pin, a response header's field, a request's placement and its chosen types read the foreign
 * record, and the names its declaration writes are looked up in the foreign schema's namespace, never the
 * description's ({@code Landed}). A route names the type as the description wrote it; {@link #payloadTypes()} names
 * it where it is declared, the declaring schema and the entry's own name, which is what the binder is asked ({@link
 * TsonPayloadType}).
 *
 * <p><b>A payload type that is a plain alias</b>, reaching no extern, is judged as the entry the alias reaches past
 * every hop, so a request, a response or an error aliasing a record is that record to its placement, its response
 * headers and its status pin; a route and {@link #payloadTypes()} name it as written, the alias in the schema
 * declaring it.
 *
 * <p>The security schemes in force for an endpoint are resolved here too, {@link TsonRoute#security()}: the endpoint's
 * own {@code security} when it writes one, an empty one meaning open, else its projection's. The resolver judged
 * every name a scheme, and an api key carried in a header is held to RFC 9110's token grammar here, since its
 * {@code name} is {@code text}; nothing here or anywhere in this library enforces a scheme.
 *
 * @see TsonServiceCoverage for holding a server to the routes
 */
public final class TsonServiceDescription {

    /**
     * The spec's own bundled meta directory, as a canonical identity's prefix ([TSON-DATA] §2.2.1). Every
     * implementation has these; a service does not publish them, and a client resolving this description already
     * has them.
     */
    private static final String BUNDLED = "tson.io/2026/40/m/";

    private static final Set<Integer> EMPTY_STATUSES = Set.of(204, 205, 304);

    /** RFC 9110's {@code token}, the grammar of a header field name, as {@code http.header_name} states it. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private static final String TSON = TsonMediaType.APPLICATION_TSON.toString();

    /** The encodings of a TSON value, each bare as a route holds it: TSON text, and the JSON encoding under either label. */
    private static final Set<String> SPOKEN = Set.of(TSON, TsonMediaType.APPLICATION_JSON.toString(),
            TsonMediaType.APPLICATION_TSON_JSON.toString());

    private final Tson tson;
    private final String schemaId;
    private final TsonLinkedSchema linked;
    private final List<TsonRoute> routes;
    private final Set<TsonPayloadType> payloadTypes;

    private TsonServiceDescription(Tson tson, String schemaId, TsonLinkedSchema linked, List<TsonRoute> routes) {
        this.tson = tson;
        this.schemaId = schemaId;
        this.linked = linked;
        this.routes = List.copyOf(routes);
        Set<TsonPayloadType> types = new LinkedHashSet<>();
        this.routes.forEach(route -> route.payloadTypes().forEach(reference -> types.add(payloadType(reference))));
        this.payloadTypes = Collections.unmodifiableSet(types);
    }

    static TsonServiceDescription of(Tson tson, String schemaId) {
        if (tson.schemaRegistry().get(schemaId).isEmpty()) {
            throw new IllegalArgumentException("'" + schemaId + "' is not resolved -- resolve the description before "
                    + "reading it");
        }
        TsonLinkedSchema linked = tson.loader().resolveLinked(schemaId);
        DataBindContext context = tson.dataBindContext();
        List<TsonRoute> routes = new ArrayList<>();
        Map<String, TsonRoute> byKey = new LinkedHashMap<>();
        projections(linked.schema().entries()).forEach((name, http) -> {
            for (TsonRoute route : read(name, http, linked)) {
                TsonRoute earlier = byKey.putIfAbsent(route.key(), route);
                if (earlier != null) {
                    throw new IllegalArgumentException("'" + route.key() + "' is declared twice, and a server serves "
                            + "one handler for a verb and a path");
                }
                routes.add(route);
            }
        });
        for (TsonRoute route : routes) {
            route.request().ifPresent(request -> ChosenTypes.requireBound(route.key(), request, linked, context));
        }
        requireNoBindingByExternName(schemaId, linked, closure(tson, linked).values(), routes, context);
        return new TsonServiceDescription(tson, schemaId, linked, routes);
    }

    /**
     * A reader asks the bind context for the class of the entry an extern lands on, by the schema declaring it and
     * its name there, never for the extern's local name ([TSON-SCHEMA] §5.13). So a context binding an extern's local
     * name, where it differs from the entry's, binds nothing a read of that payload type will ask for: the schema
     * prepares clean and every read of it is a 500 naming the entry's own name. Refused for an extern this description
     * declares itself and a route names as its request, response or error, unless a schema of the closure declares an
     * entry of that name as its own, which such a binding may be for.
     */
    private static void requireNoBindingByExternName(String schemaId, TsonLinkedSchema linked,
                                                     Collection<TsonLinkedSchema> closure, List<TsonRoute> routes,
                                                     DataBindContext context) {
        Map<String, TsonRoute> namedAt = new LinkedHashMap<>();
        routes.forEach(route -> route.payloadTypes().forEach(type -> namedAt.putIfAbsent(type.name(), route)));
        namedAt.forEach((name, route) -> {
            if (!(linked.schema().entries().get(name) instanceof Extern)
                    || !linked.declaringIdentity(name).equals(linked.identity())) {
                return;
            }
            Optional<ExternTarget.Landing> landing = linked.landing(name);
            if (landing.isEmpty() || landing.get().name().equals(name) || declaresItsOwn(closure, name)) {
                return;                                 // a read asks the binder this very name
            }
            Class<?> bound;
            try {
                bound = context.nameBinder().resolve(linked.declaringIdentity(name), name);
            } catch (DataBindException unbound) {
                return;
            }
            String target = landing.get().name();
            String declaring = landing.get().in().schema().id();
            throw new IllegalArgumentException("'" + route.key() + "' names '" + name + "', which '" + schemaId
                    + "' declares as an extern of '" + target + "' in '" + declaring + "', and the bind context binds '"
                    + name + "' to " + bound.getName() + ", which no read asks for: no schema of the closure declares "
                    + "a '" + name + "' of its own -- a reader asks the binder for the entry an extern lands on, by the "
                    + "schema declaring it and its name there; bind '" + target + "' instead, through "
                    + "TsonBindings.bySchema keyed by '" + declaring + "' where two schemas each declare '" + target
                    + "'");
        });
    }

    /** Whether a schema of {@code closure} declares an entry {@code name} of its own, other than an extern. */
    private static boolean declaresItsOwn(Collection<TsonLinkedSchema> closure, String name) {
        return closure.stream().anyMatch(schema -> schema.schema().entries().get(name) != null
                && !(schema.schema().entries().get(name) instanceof Extern)
                && schema.declaringIdentity(name).equals(schema.identity()));
    }

    /**
     * Every value entry applying {@code http.projection}, by its name, in the order written. Such an entry is a value,
     * not a type: its {@link ValueEntry} holds the bound {@link HttpProjection}. One whose value is held as a tree
     * instead is refused: the resolver loads a projection holding a type nothing binds and keeps the whole value as a
     * tree, and skipping it would serve a description with that projection's routes missing. The type may be a
     * description's own endpoint subtype, or {@code http.tn}'s records themselves where bindings keyed per schema
     * leave {@code http.tn} out, so the refusal names both remedies.
     */
    private static Map<String, HttpProjection> projections(Namespace<Top> entries) {
        Map<String, HttpProjection> found = new LinkedHashMap<>();
        entries.forEach((entry, value) -> {
            if (value instanceof ValueEntry(_, HttpProjection http)) {
                found.put(entry, http);
            } else if (value instanceof ValueEntry(String type, Object unbound) && type.equals("http.projection")) {
                throw new IllegalArgumentException("'" + entry + "' applies http.projection, and its value did not "
                        + "bind to " + HttpProjection.class.getName() + " but is held as " + heldAs(unbound)
                        + " -- a type written in it binds to no class: bind http.tn's records and a description's own "
                        + "endpoint subtype through TsonHttpSchema.bindingsWith, or key "
                        + "TsonHttpSchema.bindings under http.tn's identity in TsonBindings.bySchema");
            }
        });
        return found;
    }

    /** What a value that did not bind is held as, for a refusal: its class's simple name, or {@code null}. */
    private static String heldAs(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static List<TsonRoute> read(String httpName, HttpProjection http, TsonLinkedSchema scope) {
        Map<String, Protocol> protocols = bound(httpName, http);
        // Every exchange the projection claims, bound or exempted, states facts that must agree with each other.
        protocols.forEach((name, protocol) -> protocol.exchanges().keySet().forEach(key -> requireFactsExclusive(
                new TsonExchangeName(name, key).toString(), protocol.exchanges().getAnnotations(key))));
        List<TsonRoute> routes = new ArrayList<>();
        Set<TsonExchangeName> bound = new LinkedHashSet<>();
        http.resources().forEach((path, resource) -> {
            resource.endpoints().forEach((verbKey, endpoint) -> {
                String label = httpName + " " + verbKey + " " + path;
                HttpVerb verb = HttpVerb.valueOf(verbKey);
                Annotations onVerb = resource.endpoints().getAnnotations(verbKey);
                Optional<TsonExchangeName> exchange = Optional.empty();
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
                        TsonExchangeName named = boundExchange(label, b, protocols);
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
                TsonRequestPlacement placement = TsonRequestPlacement.of(label, verb, path, endpoint.query(),
                        endpoint.headers(), endpoint.body(), request, scope);
                if (exchange.isEmpty()) {
                    requireFactsExclusive(label, facts);
                }
                boolean safe = facts.has("service.safe");
                boolean idempotent = safe || facts.has("service.idempotent");
                boolean destructive = facts.has("service.destructive");
                requireVerbAgrees(label, verb, safe, idempotent, destructive);
                boolean document = endpoint instanceof Document;
                if (!document) {
                    requireStatusAgrees(label, endpoint.status(), response);
                }
                Set<String> mediaTypes = mediaTypes(label, endpoint);
                requireTemplatesExpand(label, endpoint.responseHeaders(), response, scope);
                // An endpoint's own list replaces the projection's, an empty one included: `security: []` is open.
                List<SecurityScheme> security = endpoint.security().orElse(http.security()).stream()
                        .map(ValueRef::get).toList();
                security.forEach(scheme -> requireHeaderToken(label, scheme));
                Annotations fallback = docs;
                routes.add(new TsonRoute(verb, path, exchange, request, response, errorStatuses(label, errors, scope),
                        endpoint.status(), safe, idempotent, destructive, document, mediaTypes, placement,
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
     * none is implied for it; any other endpoint writes a TSON value, so each type it lists must name an encoding of
     * one.
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
                        + new TreeSet<>(SPOKEN) + "; an endpoint answering bytes is a !http.document");
            }
            spoken.add(folded);
        }
        return spoken;
    }

    /**
     * Each response header's template expands from the response value: every variable names a field of the response
     * type, and that field is one scalar, as a path segment's is. An endpoint with no response type -- a document, or
     * one answering no body -- has nothing to expand a variable from, so its headers are literals. A response type
     * named through an extern or a plain alias is the record it lands on, and each field's type is judged as what it
     * lands on from that record's landing, past any further extern or plain alias ({@code Landed}).
     */
    private static void requireTemplatesExpand(String label, Map<String, String> headers, Optional<TypeRef> response,
                                               TsonLinkedSchema scope) {
        headers.forEach((header, template) -> {
            for (String variable : HeaderTemplate.variables(label, header, template)) {
                String declared = "'" + label + "' declares the response header '" + header + ": " + template + "'";
                if (response.isEmpty()) {
                    throw new IllegalArgumentException(declared + ", but the endpoint has no response type to expand '"
                            + variable + "' from -- a document's header, or one answering no body, is a literal");
                }
                String type = response.get().name();
                Landed landed = Landed.of(scope, type).pastAliases();
                if (!(landed.definition() instanceof RecordBody record)) {
                    throw new IllegalArgumentException(declared + ", but the response type '" + type + "' is not a "
                            + "record, so it has no field '" + variable + "'");
                }
                RecordField field = record.fields().get(variable);
                if (field == null) {
                    throw new IllegalArgumentException(declared + ", but the response type '" + type + "' declares no "
                            + "field '" + variable + "' -- it declares " + record.fields().keySet());
                }
                TsonRequestPlacement.notOneScalar(field, landed).ifPresent(because -> {
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
     * the reference is delivered the {@link Protocol} it names once {@code service.tn}'s records are bound. A referent
     * that is no bound {@code Protocol} is refused, saying what it is held as, rather than failing on a cast; the
     * likely cause, bindings keyed per schema leaving {@code service.tn} out, is offered as such, not asserted.
     */
    private static Map<String, Protocol> bound(String httpName, HttpProjection http) {
        Map<String, Protocol> protocols = new LinkedHashMap<>();
        for (ValueRef<?> reference : http.binds()) {
            Object referent = reference.get();
            if (!(referent instanceof Protocol protocol)) {
                throw new IllegalArgumentException("'" + httpName + "' binds '" + reference.name() + "', whose value "
                        + "is not a bound " + Protocol.class.getName() + " but is held as " + heldAs(referent)
                        + " -- likely service.tn's records bind to no class here: bind them through "
                        + "TsonHttpSchema.bindingsWith, or key TsonServiceSchema.bindings under service.tn's "
                        + "identity in TsonBindings.bySchema");
            }
            protocols.put(reference.name(), protocol);
        }
        return protocols;
    }

    /** The exchange a binding names, which must belong to a protocol its projection binds. */
    private static TsonExchangeName boundExchange(String label, Binding binding, Map<String, Protocol> protocols) {
        TsonExchangeName named = TsonExchangeName.of(binding.exchange());
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
    private static void requireComplete(String httpName, HttpProjection http, Map<String, Protocol> protocols,
                                        Set<TsonExchangeName> bound) {
        Set<TsonExchangeName> claimed = new LinkedHashSet<>();
        protocols.forEach((name, protocol) ->
                protocol.exchanges().keySet().forEach(e -> claimed.add(new TsonExchangeName(name, e))));
        Set<TsonExchangeName> exempt = new LinkedHashSet<>();
        for (Exemption exemption : http.notBound()) {
            TsonExchangeName exchange = TsonExchangeName.of(exemption.exchange());
            if (!claimed.contains(exchange)) {
                throw new IllegalArgumentException("'" + httpName + "' exempts '" + exchange + "', which is not an "
                        + "exchange of a protocol it binds -- claimed: " + claimed);
            }
            exempt.add(exchange);
        }
        List<TsonExchangeName> missing = claimed.stream().filter(e -> !bound.contains(e) && !exempt.contains(e))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("'" + httpName + "' binds protocols declaring " + missing
                    + " but has no binding for them; bind each, or exempt it in not_bound with a reason");
        }
    }

    /**
     * {@code @service.safe} and {@code @service.destructive} exclude each other: a safe exchange has no effect, so it
     * has none a caller cannot undo. {@code named} is the exchange's qualified name, or an operation's endpoint.
     */
    private static void requireFactsExclusive(String named, Annotations facts) {
        if (facts.has("service.safe") && facts.has("service.destructive")) {
            throw new IllegalArgumentException("'" + named + "' is marked both @service.safe and @service.destructive, "
                    + "which exclude each other -- a safe exchange has no effect to undo; drop one");
        }
    }

    /**
     * The projection checks its verb against what the exchange says about itself, and never derives one from the
     * other ({@code @service.safe} implies {@code @service.idempotent}, so a safe exchange may be bound to PUT or
     * DELETE). A verb whose contract the exchange does not claim is the one mismatch a client cannot see and a cache
     * will act on. A destructive exchange is refused at a safe verb outright, since no verb implies {@code
     * @service.destructive} and a safe one denies it.
     */
    private static void requireVerbAgrees(String label, HttpVerb verb, boolean safe, boolean idempotent,
                                          boolean destructive) {
        switch (verb) {
            case GET, HEAD, OPTIONS -> {
                if (destructive) {
                    throw new IllegalArgumentException("'" + label + "': " + verb + " is safe, but the exchange is "
                            + "marked @service.destructive -- a destructive exchange is bound to a verb that is not "
                            + "safe");
                }
                require(safe, label, verb, "safe");
            }
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

    /**
     * An api key carried in a header is named by a header field name, which {@code http.api_key}'s {@code text}
     * cannot require, a query parameter's name being no header name; {@link ApiKey} holds it folded.
     */
    private static void requireHeaderToken(String label, SecurityScheme scheme) {
        if (scheme instanceof ApiKey(ApiKeyIn in, String name) && in == ApiKeyIn.header
                && !TOKEN.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + label + "' accepts an api key in the header '" + name + "', "
                    + "which is no RFC 9110 token, so no header carries it -- a header name is ASCII letters, "
                    + "digits and !#$%&'*+.^_`|~-");
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
     * fixing one, would leave a client unable to say which type a failure is. An error named through an extern or a
     * plain alias fixes the status the record it lands on fixes.
     */
    private static Map<Integer, TypeRef> errorStatuses(String label, List<TypeRef> errors, TsonLinkedSchema scope) {
        Map<Integer, TypeRef> byStatus = new LinkedHashMap<>();
        for (TypeRef error : errors) {
            int status = fixedStatus(Landed.of(scope, error.name()).pastAliases().definition()).orElseThrow(() ->
                    new IllegalArgumentException("'" + label + "' names the error '" + error.name() + "', which "
                            + "fixes no status -- write `status: = <code>` on it, so a client can tell it from the "
                            + "response by status"));
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
    public List<TsonRoute> routes() {
        return routes;
    }

    /** The endpoint serving {@code verb} at the declared {@code path}, if this description declares one. */
    public Optional<TsonRoute> route(HttpVerb verb, String path) {
        return routes.stream().filter(route -> route.verb() == verb && route.path().equals(path)).findFirst();
    }

    /**
     * Every schema identity a client needs in order to resolve this description — itself and its dependencies
     * transitively, {@code service.tn} among them, minus the bundled standard library. A dependency is an import
     * or the schema an extern names ([TSON-SCHEMA] §5.13, {@code TsonLinkedSchema.dependencies()}): an extern's
     * schema loads with the imports though none of its names enters the namespace, so a client resolving the
     * description fetches it too.
     *
     * <p><b>This is the set a server must publish</b>, and deriving it is the point: a description that
     * references a schema its own server does not serve is a contract nobody can act on, and hand-listing
     * what to publish is how that happens.
     *
     * <p>Each schema is named by its own {@code !!id}, the identity a catalog serves it at. Ordered with this
     * description first, then depth first, each schema's imports before the schemas its externs name.
     */
    public Set<String> referencedSchemas() {
        return new LinkedHashSet<>(closure(tson, linked).keySet());
    }

    /**
     * {@code linked} and what it depends on transitively, minus the bundled standard library, by each schema's
     * {@code !!id}: this schema first, then depth first, each schema's imports before the schemas its externs name.
     * Every dependency resolved when the description did, so each is in the registry by its canonical identity,
     * which is what {@code dependencies()} lists.
     */
    private static Map<String, TsonLinkedSchema> closure(Tson tson, TsonLinkedSchema linked) {
        Map<String, TsonLinkedSchema> found = new LinkedHashMap<>();
        collect(tson, linked, found);
        return found;
    }

    private static void collect(Tson tson, TsonLinkedSchema linked, Map<String, TsonLinkedSchema> found) {
        if (found.putIfAbsent(linked.schema().id(), linked) != null) {
            return;
        }
        for (String dependency : linked.dependencies()) {
            if (!dependency.startsWith(BUNDLED)) {
                collect(tson, tson.schemaRegistry().getByCanonicalIdentity(dependency).orElseThrow(() ->
                        new IllegalStateException("'" + dependency + "' is a dependency of '" + linked.schema().id()
                                + "' and is not resolved")), found);
            }
        }
    }

    /**
     * Every payload type this description's endpoints name -- requests, responses and errors, in the order the
     * endpoints declare them -- each where it is declared, the schema declaring it and its name there, which is what a
     * reader asks the bind context for its class ({@link TsonPayloadType}). A type named through an extern is the foreign
     * entry it lands on, so {@code order_v1} and {@code order_v2}, externs of two schemas' {@code order}, are two
     * payload types named {@code order}, and a route still names each by its local name ({@link TsonRoute#response()},
     * {@link #payloadType}). A plain alias reaching no extern is named as written, the alias in the schema declaring
     * it, though a TSON read of it asks the bind context for the entry the alias reaches.
     *
     * <p>Not every one is a type the application binds: a parameter record bound only to be placed, or a {@code
     * problem.details} the transport writes itself, as {@link TsonHttpException} does, may be named here and mapped
     * by nobody. {@link #boundClasses} is the filtered form, which is what a warm-up wants.
     */
    public Set<TsonPayloadType> payloadTypes() {
        return payloadTypes;
    }

    /**
     * {@code type}, a name a route of this description writes, where it is declared: past its extern to the foreign
     * entry it lands on, where it names one, and otherwise as written, a plain alias included. Its {@link
     * TsonPayloadType#schemaId()} is the schema a body of the type is read under by its own name, in either encoding.
     */
    public TsonPayloadType payloadType(TypeRef type) {
        Landed landed = Landed.of(linked, type.name());
        String identity = landed.identity();
        String declaring = landed.in().identity().equals(identity) ? landed.in().schema().id()
                : tson.schemaRegistry().getByCanonicalIdentity(identity).map(found -> found.schema().id())
                        .orElseThrow(() -> new IllegalStateException("'" + identity + "' declares '" + landed.name()
                                + "', a payload type of '" + schemaId + "', and is not resolved"));
        return new TsonPayloadType(declaring, landed.name());
    }

    /**
     * The Java classes {@code bindings} maps this description's payload types to -- what to warm at startup,
     * derived rather than listed. {@code bindings} is a name-only map, as {@code TsonBindings.of} takes, so each
     * payload type is looked up by {@link TsonPayloadType#name()}, the name a reader asks, never an extern's local name.
     *
     * <p>Hand-listing them is how a response type added to a description never gets warmed: nothing connects
     * the two lists, and the omission costs only latency, so nothing reports it. A type {@code bindings} does
     * not map is skipped rather than refused.
     */
    public List<Class<?>> boundClasses(Map<String, Class<?>> bindings) {
        return payloadTypes.stream().<Class<?>>map(type -> bindings.get(type.name())).filter(Objects::nonNull)
                .distinct().toList();
    }

    /**
     * The Java classes the description's own {@code Tson} binds its payload types to, each asked as a reader asks it,
     * by the schema declaring it and its name there: what to warm where the context tells schemas apart ({@code
     * TsonBindings.bySchema}), so two versions' {@code order} warm two classes. A type nothing binds is skipped
     * rather than refused.
     */
    public List<Class<?>> boundClasses() {
        DataNameBinder binder = tson.dataBindContext().nameBinder();
        List<Class<?>> bound = new ArrayList<>();
        for (TsonPayloadType type : payloadTypes) {
            try {
                bound.add(binder.resolve(type.identity(), type.name()));
            } catch (DataBindException unbound) {
                // a payload type nobody binds: placed, read as a tree, or written by the transport itself
            }
        }
        return bound.stream().distinct().toList();
    }
}
