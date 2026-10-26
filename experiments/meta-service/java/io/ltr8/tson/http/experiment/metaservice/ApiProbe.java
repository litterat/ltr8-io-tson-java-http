package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interface declared on its own, and a web service in a second document that maps it -- the case the whole
 * experiment exists for -- read through {@link Routes}.
 *
 * <p>The interface is a map of methods; the api is a map of paths, each a map of verbs. What names something is a
 * reference the resolver judges -- {@code implements}, a binding's and an exemption's {@code method} -- so a
 * misspelt method, a reference to the wrong kind of thing, and an ambiguous name are refused at load, pinned here
 * beside the rule that an endpoint is an {@code !operation} or a {@code !binding}. What relates two things is the
 * reader's: the placement of each method's request record, a method of an interface the api does not implement,
 * a container in a path, a verb or a status against the method, and the {@code implements} claim held to -- every
 * method bound or exempted with a reason.
 *
 * <p>The last probe is the alternative model kept for comparison: a method as a <em>type</em> under plain
 * {@code meta.tn}, composing into an operation with fixed binding fields, and the cost that shows in its values.
 */
class ApiProbe {

    static final String IFACE_ID = "https://schemas.example.com/2026/38/app/orders-1.tn";
    static final String API_ID = "https://schemas.example.com/2026/38/app/orders-api-1.tn";

    /** The interface: four methods over four request records, nothing HTTP in sight. */
    static final String IFACE = """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/38/m/core.tn"
        !!import:"%s"
        {
          order       => { sku: text  quantity: int32 }
          order_ref   => { id: text }
          order_query => { status?: text  page?: int32 ~ 1  page_size?: int32 ~ 20 }
          order_page  => { items: [order]  next_page?: int32 }
          new_order   => { order: order  idempotency_key?: text }

          orders => !interfaces { orders => {
            @doc:"Accept an order and confirm it with the quantity doubled."
            place_order  => { request: new_order    response: order       errors: [sku_not_found] }
            @safe get_order    => { request: order_ref    response: order       errors: [order_not_found] }
            @safe list_orders  => { request: order_query  response: order_page }
            @idempotent cancel_order => { request: order_ref    errors: [order_not_found] }
          } }

          @doc:"Everything orders has, and a refund."
          orders_v2 => !interfaces { orders_v2 => { extends: [orders.orders]
                                                    methods: { @idempotent refund => { request: order_ref } } } }

          @doc:"Declares a name orders also declares; a qualified name says which is meant."
          billing => !interfaces { billing => { @safe get_order => { request: order_ref  response: order } } }

          bulk => !interfaces { bulk => { @safe page_orders => { request: order_page  response: order_page } } }

          order_search => { tags: [text]  status?: text }
          @doc:"A repeatable query parameter, and a second declaration of `list_orders`."
          search => !interfaces { search => { @safe search_orders => { request: order_search  response: order_page }
                                 @safe list_orders   => { request: order_query  response: order_page } } }

          @doc:"A request that is not a record at all, which the placement cannot distribute."
          scalars => !interfaces { scalars => { by_text => { request: text } } }
        }""".formatted(IFACE_ID, Experiment.META_ID, MetaServiceSketchProbe.ERR_ID);

    /**
     * The web service, in a second document: the same four methods, each looking as different from its method
     * as HTTP wants -- a body plus a header, a path segment, a query string, a bare DELETE.
     */
    static final String API = api("""
          orders_api => !apis { orders_api => {
            implements: [orders.orders]
            resources: {
              "/orders" => !resource {
                @doc:"Place an order."
                POST => !binding { method: orders.orders.methods.place_order  status: 201
                                     body: order  headers: { idempotency_key => "Idempotency-Key" } }
                GET  => !binding { method: orders.orders.methods.list_orders }
              }
              "/orders/{id}" => !resource {
                GET    => !binding { method: orders.orders.methods.get_order }
                DELETE => !binding { method: orders.orders.methods.cancel_order  status: 204 }
              }
            }
          } }
        """);

    static String api(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/38/m/core.tn"
        !!import:"%s"
        {
        %s
        }""".formatted(API_ID, Experiment.META_ID, IFACE_ID, entries);
    }

    static Tson tson(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        lib.put(MetaServiceSketchProbe.ERR_ID, MetaServiceSketchProbe.ERRORS);
        lib.put(IFACE_ID, IFACE);
        lib.put(API_ID, api);
        return Tson.of(Experiment.bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))));
    }

    /** Resolves the api document and reads it into routes; the resolver's verdict is asserted clean first. */
    static Routes routes(String api) {
        Tson tson = tson(api);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        var apis = Experiment.held(entries, "apis", Api.class);
        String name = apis.keySet().iterator().next();
        return Routes.of(name, apis.get(name), interfaces(tson, entries), entries::get);
    }

    /**
     * The interfaces an api may implement, by qualified name: the interface document's, and any the api document
     * declares itself.
     */
    static Map<String, Interface> interfaces(Tson tson, Namespace<Top> entries) {
        Map<String, Interface> all = new LinkedHashMap<>(Experiment.heldByName(
                tson.schemaRegistry().get(IFACE_ID).orElseThrow().schema().entries(), "interfaces", Interface.class));
        all.putAll(Experiment.heldByName(entries, "interfaces", Interface.class));
        return all;
    }

    /**
     * Where the flexibility lives: one request record per method, distributed by the binding. A body that is
     * one field unwrapped beside a header; a path segment; a GET whose remainder is the query string; a DELETE
     * with nothing left over. And the api's {@code implements} claim holds: all four methods are bound.
     */
    @Test
    void anInterfaceAndTheWebServiceThatMapsIt() {
        Routes routes = routes(API).requireComplete();
        assertEquals(4, routes.routes().size());

        Routes.Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new Routes.Bound("orders.orders", "place_order")), create.method());
        assertEquals("new_order", create.request().orElseThrow().name());   // borrowed from the method
        assertEquals(201, create.status());
        assertEquals(List.of("order"), create.placement().at(Placement.Location.BODY));
        assertEquals(List.of("idempotency_key"), create.placement().at(Placement.Location.HEADER));

        Routes.Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Map.of("id", Placement.Location.PATH), fetch.placement().fields());

        Routes.Route search = routes.route(HttpVerb.GET, "/orders").orElseThrow();
        assertEquals(List.of("status", "page", "page_size"), search.placement().at(Placement.Location.QUERY));

        Routes.Route cancel = routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow();
        assertEquals(Map.of("id", Placement.Location.PATH), cancel.placement().fields());
        assertEquals("order_not_found", cancel.errors().getFirst().name());
    }

    /** A web service on its own terms: no interface, every endpoint an {@code !operation} with its signature inline. */
    @Test
    void anApiWithNoInterfaceCarriesItsSignaturesInline() {
        Routes routes = routes(api("""
              standalone => !apis { standalone => { "/orders/{id}" => !resource {
                @safe GET => !operation { request: order_ref  response: order } } } }
            """)).requireComplete();

        Routes.Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.empty(), fetch.method());
        assertEquals(Map.of("id", Placement.Location.PATH), fetch.placement().fields());
    }

    /** {@code implements} is a claim: a method with no operation fails, and a reasoned exemption passes. */
    @Test
    void theImplementsClaimIsHeldTo() {
        String partial = api("""
              orders_api => !apis { orders_api => { implements: [orders.orders]
                resources: { "/orders" => !resource { POST => !binding { method: orders.orders.methods.place_order } } }
              } }
            """);
        String refused = assertThrows(IllegalStateException.class, () -> routes(partial).requireComplete())
                .getMessage();
        assertTrue(refused.contains("[orders.orders.methods.get_order, orders.orders.methods.list_orders, "
                        + "orders.orders.methods.cancel_order]") && refused.contains("not_bound"),
                refused);

        routes(api("""
              orders_api => !apis { orders_api => { implements: [orders.orders]
                not_bound: [ { method: orders.orders.methods.get_order     reason: "served by the read replica" }
                             { method: orders.orders.methods.list_orders   reason: "not built yet" }
                             { method: orders.orders.methods.cancel_order  reason: "not built yet" } ]
                resources: { "/orders" => !resource { POST => !binding { method: orders.orders.methods.place_order } } }
              } }
            """)).requireComplete();
    }

    /**
     * {@code extends} is walked: implementing {@code orders_v2} claims {@code orders}' methods too, each still the
     * declaring interface's, so the claim names them there.
     */
    @Test
    void extendsIsWalkedForTheClaim() {
        String refused = assertThrows(IllegalStateException.class, () -> routes(api("""
              v2 => !apis { v2 => { implements: [orders_v2.orders_v2]
                           resources: { "/refunds/{id}" => !resource {
                             PUT => !binding { method: orders_v2.orders_v2.methods.refund  status: 204 } } } } }
            """)).requireComplete()).getMessage();

        assertTrue(refused.contains("orders.orders.methods.place_order") && !refused.contains("refund"), refused);
    }

    /**
     * An inherited method is named at the interface that declares it: {@code extends} is a reference, not a
     * composition, so {@code orders_v2}'s own methods hold {@code refund} alone, and the resolver says so.
     */
    @Test
    void anInheritedMethodIsNamedWhereItIsDeclared() {
        String refused = only(api("""
              v2 => !apis { v2 => { implements: [orders_v2.orders_v2]
                           resources: { "/orders" => !resource {
                             POST => !binding { method: orders_v2.orders_v2.methods.place_order } } } } }
            """));
        assertTrue(refused.contains("has no key 'place_order' -- it holds refund"), refused);
    }

    /**
     * An endpoint is an {@code !operation} or a {@code !binding}, and the tag says which: the base has no data of
     * its own, so an untagged value is refused by the resolver naming both subtypes, and a method on an
     * {@code !operation} is a field it does not have. The one-or-the-other rule needs no reader.
     */
    @Test
    void anEndpointIsTaggedOperationOrBinding() {
        String untagged = only(api("""
              a => !apis { a => { "/orders" => !resource { POST => { method: place_order } } } }
            """));
        assertTrue(untagged.contains("'endpoint' has no data of its own to bind")
                && untagged.contains("[operation, binding]"), untagged);

        String methodOnAnOperation = only(api("""
              a => !apis { a => { "/orders" => !resource { POST => !operation { method: place_order  request: order } } } }
            """));
        assertTrue(methodOnAnOperation.contains("unknown field 'method' on 'operation'"), methodOnAnOperation);

        String bareBase = only(api("""
              a => !apis { a => { "/orders" => !resource { POST => !endpoint { status: 201 } } } }
            """));
        assertTrue(bareBase.contains("has no data of its own to bind"), bareBase);
    }

    /**
     * An {@code extends} cycle is the one thing a reference cannot rule out: each names an interface that exists,
     * so the reader walks them and refuses the loop.
     */
    @Test
    void anExtendsCycleIsRefusedByTheReader() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              p => !interfaces { p => { extends: [q.q]  methods: { @safe m => { request: order_ref } } } }
              q => !interfaces { q => { extends: [p.p]  methods: { @safe n => { request: order_ref } } } }
              a => !apis { a => { implements: [p.p]
                          resources: { "/o" => !resource { @safe GET => !operation { request: order_ref } } } } }
            """))).getMessage();
        assertTrue(refused.contains("extends itself") && refused.contains("p.p"), refused);
    }

    /**
     * A misspelt method is the resolver's to refuse, naming the keys the interface does hold -- where, named by
     * identifier, it resolved clean and only the reader caught it.
     */
    @Test
    void aMisspeltMethodIsRefusedByTheResolver() {
        String refused = only(api("""
              orders_api => !apis { orders_api => { implements: [orders.orders]
                resources: { "/orders" => !resource { POST => !binding { method: orders.orders.methods.plaec_order } } }
              } }
            """));
        assertTrue(refused.contains("has no key 'plaec_order' -- it holds place_order, get_order"), refused);
    }

    /**
     * A reference names the kind of thing its slot wants: an interface where a method is wanted, or a method where
     * an interface is, is refused at load.
     */
    @Test
    void aReferenceToTheWrongKindOfThingIsRefusedByTheResolver() {
        String notAMethod = only(api("""
              a => !apis { a => { "/orders" => !resource { POST => !binding { method: orders.orders } } } }
            """));
        assertTrue(notAMethod.contains("wants a 'method'") && notAMethod.contains("is an 'interface'"), notAMethod);

        String notAnInterface = only(api("""
              a => !apis { a => { implements: [orders.orders.methods.get_order]
                          resources: { "/o" => !resource { @safe GET => !operation { request: order_ref } } } } }
            """));
        assertTrue(notAnInterface.contains("wants an 'interface'") && notAnInterface.contains("is a 'method'"),
                notAnInterface);
    }

    /** A sound reference to a method of an interface the api does not implement is the reader's to refuse. */
    @Test
    void aMethodOfAnInterfaceNotImplementedIsRefusedByTheReader() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [orders.orders]
                          resources: { "/orders/{id}" => !resource {
                            GET => !binding { method: billing.billing.methods.get_order } } } } }
            """))).getMessage();
        assertTrue(refused.contains("'billing.billing.methods.get_order'") && refused.contains("does not implement")
                && refused.contains("[orders.orders]"), refused);
    }

    /**
     * Two implemented interfaces declaring one name are not ambiguous: the qualified name says which interface
     * declares the method a binding borrows.
     */
    @Test
    void aQualifiedNameSaysWhichInterface() {
        Routes routes = routes(api("""
              a => !apis { a => { implements: [orders.orders billing.billing]
                          resources: { "/orders/{id}" => !resource {
                            GET => !binding { method: billing.billing.methods.get_order } } } } }
            """));
        Routes.Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.of(new Routes.Bound("billing.billing", "get_order")), fetch.method());
        assertEquals("order_ref", fetch.request().orElseThrow().name());
    }

    /** A URL segment cannot carry a record: the limit `meta-http.tn` could only state, checked. */
    @Test
    void aContainerFieldCannotRideInThePath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [bulk.bulk]
                          resources: { "/orders/{items}" => !resource {
                            GET => !binding { method: bulk.bulk.methods.page_orders } } } } }
            """))).getMessage();

        assertTrue(refused.contains("'items'") && refused.contains("carries exactly one scalar"), refused);
    }

    /**
     * A request is the record whose fields an endpoint distributes, so one that is not a record is refused where
     * it is read. Left to fall through it places nothing and reads as a working endpoint carrying no data.
     */
    @Test
    void aRequestThatIsNotARecordIsRefused() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [scalars.scalars]
                          resources: { "/things" => !resource {
                            POST => !binding { method: scalars.scalars.methods.by_text } } } } }
            """))).getMessage();

        assertTrue(refused.contains("'text'") && refused.contains("not a record"), refused);
    }

    /**
     * A query parameter and a header may repeat, so either admits an array of scalars -- {@code ?tag=a&tag=b} is
     * ordinary HTTP, and refusing it would push a multi-valued parameter into a body no GET can carry. An array
     * of records still has no spelling, and neither has an array in a path segment.
     */
    @Test
    void aRepeatedQueryValueIsAnArrayOfScalars() {
        Routes routes = routes(api("""
              a => !apis { a => { implements: [search.search]
                          not_bound: [ { method: search.search.methods.list_orders  reason: "elsewhere" } ]
                          resources: { "/search" => !resource {
                            GET => !binding { method: search.search.methods.search_orders } } } } }
            """)).requireComplete();
        assertEquals(List.of("tags", "status"),
                routes.route(HttpVerb.GET, "/search").orElseThrow().placement().at(Placement.Location.QUERY));

        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [bulk.bulk]
                          resources: { "/pages" => !resource {
                            GET => !binding { method: bulk.bulk.methods.page_orders } } } } }
            """))).getMessage();
        assertTrue(refused.contains("'items'") && refused.contains("array of order"), refused);
    }

    /**
     * The projection checks its verb against what the method claims, and never derives one from the other: a GET
     * must be safe, a PUT or DELETE idempotent, and {@code @safe} implies {@code @idempotent}. The mismatch is
     * the one a client cannot see and a cache will act on.
     */
    @Test
    void aVerbMustAgreeWithWhatTheMethodClaims() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [orders.orders]
                          resources: { "/orders/{id}" => !resource {
                            GET => !binding { method: orders.orders.methods.cancel_order } } } } }
            """))).getMessage();
        assertTrue(refused.contains("GET is safe") && refused.contains("@safe"), refused);

        // A safe method is idempotent too, so DELETE takes one without saying so twice.
        routes(api("""
              a => !apis { a => { implements: [orders.orders]
                          resources: { "/orders/{id}" => !resource {
                            DELETE => !binding { method: orders.orders.methods.get_order  status: 204 } } } } }
            """));
    }

    /** A response-less endpoint answers with no body, and the default 200 says otherwise. */
    @Test
    void aResponselessEndpointCannotAnswer200() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !apis { a => { implements: [orders.orders]
                          resources: { "/orders/{id}" => !resource {
                            DELETE => !binding { method: orders.orders.methods.cancel_order } } } } }
            """))).getMessage();
        assertTrue(refused.contains("declares no response") && refused.contains("204"), refused);
    }

    /**
     * Coverage is per (interface, method): two implemented interfaces may both declare {@code list_orders}, and
     * binding one says nothing about the other. An exemption is held as hard as a binding -- a misspelt one is the
     * resolver's to refuse, and one naming a method of an interface the api does not implement is the reader's.
     */
    @Test
    void theClaimIsHeldPerInterfaceNotPerName() {
        String bothClaimed = api("""
              a => !apis { a => { implements: [orders.orders search.search]
                not_bound: [ { method: orders.orders.methods.place_order    reason: "elsewhere" }
                             { method: orders.orders.methods.get_order      reason: "elsewhere" }
                             { method: orders.orders.methods.cancel_order   reason: "elsewhere" }
                             { method: search.search.methods.search_orders  reason: "elsewhere" }
                             { method: search.search.methods.list_orders    reason: "elsewhere" } ]
                resources: { "/orders" => !resource { GET => !binding { method: orders.orders.methods.list_orders } } }
              } }
            """);
        // orders' list_orders is bound and search's exempted; neither stands in for the other.
        routes(bothClaimed).requireComplete();

        String elsewhere = api("""
              a => !apis { a => { implements: [orders.orders]
                not_bound: [ { method: billing.billing.methods.get_order  reason: "elsewhere" } ]
                resources: { "/orders" => !resource { POST => !binding { method: orders.orders.methods.place_order } } }
              } }
            """);
        String notClaimed = assertThrows(IllegalStateException.class, () -> routes(elsewhere).requireComplete())
                .getMessage();
        assertTrue(notClaimed.contains("exempts 'billing.billing.methods.get_order'")
                && notClaimed.contains("not a method of an interface it implements"), notClaimed);

        String typo = only(api("""
              a => !apis { a => { implements: [orders.orders]
                not_bound: [ { method: orders.orders.methods.plaec_order  reason: "elsewhere" } ]
                resources: { "/orders" => !resource { POST => !binding { method: orders.orders.methods.place_order } } }
              } }
            """));
        assertTrue(typo.contains("has no key 'plaec_order'"), typo);
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
        Api api = Experiment.held(entries, "apis", Api.class).get("orders_api");
        assertEquals(Map.of("idempotency_key", "idempotency-key"),
                api.resources().get("/orders").endpoints().get("POST").headers());
    }

    /** The borrowed namespaces keep their grammars at the key: a path, a header name, a method name. */
    @Test
    void theBorrowedNamespacesKeepTheirGrammarsAtTheKey() {
        String notAPath = only(api("""
              a => !apis { a => { "orders" => !resource { GET => !operation { request: order_ref } } } }
            """));
        assertTrue(notAPath.contains("'path_template'") && notAPath.contains("'orders'"), notAPath);

        String notAToken = only(api("""
              a => !apis { a => { "/orders" => !resource {
                POST => !operation { request: new_order  headers: { idempotency_key => "Idempotency Key" } } } } }
            """));
        assertTrue(notAToken.contains("'header_name'"), notAToken);

        String notAMethodName = only(api("""
              x => !interfaces { x => { "place order" => { request: new_order } } }
            """));
        assertTrue(notAMethodName.contains("'method_name': 'place order'"), notAMethodName);
    }

    /** The one diagnostic the resolver gives for {@code api}, or a failure naming them all. */
    static String only(String api) {
        List<Diagnostic> problems = tson(api).validateSchema(api);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    // ── kept for comparison: a method as a TYPE, and the operation IS-A the method ──────────────

    static final String LIB_ID = "https://tson.io/2026/38/io/ltr8/http/service.tn";

    static final String LIB_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/38/m/meta.tn"
        !!import:"https://tson.io/2026/38/m/core.tn"
        {
          method      => <Req, Resp> { request: Req  response?: Resp  safe?: boolean ~ false  idempotent?: boolean ~ false }
          http_verb   => !enum [GET POST PUT PATCH DELETE HEAD OPTIONS]
          status_code => !integer ^ { min: 100  max: 599 }
          http        => { verb: http_verb  path: text  status?: status_code ~ 200 }
        }""".formatted(LIB_ID);

    /**
     * {@code place_order => method<order, order>} alone would be an alias to an instantiation, which has no
     * vocabulary body to compose with (§5.8); the trailing {@code & { … }} is what gives it one.
     */
    static final String IFACE_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/38/m/meta.tn"
        !!import:"https://tson.io/2026/38/m/core.tn"
        !!import:"%s"
        {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
          place_order  => method<order, order> & { errors?: [text] }
          cancel_order => method<order_ref, void> & { idempotent?: = true }
        }""".formatted(IFACE_ID, LIB_ID);

    static final String API_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/38/m/meta.tn"
        !!import:"https://tson.io/2026/38/m/core.tn"
        !!import:"%s"
        !!import:"%s"
        {
          create_order => place_order & http & { verb: = POST  path: = "/orders"  status?: = 201 }
        }""".formatted(API_ID, LIB_ID, IFACE_ID);

    /**
     * Under plain {@code meta.tn}: the operation IS-A its method, the binding reads back as fixed fields, and a
     * plan step is a value of the method type. The cost shows in every value -- schema facts declared as fields
     * are per-instance. {@code verb: = POST} pins a field whose name is unmarked, which [TSON-SCHEMA] §5.2 makes
     * a <em>marker</em>: never injected, so each step must write its own verb and URL, and omitting one is the
     * missing-field error. Pinning with the name marked instead would inject, but a refinement may not relax a
     * required field to an omissible one, so the base would have to declare {@code verb?:} to begin with.
     */
    @Test
    void aMethodAsATypeCanBeComposedIntoAnOperation() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(LIB_ID, LIB_B);
        lib.put(IFACE_ID, IFACE_B);
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
        assertEquals("order", fields.get("request").type().name());

        String bare = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 } }""".formatted(API_ID);
        String missing = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bare)).getMessage();
        assertTrue(missing.contains("missing required field 'verb'"), missing);   // the cost: a marker, not injected

        String step = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: POST  path: "/orders" }""".formatted(API_ID);
        var value = tson.treeReader().read(step);
        assertEquals("POST", value.get("verb").asString().orElseThrow());

        String bad = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: GET }""".formatted(API_ID);
        String refused = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bad)).getMessage();
        assertTrue(refused.contains("'verb' is fixed on 'create_order'"), refused);
    }
}
