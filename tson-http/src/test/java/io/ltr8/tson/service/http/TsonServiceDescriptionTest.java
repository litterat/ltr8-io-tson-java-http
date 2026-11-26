package io.ltr8.tson.service.http;

import io.ltr8.tson.service.protocol.Exchange;
import io.ltr8.tson.service.protocol.ExchangeName;
import io.ltr8.tson.service.protocol.Protocol;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.service.TsonBindings;
import io.ltr8.tson.service.http.RequestPlacement.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A protocol declared on its own, and a web service in a second document that maps it, read through {@link
 * TsonServiceDescription}.
 *
 * <p>A protocol is a record whose {@code exchanges} map exchange names to exchanges; an http projection is a record
 * whose {@code resources} map paths to verbs, beside its claims {@code binds} and {@code not_bound}. Every name in a
 * description is a reference the resolver judges, so a misspelt exchange, a reference to the wrong kind of thing, a
 * {@code binds} naming no protocol and an exemption naming no exchange are refused at load, pinned here beside the
 * rule that an endpoint is a {@code !service.operation} or a {@code !service.binding}. What relates two things is the
 * reader's: the placement of each exchange's request record, an exchange of a protocol the projection does not bind,
 * a container in a path, a verb or a status against the exchange, and the {@code binds} claim held to -- every
 * exchange bound or exempted with a reason.
 *
 * <p>The last probe is the alternative model kept for comparison: an exchange as a <em>type</em> under plain
 * {@code meta.tn}, composing into an operation with fixed binding fields, and the cost that shows in its values.
 */
class TsonServiceDescriptionTest {

    static final String ERR_ID = "https://schemas.example.com/2026/40/app/orders-errors-1.tn";

    /** Business errors, each fixing the status it inherits from {@code problem.details}. */
    static final String ERRORS = """
            !!id:"%s"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !!import:"%s"
            !schema {
              sku_not_found   => problem.details & { status: = 404  sku: text }
              order_not_found => problem.details & { status: = 404 }
            }""".formatted(ERR_ID, TsonProblemSchema.ID);

    static final String RECEIPT_ID = "https://schemas.example.com/2026/40/app/receipt-1.tn";

    /** A schema nothing imports, reached only by an extern a test writes. */
    static final String RECEIPT = """
            !!id:"%s"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              receipt => { order_id: text  total: int32 }
            }""".formatted(RECEIPT_ID);

    static final String PROTOCOL_ID = "https://schemas.example.com/2026/40/app/orders-1.tn";
    static final String API_ID = "https://schemas.example.com/2026/40/app/orders-api-1.tn";

    /** The protocol: four exchanges over four request records, nothing HTTP in sight. */
    static final String PROTOCOL = """
        !!id:"%s"
        !!import:"%s"
        !!import:"https://tson.io/2026/40/m/meta.tn"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !!import:"%s"
        !schema {
          order       => { sku: text  quantity: int32 }
          order_ref   => { id: text }
          order_query => { status?: text  page?: int32 ~ 1  page_size?: int32 ~ 20 }
          order_page  => { items: [order]  next_page?: int32 }
          new_order   => { order: order  idempotency_key?: text }

          orders => !service.protocol {
            exchanges: {
              @doc:"Accept an order and confirm it with the quantity doubled."
              place_order  => { request: new_order    response: order       errors: [sku_not_found] }
              @service.safe get_order    => { request: order_ref    response: order       errors: [order_not_found] }
              @service.safe list_orders  => { request: order_query  response: order_page }
              @service.idempotent cancel_order => { request: order_ref    errors: [order_not_found] }
            }
          }

          @doc:"Refunds, offered beside orders: a projection offering both binds both."
          orders_v2 => !service.protocol { exchanges: { @service.idempotent refund => { request: order_ref } } }

          @doc:"Declares a name orders also declares; a qualified name says which is meant."
          billing => !service.protocol {
            exchanges: { @service.safe get_order => { request: order_ref  response: order } }
          }

          bulk => !service.protocol {
            exchanges: { @service.safe page_orders => { request: order_page  response: order_page } }
          }

          order_search => { tags: [text]  status?: text }
          @doc:"A repeatable query parameter, and a second declaration of `list_orders`."
          search => !service.protocol {
            exchanges: { @service.safe search_orders => { request: order_search  response: order_page }
                         @service.safe list_orders   => { request: order_query  response: order_page } }
          }

          @doc:"An exchange whose effect cannot be undone, which no verb implies."
          purge => !service.protocol {
            exchanges: { @service.destructive purge_orders => { request: order_query  response: order_page } }
          }

          @doc:"An exchange claiming to be safe and destructive at once."
          contradictory => !service.protocol {
            exchanges: { @service.safe @service.destructive peek_purge => { request: order_query  response: order_page } }
          }

          @doc:"A request that is not a record at all, which the placement cannot distribute."
          scalars => !service.protocol { exchanges: { by_text => { request: text } } }

          @doc:"A dependent field: payload is read against the type kind names, per instance."
          typed_note => { note_id: text  kind: meta.type_ref  payload: kind! }
          notes => !service.protocol {
            exchanges: { put_note                 => { request: typed_note  response: order }
                         @service.safe find_notes => { request: typed_note  response: order_page } }
          }

          @doc:"A dependent field whose sibling the sender cannot choose freely: pinned, and a discriminator."
          pinned_note => { note_id: text  kind?: meta.type_ref = order  payload: kind! }
          note_base   => abstract { kind: meta.type_ref =?  payload: kind! }
          order_note  => note_base & { kind?: = order }
          ref_note    => note_base & { kind?: = order_ref }
          narrowed => !service.protocol {
            exchanges: { put_pinned => { request: pinned_note  response: order }
                         put_based  => { request: note_base    response: order } }
          }
        }""".formatted(PROTOCOL_ID, TsonServiceSchema.ID, ERR_ID);

    /**
     * The web service, in a second document: the same four exchanges, each looking as different from its exchange
     * as HTTP wants -- a body plus a header, a path segment, a query string, a bare DELETE.
     */
    static final String API = api("""
          orders_http => !service.http {
            binds: [orders]
            resources: {
              "/orders" => {
                @doc:"Place an order."
                POST => !service.binding { exchange: orders.exchanges.place_order  status: 201
                                           body: order  headers: { idempotency_key => "Idempotency-Key" } }
                GET  => !service.binding { exchange: orders.exchanges.list_orders }
              }
              "/orders/{id}" => {
                GET    => !service.binding { exchange: orders.exchanges.get_order }
                DELETE => !service.binding { exchange: orders.exchanges.cancel_order  status: 204 }
              }
            }
          }
        """);

    static String api(String entries) {
        return """
        !!id:"%s"
        !!import:"%s"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !!import:"%s"
        !schema {
        %s
        }""".formatted(API_ID, TsonServiceSchema.ID, PROTOCOL_ID, entries);
    }

    /**
     * A projection {@code a} binding {@code binds}, exempting each of {@code notBound} (each an exchange's qualified
     * name, exempted "elsewhere"), with {@code resources} as its map of paths.
     */
    static String projection(String binds, List<String> notBound, String resources) {
        StringBuilder exemptions = new StringBuilder();
        for (String exchange : notBound) {
            exemptions.append(" { exchange: ").append(exchange).append("  reason: \"elsewhere\" }");
        }
        return api("a => !service.http {\n  binds: [" + binds + "]\n"
                + (notBound.isEmpty() ? "" : "  not_bound: [" + exemptions + " ]\n")
                + "  resources: { " + resources + " }\n}");
    }

    static Tson tson(String api) {
        return tson(api, Map.of());
    }

    /** {@link #tson(String)} binding {@code application}'s classes beside the vocabulary's. */
    static Tson tson(String api, Map<String, Class<?>> application) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.putAll(TsonServiceSchema.publishedById());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        lib.put(ERR_ID, ERRORS);
        lib.put(RECEIPT_ID, RECEIPT);
        lib.put(PROTOCOL_ID, PROTOCOL);
        lib.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))
                .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindingsWith(application))));
    }

    /** Resolves the http document and reads its description; the resolver's verdict is asserted clean first. */
    static TsonServiceDescription routes(String api) {
        Tson tson = tson(api);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);
        return TsonServiceSchema.describedBy(tson, API_ID);
    }

    /**
     * Where the flexibility lives: one request record per exchange, distributed by the binding. A body that is
     * one field unwrapped beside a header; a path segment; a GET whose remainder is the query string; a DELETE
     * with nothing left over. And the projection's {@code binds} claim holds: all four exchanges are bound.
     */
    @Test
    void aProtocolAndTheWebServiceThatMapsIt() {
        TsonServiceDescription routes = routes(API);
        assertEquals(4, routes.routes().size());

        Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new ExchangeName("orders", "place_order")), create.exchange());
        assertEquals("orders.exchanges.place_order", create.exchange().orElseThrow().toString());
        assertEquals("new_order", create.request().orElseThrow().name());   // borrowed from the exchange
        assertEquals(201, create.status());
        assertEquals(List.of("order"), create.placement().at(Location.BODY));
        assertEquals(List.of("idempotency_key"), create.placement().at(Location.HEADER));

        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Map.of("id", Location.PATH), fetch.placement().fields());

        Route search = routes.route(HttpVerb.GET, "/orders").orElseThrow();
        assertEquals(List.of("status", "page", "page_size"), search.placement().at(Location.QUERY));

        Route cancel = routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow();
        assertEquals(Map.of("id", Location.PATH), cancel.placement().fields());
        assertEquals("order_not_found", cancel.errorFor(404).orElseThrow().name());
    }

    /**
     * The claims are fields the resolver judged, and each reference arrives delivered: a name in {@code binds} is a
     * {@link ValueRef} whose {@code get()} is the bound {@link Protocol}, the very value of the protocol's entry, and
     * a binding's exchange one whose {@code get()} is the {@link Exchange} under its key. So the reader takes the
     * protocol from the claim and the exchange from the binding, and finds neither by name.
     */
    @Test
    void aClaimAndABindingArriveDeliveredTheirReferents() {
        Tson tson = tson(API);
        assertEquals(List.of(), tson.validateSchema(API));
        Http http = assertInstanceOf(Http.class, ((ValueEntry) tson.schemaRegistry().get(API_ID).orElseThrow().schema()
                .entries().get("orders_http")).value());
        assertEquals(List.of(), http.notBound());
        ValueRef<Protocol> orders = http.binds().getFirst();
        assertEquals("orders", orders.name());
        assertTrue(orders.isResolved());
        Protocol declared = assertInstanceOf(Protocol.class, ((ValueEntry) tson.schemaRegistry().get(PROTOCOL_ID)
                .orElseThrow().schema().entries().get("orders")).value());
        assertSame(declared, orders.get());
        assertEquals(List.of("place_order", "get_order", "list_orders", "cancel_order"),
                List.copyOf(orders.get().exchanges().keySet()));

        ValueRef<Exchange> place = ((Binding) http.resources().get("/orders").endpoints().get("POST")).exchange();
        assertEquals("orders.exchanges.place_order", place.name());
        assertTrue(place.isResolved());
        assertSame(declared.exchanges().get("place_order"), place.get());
    }

    /**
     * A web service on its own terms: no protocol, every endpoint a {@code !service.operation} with its signature
     * inline.
     */
    @Test
    void aProjectionWithNoProtocolCarriesItsSignaturesInline() {
        TsonServiceDescription routes = routes(api("""
              standalone => !service.http {
                resources: {
                  "/orders/{id}" => { @service.safe GET => !service.operation { request: order_ref  response: order } }
                }
              }
            """));

        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.empty(), fetch.exchange());
        assertEquals(Map.of("id", Location.PATH), fetch.placement().fields());
    }

    /** {@code binds} is a claim: an exchange with no endpoint fails, and a reasoned exemption passes. */
    @Test
    void theBindsClaimIsHeldTo() {
        String post = "\"/orders\" => { POST => !service.binding { exchange: orders.exchanges.place_order } }";
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("orders", List.of(),
                post))).getMessage();
        assertTrue(refused.contains("[orders.exchanges.get_order, orders.exchanges.list_orders, "
                + "orders.exchanges.cancel_order]") && refused.contains("not_bound"), refused);

        routes(api("""
              orders_http => !service.http {
                binds: [orders]
                not_bound: [ { exchange: orders.exchanges.get_order     reason: "served by the read replica" }
                             { exchange: orders.exchanges.list_orders   reason: "not built yet" }
                             { exchange: orders.exchanges.cancel_order  reason: "not built yet" } ]
                resources: { "/orders" => { POST => !service.binding { exchange: orders.exchanges.place_order } } }
              }
            """));
    }

    static final String SAFE_GET = "\"/o\" => { @service.safe GET => !service.operation { request: order_ref "
            + " response: order } }";

    /**
     * {@code binds} names protocols, and the resolver judges each name at schema load: a name nothing declares, a
     * type, and a value of another record type are each refused before any reader runs, naming the slot.
     */
    @Test
    void aBindsNamingNoProtocolIsRefusedAtLoad() {
        assertEquals("'a' at /binds/0 names 'no_such', and 'no_such' is no entry of this schema or its imports -- "
                + "the first segment names the entry a value is reached through, and a member is reached through its "
                + "entry", only(projection("no_such", List.of(), SAFE_GET)));

        assertEquals("'a' at /binds/0 wants a 'service.protocol', and 'order' is an entry whose body is built by "
                + "'meta.record', which is no 'service.protocol'", only(projection("order", List.of(), SAFE_GET)));

        String other = projection("other", List.of(), SAFE_GET).replace("a => !service.http",
                "other => !service.http { resources: { \"/p\" => { @service.safe GET => !service.operation { "
                        + "request: order_ref  response: order } } } }\na => !service.http");
        assertEquals("'a' at /binds/0 wants a 'service.protocol', and 'other' is an entry whose body is built by "
                + "'service.http', which is no 'service.protocol'", only(other));
    }

    /**
     * An endpoint is a {@code !service.operation}, a {@code !service.binding} or a {@code !service.document}, and the
     * tag says which: the base is {@code abstract}, so an untagged value, and one tagged with the base, is refused by
     * the resolver naming the subtypes, and an exchange on a {@code !service.operation} is a field it does not have.
     * The one-or-the-other rule needs no reader.
     */
    @Test
    void anEndpointIsTaggedOperationOrBinding() {
        String untagged = only(api("""
              a => !service.http { resources: { "/orders" => { POST => { exchange: place_order } } } }
            """));
        assertTrue(untagged.contains("'service.endpoint' is abstract and has no direct instances, so a value here "
                + "must name its type -- one of (service.document | service.operation | service.binding)"), untagged);

        String exchangeOnAnOperation = only(api("""
              a => !service.http {
                resources: { "/orders" => { POST => !service.operation { exchange: place_order  request: order } } }
              }
            """));
        assertTrue(exchangeOnAnOperation.contains("unknown field 'exchange' on 'service.operation'"), exchangeOnAnOperation);

        String bareBase = only(api("""
              a => !service.http { resources: { "/orders" => { POST => !service.endpoint { status: 201 } } } }
            """));
        assertTrue(bareBase.contains("a tag naming 'service.endpoint' selects nothing -- it is abstract and has no "
                + "direct instances"), bareBase);
    }

    /**
     * A misspelt exchange on a binding is the resolver's to refuse, through the three-segment name, naming the keys
     * the protocol does hold.
     */
    @Test
    void aMisspeltExchangeIsRefusedByTheResolver() {
        String refused = only(projection("orders", List.of(),
                "\"/orders\" => { POST => !service.binding { exchange: orders.exchanges.plaec_order } }"));
        assertTrue(refused.contains("'orders.exchanges' has no key 'plaec_order' -- it holds place_order, get_order"),
                refused);
    }

    /**
     * An exchange is reached through its protocol's {@code exchanges} field, and the two-segment name that skips it is
     * refused at load naming the fields a protocol declares, which tells an author migrating a document the remedy.
     */
    @Test
    void aTwoSegmentExchangeNameIsRefusedNamingTheField() {
        assertEquals("'a' at /resources/~1orders/endpoints/POST/exchange names 'orders.place_order', and 'orders' is a "
                + "'service.protocol', which has no field 'place_order' -- it declares exchanges",
                only(projection("orders", List.of(),
                        "\"/orders\" => { POST => !service.binding { exchange: orders.place_order } }")));
    }

    /** A binding's reference names the kind of thing its slot wants: a protocol where an exchange is wanted is refused. */
    @Test
    void aReferenceToTheWrongKindOfThingIsRefusedByTheResolver() {
        String notAnExchange = only(api("""
              a => !service.http { resources: { "/orders" => { POST => !service.binding { exchange: orders } } } }
            """));
        assertTrue(notAnExchange.contains("wants a 'service.exchange'")
                && notAnExchange.contains("'orders' is an entry whose body is built by 'service.protocol'"), notAnExchange);
    }

    /** A sound reference to an exchange of a protocol the projection does not bind is the reader's to refuse. */
    @Test
    void anExchangeOfAProtocolNotBoundIsRefusedByTheReader() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("orders", List.of(),
                "\"/orders/{id}\" => { GET => !service.binding { exchange: billing.exchanges.get_order } }")))
                .getMessage();
        assertTrue(refused.contains("'billing.exchanges.get_order'") && refused.contains("does not bind")
                && refused.contains("[orders]"), refused);
    }

    /**
     * Two bound protocols declaring one name are not ambiguous: the qualified name says which protocol declares
     * the exchange a binding borrows.
     */
    @Test
    void aQualifiedNameSaysWhichProtocol() {
        TsonServiceDescription routes = routes(projection("orders billing",
                List.of("orders.exchanges.place_order", "orders.exchanges.get_order", "orders.exchanges.list_orders",
                        "orders.exchanges.cancel_order"),
                "\"/orders/{id}\" => { GET => !service.binding { exchange: billing.exchanges.get_order } }"));
        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.of(new ExchangeName("billing", "get_order")), fetch.exchange());
        assertEquals("order_ref", fetch.request().orElseThrow().name());
    }

    /** A URL segment cannot carry a record: the limit a parameter list could only state, checked. */
    @Test
    void aContainerFieldCannotRideInThePath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("bulk", List.of(),
                "\"/orders/{items}\" => { GET => !service.binding { exchange: bulk.exchanges.page_orders } }")))
                .getMessage();

        assertTrue(refused.contains("'items'") && refused.contains("carries exactly one scalar"), refused);
    }

    /**
     * A request is the record whose fields an endpoint distributes, so one that is not a record is refused where
     * it is read. Left to fall through it places nothing and reads as a working endpoint carrying no data.
     */
    @Test
    void aRequestThatIsNotARecordIsRefused() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("scalars", List.of(),
                "\"/things\" => { POST => !service.binding { exchange: scalars.exchanges.by_text } }")))
                .getMessage();

        assertTrue(refused.contains("'text'") && refused.contains("not a record"), refused);
    }

    /**
     * A dependent field's type is chosen per instance by its sibling, so it travels in the remainder body beside
     * that sibling, the one place a single record holds both. Placed in the path, a header or the query, named as
     * the whole body, or left to a remainder that goes to the query, it is refused; so is its sibling placed
     * anywhere but the remainder body.
     */
    @Test
    void aDependentFieldTravelsInTheRemainderBodyWithItsSibling() {
        List<String> exempt = List.of("notes.exchanges.find_notes");
        TsonServiceDescription routes = routes(projection("notes", exempt,
                "\"/notes/{note_id}\" => { POST => !service.binding { exchange: notes.exchanges.put_note } }"));
        RequestPlacement placement = routes.route(HttpVerb.POST, "/notes/{note_id}").orElseThrow().placement();
        assertEquals(List.of("kind", "payload"), placement.at(Location.BODY));
        assertEquals(List.of("note_id"), placement.at(Location.PATH));

        String inPath = refusedPlacement(exempt,
                "\"/notes/{payload}\" => { POST => !service.binding { exchange: notes.exchanges.put_note } }");
        assertTrue(inPath.contains("places 'payload' in the PATH, but 'payload: kind!' is a dependent field")
                && inPath.contains("travels in the remainder body with 'kind'"), inPath);

        String wholeBody = refusedPlacement(exempt,
                "\"/notes\" => { POST => !service.binding { exchange: notes.exchanges.put_note  body: payload } }");
        assertTrue(wholeBody.contains("places 'payload' as the whole body"), wholeBody);

        String sibling = refusedPlacement(exempt, "\"/notes\" => { POST => !service.binding { "
                + "exchange: notes.exchanges.put_note  headers: { kind => \"X-Kind\" } } }");
        assertTrue(sibling.contains("places 'kind', the sibling of 'payload', in the HEADER"), sibling);

        String query = refusedPlacement(List.of("notes.exchanges.put_note"),
                "\"/notes\" => { GET => !service.binding { exchange: notes.exchanges.find_notes } }");
        assertTrue(query.contains("places 'payload' in the QUERY as the remainder"), query);
    }

    /** Bind targets for the dependent-field requests: a dependent field binds to {@code Object}. */
    public record Order(String sku, int quantity) {
    }

    public record OrderRef(String id) {
    }

    public record TypedNote(String note_id, TypeRef kind, Object payload) {
    }

    public record PinnedNote(String note_id, TypeRef kind, Object payload) {
    }

    public sealed interface NoteBase permits OrderNote, RefNote {
    }

    public record OrderNote(TypeRef kind, Object payload) implements NoteBase {
    }

    public record RefNote(TypeRef kind, Object payload) implements NoteBase {
    }

    /** Resolves {@code api} under {@code application}'s bindings and reads its description. */
    private static TsonServiceDescription boundRoutes(String api, Map<String, Class<?>> application) {
        Tson tson = tson(api, application);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);
        return TsonServiceSchema.describedBy(tson, API_ID);
    }

    /**
     * A dependent field lets the sender name the type its value is read against, and in bind mode a named type no
     * class binds is a 500 at the read -- a server-fault status the client chose. So a bound request record holding
     * one loads only if every type the sender may name binds. A free {@code meta.type_ref} sibling admits the whole
     * closure, the kernel's records among them, so it never does; a pinned sibling offers its pin, and a
     * discriminator the pins of its subtypes, which the application binds or the description does not load.
     */
    @Test
    void aBoundDependentFieldLoadsOnlyIfEveryTypeASenderMayNameBinds() {
        Map<String, Class<?>> bound = new LinkedHashMap<>(Map.of("order", Order.class, "order_ref", OrderRef.class,
                "typed_note", TypedNote.class, "pinned_note", PinnedNote.class, "note_base", NoteBase.class,
                "order_note", OrderNote.class, "ref_note", RefNote.class));
        String free = projection("notes", List.of("notes.exchanges.find_notes"),
                "\"/notes/{note_id}\" => { POST => !service.binding { exchange: notes.exchanges.put_note } }");
        String refused = assertThrows(IllegalArgumentException.class, () -> boundRoutes(free, bound)).getMessage();
        assertTrue(refused.contains("'POST /notes/{note_id}' takes 'typed_note', whose dependent field 'payload: kind!'"
                + " is read against the type a sender names in 'kind', and it may name types this server cannot"
                + " bind:"), refused);
        assertTrue(refused.contains("(the kernel's vocabulary)") && refused.contains("pin 'kind'"), refused);
        boundRoutes(free, Map.of("order", Order.class));                // unbound, read as a tree: not checked

        String narrowed = projection("narrowed", List.of(),
                "\"/pinned/{note_id}\" => { POST => !service.binding { exchange: narrowed.exchanges.put_pinned } }\n"
                        + "  \"/based\" => { POST => !service.binding { exchange: narrowed.exchanges.put_based } }");
        boundRoutes(narrowed, bound);

        Map<String, Class<?>> noRef = new LinkedHashMap<>(bound);
        noRef.remove("order_ref");
        String discriminated = assertThrows(IllegalArgumentException.class, () -> boundRoutes(narrowed, noRef))
                .getMessage();
        assertTrue(discriminated.contains("'POST /based' takes 'note_base'")
                && discriminated.contains("bind: 'order_ref' (no class binds it) --"), discriminated);

        Map<String, Class<?>> noOrder = new LinkedHashMap<>(bound);
        noOrder.remove("order");
        String pinned = assertThrows(IllegalArgumentException.class, () -> boundRoutes(narrowed, noOrder))
                .getMessage();
        assertTrue(pinned.contains("'POST /pinned/{note_id}' takes 'pinned_note'")
                && pinned.contains("bind: 'order' (no class binds it) --"), pinned);
    }

    private static String refusedPlacement(List<String> exempt, String path) {
        return assertThrows(IllegalArgumentException.class, () -> routes(projection("notes", exempt, path)))
                .getMessage();
    }

    /**
     * A query parameter and a header may repeat, so either admits an array of scalars -- {@code ?tag=a&tag=b} is
     * ordinary HTTP, and refusing it would push a multi-valued parameter into a body no GET can carry. An array
     * of records still has no spelling, and neither has an array in a path segment.
     */
    @Test
    void aRepeatedQueryValueIsAnArrayOfScalars() {
        TsonServiceDescription routes = routes(projection("search", List.of("search.exchanges.list_orders"),
                "\"/search\" => { GET => !service.binding { exchange: search.exchanges.search_orders } }"));
        assertEquals(List.of("tags", "status"),
                routes.route(HttpVerb.GET, "/search").orElseThrow().placement().at(Location.QUERY));

        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("bulk", List.of(),
                "\"/pages\" => { GET => !service.binding { exchange: bulk.exchanges.page_orders } }"))).getMessage();
        assertTrue(refused.contains("'items'") && refused.contains("array of order"), refused);
    }

    /**
     * The projection checks its verb against what the exchange claims, and never derives one from the other: a GET must
     * be safe, a PUT or DELETE idempotent, and {@code @service.safe} implies {@code @service.idempotent}. The mismatch
     * is the one a client cannot see and a cache will act on.
     */
    @Test
    void aVerbMustAgreeWithWhatTheExchangeClaims() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("orders", List.of(),
                "\"/orders/{id}\" => { GET => !service.binding { exchange: orders.exchanges.cancel_order } }")))
                .getMessage();
        assertTrue(refused.contains("GET is safe") && refused.contains("@service.safe"), refused);

        // A safe exchange is idempotent too, so DELETE takes one without saying so twice.
        routes(projection("orders", List.of("orders.exchanges.place_order", "orders.exchanges.list_orders",
                        "orders.exchanges.cancel_order"),
                "\"/orders/{id}\" => { DELETE => !service.binding { exchange: orders.exchanges.get_order  status: 204 } }"));
    }

    /**
     * The facts an exchange states about itself arrive on every route binding it, resolved: {@code @service.safe}
     * implies {@code @service.idempotent}, and {@code @service.destructive} is its own fact, derived from no verb --
     * here a POST, where nothing about the verb says so.
     */
    @Test
    void anExchangesFactsArriveOnItsRoute() {
        TsonServiceDescription orders = routes(API);
        Route fetch = orders.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertTrue(fetch.safe() && fetch.idempotent() && !fetch.destructive(), "safe implies idempotent");
        Route cancel = orders.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow();
        assertTrue(!cancel.safe() && cancel.idempotent() && !cancel.destructive());
        Route place = orders.route(HttpVerb.POST, "/orders").orElseThrow();
        assertTrue(!place.safe() && !place.idempotent() && !place.destructive());

        Route purge = routes(projection("purge", List.of(),
                "\"/purge\" => { POST => !service.binding { exchange: purge.exchanges.purge_orders } }"))
                .route(HttpVerb.POST, "/purge").orElseThrow();
        assertTrue(purge.destructive() && !purge.safe() && !purge.idempotent());

        // On an inline operation the marker sits before the verb key, and arrives the same way.
        Route inline = routes(api("""
              standalone => !service.http { resources: { "/purge" => {
                @service.destructive POST => !service.operation { request: order_query  response: order_page } } } }
            """)).route(HttpVerb.POST, "/purge").orElseThrow();
        assertTrue(inline.destructive());
    }

    /**
     * {@code @service.safe} and {@code @service.destructive} exclude each other: a safe exchange has no effect to
     * undo. Refused on an exchange of a protocol the projection binds, named by its qualified name, and on an inline
     * operation's verb key, named by its endpoint.
     */
    @Test
    void anExchangeMarkedSafeAndDestructiveIsRefused() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("contradictory",
                List.of(), "\"/peek\" => { POST => !service.binding { exchange: contradictory.exchanges.peek_purge } }")))
                .getMessage();
        assertEquals("'contradictory.exchanges.peek_purge' is marked both @service.safe and @service.destructive, "
                + "which exclude each other -- a safe exchange has no effect to undo; drop one", refused);

        String inline = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              standalone => !service.http { resources: { "/peek" => {
                @service.safe @service.destructive POST => !service.operation { request: order_query } } } }
            """))).getMessage();
        assertEquals("'standalone POST /peek' is marked both @service.safe and @service.destructive, which exclude "
                + "each other -- a safe exchange has no effect to undo; drop one", inline);
    }

    /**
     * A destructive exchange is refused at GET or HEAD, as an unsafe one is: no verb implies {@code
     * @service.destructive}, and a safe verb denies it.
     */
    @Test
    void aDestructiveExchangeIsRefusedAtASafeVerb() {
        for (String verb : List.of("GET", "HEAD")) {
            String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("purge", List.of(),
                    "\"/purge\" => { " + verb + " => !service.binding { exchange: purge.exchanges.purge_orders } }")))
                    .getMessage();
            assertEquals("'a " + verb + " /purge': " + verb + " is safe, but the exchange is marked "
                    + "@service.destructive -- a destructive exchange is bound to a verb that is not safe", refused);
        }
    }

    /** A response-less endpoint answers with no body, and the default 200 says otherwise. */
    @Test
    void aResponselessEndpointCannotAnswer200() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(projection("orders", List.of(),
                "\"/orders/{id}\" => { DELETE => !service.binding { exchange: orders.exchanges.cancel_order } }")))
                .getMessage();
        assertTrue(refused.contains("declares no response") && refused.contains("204"), refused);
    }

    /**
     * Coverage is per (protocol, exchange): two bound protocols may both declare {@code list_orders}, and binding
     * one says nothing about the other; and a projection binding {@code orders} and {@code orders_v2} claims the
     * exchanges of each, since a protocol lists its own. An exemption is held as hard as a binding: one naming an
     * exchange of a protocol the projection does not bind is the reader's to refuse.
     */
    @Test
    void theClaimIsHeldPerProtocolNotPerName() {
        // orders' list_orders is bound and search's exempted; neither stands in for the other.
        routes(projection("orders search", List.of("orders.exchanges.place_order", "orders.exchanges.get_order",
                        "orders.exchanges.cancel_order", "search.exchanges.search_orders", "search.exchanges.list_orders"),
                "\"/orders\" => { GET => !service.binding { exchange: orders.exchanges.list_orders } }"));

        String twoProtocols = projection("orders orders_v2", List.of("orders.exchanges.get_order",
                        "orders.exchanges.list_orders", "orders.exchanges.cancel_order"),
                "\"/orders\" => { POST => !service.binding { exchange: orders.exchanges.place_order  status: 201 } }");
        String refund = assertThrows(IllegalArgumentException.class, () -> routes(twoProtocols)).getMessage();
        assertTrue(refund.contains("[orders_v2.exchanges.refund]"), refund);
        routes(twoProtocols.replace("resources: {", "resources: { \"/orders/{id}/refund\" => { "
                + "PUT => !service.binding { exchange: orders_v2.exchanges.refund  status: 204 } }"));

        String elsewhere = projection("orders", List.of("billing.exchanges.get_order"),
                "\"/orders\" => { POST => !service.binding { exchange: orders.exchanges.place_order } }");
        String notClaimed = assertThrows(IllegalArgumentException.class, () -> routes(elsewhere))
                .getMessage();
        assertTrue(notClaimed.contains("exempts 'billing.exchanges.get_order'")
                && notClaimed.contains("not an exchange of a protocol it binds"), notClaimed);
    }

    /**
     * An exemption's {@code exchange} is a reference the resolver judges at load, as a binding's is: a key the
     * protocol does not hold, the two-segment name that skips the {@code exchanges} field, and a token that is no name
     * at all are each refused before any reader runs.
     */
    @Test
    void anExemptionNamingNoExchangeIsRefusedAtLoad() {
        String post = "\"/orders\" => { POST => !service.binding { exchange: orders.exchanges.place_order } }";
        assertEquals("'a' at /not_bound/0/exchange names 'orders.exchanges.no_such', and 'orders.exchanges' has no key "
                + "'no_such' -- it holds place_order, get_order, list_orders, cancel_order",
                only(projection("orders", List.of("orders.exchanges.no_such"), post)));

        assertEquals("'a' at /not_bound/0/exchange names 'orders.place_order', and 'orders' is a 'service.protocol', "
                + "which has no field 'place_order' -- it declares exchanges",
                only(projection("orders", List.of("orders.place_order"), post)));

        assertEquals("'a' at /not_bound/0/exchange holds '42', which is no qualified name -- '42' is no name: '42': "
                + "U+0034 at index 0 cannot start an identifier -- an identifier never begins with a digit or a sign",
                only(projection("orders", List.of("42"), post)));
    }

    /**
     * A header name is held folded, as RFC 9110 §5.1 compares it: {@code header_name} is a case-folding text
     * family, and the binding written {@code Idempotency-Key} reads back {@code idempotency-key}.
     */
    @Test
    void aHeaderNameIsHeldFolded() {
        Tson tson = tson(API);
        assertEquals(List.of(), tson.validateSchema(API));
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        Resource orders = ((Http) ((ValueEntry) entries.get("orders_http")).value()).resources().get("/orders");
        assertEquals(Map.of("idempotency_key", "idempotency-key"), orders.endpoints().get("POST").headers());
    }

    /** Each borrowed grammar holds at its key, naming its role: a path, a header name, an exchange name. */
    @Test
    void theBorrowedGrammarsHoldAtTheKey() {
        String notAPath = only(api("""
              a => !service.http { resources: { "orders" => { GET => !service.operation { request: order_ref } } } }
            """));
        assertTrue(notAPath.contains("'service.path_template'") && notAPath.contains("'orders'"), notAPath);

        String notAToken = only(api("""
              a => !service.http { resources: { "/orders" => {
                POST => !service.operation { request: new_order  headers: { idempotency_key => "Idempotency Key" } } } } }
            """));
        assertTrue(notAToken.contains("'service.header_name'"), notAToken);

        String notAnExchangeName = only(api("""
              x => !service.protocol { exchanges: { "place order" => { request: new_order } } }
            """));
        assertTrue(notAnExchangeName.contains("'service.exchange_name': 'place order'"), notAnExchangeName);
    }

    /**
     * A header name and an authentication scheme are folded under {@code ASCII_CASEFOLD}, which maps {@code A} to
     * {@code Z} and nothing else, and the {@code pattern} judges the folded value ([TSON-SCHEMA] §5.5): {@code
     * Content-Type} reads as {@code content-type}, while a name spelt with a fullwidth letter or a long s stays outside
     * the token grammar and is refused, in a response header and in a request field's header alike, where a
     * compatibility fold would have brought either into ASCII and admitted it.
     */
    @Test
    void aHeaderNameIsFoldedInAsciiAndOneACompatibilityFoldWouldAdmitIsRefused() {
        Route create = routes(api("""
              a => !service.http { resources: { "/orders" => {
                POST => !service.operation { request: order  response: order  status: 201
                        response_headers: { Content-Type => "application/tson" } } } } }
            """)).route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Map.of("content-type", "application/tson"), create.responseHeaders());

        for (String name : List.of("\uFF23ontent-Type", "Acce\u017Fs-Key")) {
            String asResponseHeader = only(api("a => !service.http { resources: { \"/orders\" => {\n"
                    + "  POST => !service.operation { request: order  response: order  status: 201\n"
                    + "          response_headers: { \"" + name + "\" => \"x\" } } } } }"));
            assertTrue(asResponseHeader.contains("'service.header_name'"), asResponseHeader);

            String asRequestHeader = only(api("a => !service.http { resources: { \"/orders\" => {\n"
                    + "  POST => !service.operation { request: new_order  headers: { idempotency_key => \"" + name
                    + "\" } } } } }"));
            assertTrue(asRequestHeader.contains("'service.header_name'"), asRequestHeader);
        }

        String scheme = only(api("bearer => !service.http_auth { scheme: \"\uFF22earer\" }"));
        assertTrue(scheme.contains("'service.auth_scheme'"), scheme);
    }

    /**
     * <b>A schema an extern names is published with the imports.</b> It loads with them though none of its names
     * enters the description's namespace ([TSON-SCHEMA] §5.13), so a client resolving the description fetches it,
     * and {@code referencedSchemas()} walks each schema's dependencies, imports then externs' schemas, rather than its
     * imports alone. Here the receipt schema is reached by the extern and nothing else.
     */
    @Test
    void aSchemaAnExternNamesIsReferenced() {
        TsonServiceDescription described = routes(api("""
                  receipt_v1 => !meta.extern { schema: "%s"  type: receipt }
                  receipts => !service.protocol {
                    exchanges: { @service.safe get_receipt => { request: order_ref  response: receipt_v1 } }
                  }
                  receipts_http => !service.http {
                    binds: [receipts]
                    resources: {
                      "/receipts/{id}" => { GET => !service.binding { exchange: receipts.exchanges.get_receipt } }
                    }
                  }
                """.formatted(RECEIPT_ID)));

        assertEquals(List.of(API_ID, TsonServiceSchema.ID, PROTOCOL_ID, ERR_ID, TsonProblemSchema.ID, RECEIPT_ID),
                List.copyOf(described.referencedSchemas()));
    }

    // ── security schemes and response headers ──

    /** Two schemes, declared as value entries at the root, each tagged with the kind of scheme it is. */
    static final String SCHEMES = """
          bearer      => !service.http_auth { scheme: Bearer }
          partner_key => !service.api_key { in: query  name: api_key }
        """;

    /** A GET on {@code path} answering an order, with {@code extra} written into its operation. */
    static String safeGet(String path, String extra) {
        return "\"" + path + "\" => { @service.safe GET => !service.operation { request: order_ref  response: order"
                + (extra.isEmpty() ? "" : "  " + extra) + " } }";
    }

    /**
     * The schemes in force for an endpoint: its own {@code security} when it writes one, which replaces the
     * projection's; none when it writes {@code security: []}; the projection's when it writes none; and none when
     * neither does. Each name arrives delivered, so the route holds the bound schemes themselves.
     */
    @Test
    void anEndpointsSecurityReplacesItsProjectionsAndAnEmptyListIsOpen() {
        TsonServiceDescription routes = routes(api(SCHEMES + "secured => !service.http {\n  security: [bearer]\n"
                + "  resources: { " + safeGet("/a", "") + "\n  " + safeGet("/b", "security: [partner_key]") + "\n  "
                + safeGet("/c", "security: []") + " }\n}\n"
                + "open => !service.http { resources: { " + safeGet("/d", "") + " } }"));
        HttpAuth bearer = new HttpAuth("bearer");
        ApiKey partner = new ApiKey(ApiKeyIn.query, "api_key");

        assertEquals(List.of(bearer), routes.route(HttpVerb.GET, "/a").orElseThrow().security(),
                "inherited, the scheme name held folded");
        assertEquals(List.of(partner), routes.route(HttpVerb.GET, "/b").orElseThrow().security(), "replaced");
        assertEquals(List.of(), routes.route(HttpVerb.GET, "/c").orElseThrow().security(), "security: [] is open");
        assertEquals(List.of(), routes.route(HttpVerb.GET, "/d").orElseThrow().security(), "both absent is open");
    }

    /**
     * Absent and empty arrive apart: an endpoint writing no {@code security} reads it {@link Optional#empty()}, one
     * writing {@code security: []} a present empty list, and a reference is delivered the bound scheme.
     */
    @Test
    void anAbsentSecurityAndAnEmptyOneArriveApart() {
        String api = api(SCHEMES + "a => !service.http {\n  security: [bearer]\n  resources: { "
                + safeGet("/a", "") + "\n  " + safeGet("/c", "security: []") + " }\n}");
        Tson tson = tson(api);
        assertEquals(List.of(), tson.validateSchema(api));
        Http http = (Http) ((ValueEntry) tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().get("a"))
                .value();
        ValueRef<SecurityScheme> bearer = http.security().getFirst();
        assertTrue(bearer.isResolved());
        assertEquals(new HttpAuth("bearer"), bearer.get());
        assertEquals(Optional.empty(), http.resources().get("/a").endpoints().get("GET").security());
        assertEquals(Optional.of(List.of()), http.resources().get("/c").endpoints().get("GET").security());
    }

    /**
     * A name in {@code security} is a reference the resolver judges at load: a type and a protocol are each refused
     * there, naming the slot, on a projection and on an endpoint alike; a scheme tagged with the abstract base is
     * refused naming the subtypes, and without offering to omit the tag, since the base states no discriminators to
     * place an untagged value by.
     */
    @Test
    void aSecurityNamingNoSchemeIsRefusedAtLoad() {
        assertEquals("'a' at /security/0 wants a 'service.security_scheme', and 'order' is an entry whose body is "
                + "built by 'meta.record', which is no 'service.security_scheme'",
                only(api("a => !service.http { security: [order]  resources: { " + safeGet("/o", "") + " } }")));
        assertEquals("'a' at /security/0 wants a 'service.security_scheme', and 'orders' is an entry whose body is "
                + "built by 'service.protocol', which is no 'service.security_scheme'",
                only(api("a => !service.http { security: [orders]  resources: { " + safeGet("/o", "") + " } }")));

        String onAnEndpoint = only(api("a => !service.http { resources: { " + safeGet("/o", "security: [order]")
                + " } }"));
        assertTrue(onAnEndpoint.contains("/security/0 wants a 'service.security_scheme', and 'order'"), onAnEndpoint);

        assertEquals("'k': the body is no value of the type 'service.security_scheme' -- a tag naming "
                + "'service.security_scheme' selects nothing -- it is abstract and has no direct instances. Name the "
                + "member this value is, one of (service.http_auth | service.api_key)",
                only(api("raw => { scheme: Bearer }\nk => !service.security_scheme { }")));
    }

    /**
     * An api key's {@code name} is {@code text}, a query parameter's name being no header name, and one carried in a
     * header is held as a header name: folded under ASCII case folding, and refused at startup unless it is an RFC
     * 9110 token. A query parameter's name is kept as written.
     */
    @Test
    void aHeaderBorneApiKeyIsHeldAsAHeaderName() {
        TsonServiceDescription routes = routes(api("""
              header_key => !service.api_key { in: header  name: X-Api-Key }
              query_key  => !service.api_key { in: query  name: Api_Key }
            """ + "a => !service.http {\n  security: [header_key]\n  resources: { " + safeGet("/a", "") + "\n  "
                + safeGet("/b", "security: [query_key]") + " }\n}"));
        assertEquals(List.of(new ApiKey(ApiKeyIn.header, "x-api-key")),
                routes.route(HttpVerb.GET, "/a").orElseThrow().security());
        assertEquals("x-api-key", ((ApiKey) routes.route(HttpVerb.GET, "/a").orElseThrow().security().getFirst()).name());
        assertEquals("Api_Key", ((ApiKey) routes.route(HttpVerb.GET, "/b").orElseThrow().security().getFirst()).name(),
                "a query parameter's name is kept as written");

        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api(
                "bad => !service.api_key { in: header  name: \"X Api Key\" }\n"
                        + "a => !service.http {\n  security: [bad]\n  resources: { " + safeGet("/a", "") + " }\n}")))
                .getMessage();
        assertEquals("'a GET /a' accepts an api key in the header 'x api key', which is no RFC 9110 token, so no header "
                + "carries it -- a header name is ASCII letters, digits and !#$%&'*+.^_`|~-", refused);
    }

    /**
     * A response header is a literal or an RFC 6570 level-1 template over the response type's fields, and a server
     * expands it against the response value: a variable percent-encoded, a literal as it is.
     */
    @Test
    void aResponseHeaderTemplateExpandsFromTheResponseValue() {
        Route create = routes(api("""
              a => !service.http { resources: { "/orders" => {
                POST => !service.operation { request: order  response: order  status: 201
                        response_headers: { Location => "/orders/{sku}"  Cache-Control => "no-store" } } } } }
            """)).route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Map.of("location", "/orders/{sku}", "cache-control", "no-store"), create.responseHeaders(),
                "the names held folded, the templates as written");
        assertEquals(Map.of("location", "/orders/A%2F1%20%C3%A9", "cache-control", "no-store"),
                create.expandResponseHeaders(Map.of("sku", "A/1 é")::get));
        assertEquals("/orders/", create.expandResponseHeaders(field -> null).get("location"),
                "an absent value expands to nothing");
    }

    /**
     * A template's variables are checked against the response type at startup, as a request field's placement is: a
     * name the response does not declare, a field that is not one scalar, a variable on an endpoint with no response
     * type -- a document, or one answering no body -- and a form beyond level 1 are each refused.
     */
    @Test
    void aResponseHeaderTemplateIsCheckedAgainstTheResponseType() {
        assertEquals("'a GET /o' declares the response header 'location: /orders/{id}', but the response type 'order' "
                + "declares no field 'id' -- it declares [sku, quantity]", refusedHeader(safeGet("/o",
                "response_headers: { Location => \"/orders/{id}\" }")));

        assertEquals("'a GET /p' declares the response header 'link: /p/{items}', but 'items' of 'order_page' is an "
                + "array, and a header's variable expands from one scalar", refusedHeader("\"/p\" => { @service.safe "
                + "GET => !service.operation { request: order_query  response: order_page  response_headers: { "
                + "Link => \"/p/{items}\" } } }"));

        assertEquals("'a GET /page' declares the response header 'location: /x/{id}', but the endpoint has no "
                + "response type to expand 'id' from -- a document's header, or one answering no body, is a literal",
                refusedHeader("\"/page\" => { @service.safe GET => !service.document { media_types: [\"text/html\"]  "
                        + "response_headers: { Location => \"/x/{id}\" } } }"));

        assertEquals("'a GET /o' declares the response header 'location: /orders/{+sku}', which is no level-1 "
                + "template -- each expression is one variable of ASCII letters, digits and '_' in braces, '{id}', "
                + "with no operator: a value is an RFC 6570 level-1 template", refusedHeader(safeGet("/o",
                "response_headers: { Location => \"/orders/{+sku}\" }")));
    }

    private static String refusedHeader(String resources) {
        return assertThrows(IllegalArgumentException.class,
                () -> routes(api("a => !service.http { resources: { " + resources + " } }"))).getMessage();
    }

    /** The one diagnostic the resolver gives for {@code http}, or a failure naming them all. */
    static String only(String api) {
        List<Diagnostic> problems = tson(api).validateSchema(api);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    // ── a plain alias: judged as its target, named as written ──

    /**
     * Plain aliases within one schema, none reaching an extern, and the records whose fields they type: {@code sku}
     * aliases {@code text} and {@code sku_alias} aliases {@code sku}; {@code ref_alias2} aliases {@code ref_alias},
     * which aliases the protocol's record {@code order_ref}; {@code order_alias} a record, {@code orders_alias} an
     * array of records, {@code sku_missing} the business error {@code sku_not_found}, which fixes 404.
     */
    static final String ALIASES = """
          sku          => text
          sku_alias    => sku
          ref_alias    => order_ref
          ref_alias2   => ref_alias
          order_alias  => order
          orders_list  => [order]
          orders_alias => orders_list
          sku_missing  => sku_not_found
          by_sku       => { sku: sku_alias }
          by_order     => { id: order_alias }
          by_list      => { id: orders_alias }
          by_tags      => { id: text  tags: [order_alias] }
          by_skus      => { id: text  skus: [sku_alias] }
          reply        => { id: text  order: order_alias  orders: orders_alias  sku: sku_alias }
          reply_alias  => reply
        """;

    /** A description declaring {@link #ALIASES}, with one safe GET at {@code path} whose operation is {@code body}. */
    static String aliased(String path, String body) {
        return api(ALIASES + "a => !service.http { resources: { \"" + path + "\" => { @service.safe GET => "
                + "!service.operation { " + body + " } } } }");
    }

    private static String refusedAliased(String path, String body) {
        return assertThrows(IllegalArgumentException.class, () -> routes(aliased(path, body))).getMessage();
    }

    /**
     * <b>A payload type that is a plain alias is named as the description writes it.</b> {@code payloadTypes()} and
     * {@code payloadType(ref)} give the alias's own name in the schema declaring the alias, and both forms of {@code
     * boundClasses} look a class up by that name, so a map binding {@code order_alias} warms its class and one
     * binding only {@code order} warms nothing. The shape checks see past the alias; this naming is what they leave
     * alone.
     */
    @Test
    void aPayloadTypeThatIsAPlainAliasIsNamedAsWritten() {
        String api = aliased("/o/{id}", "request: order_ref  response: order_alias");
        TsonServiceDescription described = routes(api);

        assertEquals(List.of(new PayloadType(PROTOCOL_ID, "order_ref"), new PayloadType(API_ID, "order_alias")),
                List.copyOf(described.payloadTypes()));
        assertEquals(new PayloadType(API_ID, "ref_alias"), described.payloadType(TypeRef.of("ref_alias")));
        assertEquals(new PayloadType(API_ID, "ref_alias2"), described.payloadType(TypeRef.of("ref_alias2")));
        assertEquals(List.of(Order.class), described.boundClasses(Map.of("order_alias", Order.class)));
        assertEquals(List.of(), described.boundClasses(Map.of("order", Order.class)));

        assertEquals(List.of(Order.class), TsonServiceSchema.describedBy(boundTson(api, Map.of("order_alias",
                Order.class)), API_ID).boundClasses());
        assertEquals(List.of(), TsonServiceSchema.describedBy(boundTson(api, Map.of("order", Order.class)), API_ID)
                .boundClasses());
    }

    private static Tson boundTson(String api, Map<String, Class<?>> application) {
        Tson tson = tson(api, application);
        tson.resolve(api);
        return tson;
    }

    /**
     * A request that is a plain alias of a record is placed as the record, past every hop: {@code ref_alias2} reaches
     * {@code order_ref} through {@code ref_alias}, so its {@code id} binds the path segment. The route and {@code
     * payloadTypes()} still name the alias.
     */
    @Test
    void aRequestThatIsAPlainAliasOfARecordIsPlacedAsTheRecord() {
        TsonServiceDescription described = routes(aliased("/o/{id}", "request: ref_alias2  response: order"));
        Route fetch = described.route(HttpVerb.GET, "/o/{id}").orElseThrow();

        assertEquals(Map.of("id", Location.PATH), fetch.placement().fields());
        assertEquals("ref_alias2", fetch.request().orElseThrow().name());
        assertTrue(described.payloadTypes().contains(new PayloadType(API_ID, "ref_alias2")),
                () -> "" + described.payloadTypes());
    }

    /**
     * A field typed by a plain alias is judged in the path as what the alias reaches: an alias of a record is no
     * scalar, and an alias of an array of records carries more than one value.
     */
    @Test
    void aFieldTypedByAPlainAliasOfAContainerIsRefusedInThePath() {
        assertEquals("'a GET /o/{id}' places 'id' in the PATH, but it is a order_alias, which is not a scalar",
                refusedAliased("/o/{id}", "request: by_order  response: order"));
        assertEquals("'a GET /o/{id}' places 'id' in the PATH, but it is an array, and a URL segment carries exactly "
                + "one scalar", refusedAliased("/o/{id}", "request: by_list  response: order"));
    }

    /**
     * And a field typed by a plain alias of a scalar is admitted there, past every hop: {@code sku_alias} reaches
     * {@code text} through {@code sku}.
     */
    @Test
    void aFieldTypedByAPlainAliasOfAScalarIsAdmittedInThePath() {
        Route route = routes(aliased("/o/{sku}", "request: by_sku  response: order")).routes().getFirst();

        assertEquals(Map.of("sku", Location.PATH), route.placement().fields());
    }

    /**
     * An array's element type is judged past its plain alias: {@code [order_alias]} is an array of records, refused in
     * the query, and {@code [sku_alias]} an array of scalars, admitted there.
     */
    @Test
    void anArrayOfAPlainAliasIsJudgedByWhatTheAliasReaches() {
        assertEquals("'a GET /o' places 'tags' in the QUERY, but it is an array of order_alias, and a repeated query "
                + "value carries a scalar", refusedAliased("/o", "request: by_tags  response: order"));

        Route route = routes(aliased("/o", "request: by_skus  response: order")).routes().getFirst();
        assertEquals(List.of("id", "skus"), route.placement().at(Location.QUERY));
    }

    /**
     * A response type that is a plain alias of a record expands a response header from the record's fields: {@code
     * reply_alias} reaches {@code reply}, whose {@code sku} is a scalar through two hops.
     */
    @Test
    void aResponseHeaderExpandsOverARecordReachedThroughAPlainAlias() {
        Route route = routes(aliased("/o/{id}", "request: order_ref  response: reply_alias  "
                + "response_headers: { ETag => \"{sku}\" }")).routes().getFirst();

        assertEquals(Map.of("etag", "A-1"), route.expandResponseHeaders(field -> field.equals("sku") ? "A-1" : null));
    }

    /**
     * A response header's variable over a field typed by a plain alias is judged as what the alias reaches: an alias
     * of a record is no scalar, and an alias of an array is an array.
     */
    @Test
    void aResponseHeaderOverAFieldTypedByAPlainAliasOfAContainerIsRefused() {
        String declared = "'a GET /o/{id}' declares the response header ";
        assertEquals(declared + "'etag: {order}', but 'order' of 'reply' is a order_alias, which is not a scalar, and "
                + "a header's variable expands from one scalar", refusedAliased("/o/{id}", "request: order_ref  "
                + "response: reply  response_headers: { ETag => \"{order}\" }"));
        assertEquals(declared + "'etag: {orders}', but 'orders' of 'reply' is an array, and a header's variable "
                + "expands from one scalar", refusedAliased("/o/{id}", "request: order_ref  response: reply  "
                + "response_headers: { ETag => \"{orders}\" }"));
    }

    /**
     * A business error that is a plain alias fixes the status the record it reaches fixes: {@code sku_missing} reaches
     * {@code sku_not_found}, which fixes 404, and the route names the error as written.
     */
    @Test
    void anErrorThatIsAPlainAliasFixesTheStatusItsTargetFixes() {
        Route route = routes(aliased("/o/{id}", "request: order_ref  response: order  errors: [sku_missing]"))
                .routes().getFirst();

        assertEquals("sku_missing", route.errorFor(404).orElseThrow().name());
    }


    // ── kept for comparison: an exchange as a TYPE, and the operation IS-A the exchange ──────────

    static final String LIB_ID = "https://tson.io/2026/40/io/ltr8/http/service.tn";

    static final String LIB_B = """
        !!id:"%s"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !schema {
          exchange    => <Req, Resp> { request: Req  response?: Resp  safe?: boolean ~ false  idempotent?: boolean ~ false }
          http_verb   => !enum [GET POST PUT PATCH DELETE HEAD OPTIONS]
          status_code => !integer ^ { min: 100  max: 599 }
          http        => { verb: http_verb  path: text  status?: status_code ~ 200 }
        }""".formatted(LIB_ID);

    /**
     * {@code place_order => exchange<order, order>} alone would be an alias to an instantiation, which has no
     * vocabulary body to compose with (§5.8); the trailing {@code & { … }} is what gives it one.
     */
    static final String PROTOCOL_B = """
        !!id:"%s"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !!import:"%s"
        !schema {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
          place_order  => exchange<order, order> & { errors?: [text] }
          cancel_order => exchange<order_ref, void> & { idempotent?: = true }
        }""".formatted(PROTOCOL_ID, LIB_ID);

    static final String API_B = """
        !!id:"%s"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !!import:"%s"
        !!import:"%s"
        !schema {
          create_order => place_order & http & { verb: = POST  path: = "/orders"  status?: = 201 }
        }""".formatted(API_ID, LIB_ID, PROTOCOL_ID);

    /**
     * Under plain {@code meta.tn}: the operation IS-A its exchange, the binding reads back as fixed fields, and a
     * plan step is a value of the exchange type. The cost shows in every value -- schema facts declared as fields
     * are per-instance. {@code verb: = POST} pins a field whose name is unmarked, which [TSON-SCHEMA] §5.2 makes
     * a <em>marker</em>: never injected, so each step must write its own verb and URL, and omitting one is the
     * missing-field error. Restating the field with its name marked, {@code verb?: = POST}, would inject the pin: a
     * composition may move a required field to fixed, though not to optional.
     */
    @Test
    void anExchangeAsATypeCanBeComposedIntoAnOperation() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(LIB_ID, LIB_B);
        lib.put(PROTOCOL_ID, PROTOCOL_B);
        lib.put(API_ID, API_B);
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib))));
        List<Diagnostic> problems = tson.validateSchema(API_B);
        assertEquals(List.of(), problems, () -> "" + problems);

        var def = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().get("create_order");
        assertTrue(TypeChain.supertypesOf(def).contains("place_order"), () -> "" + TypeChain.supertypesOf(def));
        assertTrue(TypeChain.supertypesOf(def).contains("http"), () -> "" + TypeChain.supertypesOf(def));

        var fields = ((RecordBody) def).fields();
        assertEquals(FieldRole.FIXED, fields.get("verb").role());
        assertEquals("POST", fields.get("verb").value().orElseThrow().text());
        assertEquals("/orders", fields.get("path").value().orElseThrow().text());
        assertEquals("order", ((TypeRef) fields.get("request").type()).name());

        String bare = """
            !!import:"%s"
            !create_order { request: { sku: A-100  quantity: 2 } }""".formatted(API_ID);
        String missing = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bare)).getMessage();
        assertTrue(missing.contains("missing required field 'verb'"), missing);   // the cost: a marker, not injected

        String step = """
            !!import:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: POST  path: "/orders" }""".formatted(API_ID);
        var value = tson.treeReader().read(step);
        assertEquals("POST", value.get("verb").asString().orElseThrow());

        String bad = """
            !!import:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: GET }""".formatted(API_ID);
        String refused = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bad)).getMessage();
        assertTrue(refused.contains("'verb' is fixed on 'create_order'"), refused);
    }
}
