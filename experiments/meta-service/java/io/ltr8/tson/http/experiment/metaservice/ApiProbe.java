package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
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
 * <p>The interface is a map of methods; the api is a map of paths, each a map of verbs. Every relation between
 * them is an identifier the resolver does not check (a method name, an {@code implements}, a {@code {segment}}),
 * so each probe here is one of the reader's checks: the placement of each method's request record, a method no
 * implemented interface declares, an ambiguous name across two interfaces, a container in a path, and the
 * {@code implements} claim held to -- every method bound or exempted with a reason. The one rule the reader no
 * longer needs -- an endpoint is an {@code !operation} or a {@code !binding} -- is the resolver's, and pinned here.
 *
 * <p>The last probe is the alternative model kept for comparison: a method as a <em>type</em> under plain
 * {@code meta.tn}, composing into an operation with fixed binding fields, and the cost that shows in its values.
 */
class ApiProbe {

    static final String IFACE_ID = "https://schemas.example.com/2026/34/app/orders-1.tn";
    static final String API_ID = "https://schemas.example.com/2026/34/app/orders-api-1.tn";

    /** The interface: four methods over four request records, nothing HTTP in sight. */
    static final String IFACE = """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        {
          order       => { sku: text  quantity: int32 }
          order_ref   => { id: text }
          order_query => { status: text?  page: int32 ~ 1  page_size: int32 ~ 20 }
          order_page  => { items: [order]  next_page: int32? }
          new_order   => { order: order  idempotency_key: text? }

          orders => !interface {
            @doc:"Accept an order and confirm it with the quantity doubled."
            place_order  => { request: new_order    response: order       errors: [sku_not_found] }
            get_order    => { request: order_ref    response: order       errors: [order_not_found]  safe: true }
            list_orders  => { request: order_query  response: order_page  safe: true }
            cancel_order => { request: order_ref    errors: [order_not_found]  idempotent: true }
          }

          @doc:"Everything orders has, and a refund."
          orders_v2 => !interface { extends: [orders]  methods: { refund => { request: order_ref  idempotent: true } } }

          @doc:"Declares a name orders also declares, to force `interface:` on a binding of it."
          billing => !interface { get_order => { request: order_ref  response: order } }

          bulk => !interface { page_orders => { request: order_page } }
        }""".formatted(IFACE_ID, Experiment.META_ID, MetaServiceSketchProbe.ERR_ID);

    /**
     * The web service, in a second document: the same four methods, each looking as different from its method
     * as HTTP wants -- a body plus a header, a path segment, a query string, a bare DELETE.
     */
    static final String API = api("""
          orders_api => !api {
            implements: [orders]
            resources: {
              "/orders" => !resource {
                @doc:"Place an order."
                POST => !binding { method: place_order  status: 201
                                     body: order  headers: { idempotency_key => "Idempotency-Key" } }
                GET  => !binding { method: list_orders }
              }
              "/orders/{id}" => !resource {
                GET    => !binding { method: get_order }
                DELETE => !binding { method: cancel_order  status: 204 }
              }
            }
          }
        """);

    static String api(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/34/m/core.tn"
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
        return Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
    }

    /** Resolves the api document and reads it into routes; the resolver's verdict is asserted clean first. */
    static Routes routes(String api) {
        Tson tson = tson(api);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        String name = entries.keySet().stream().filter(n -> entries.get(n).body() instanceof Api).findFirst()
                .orElseThrow();
        return Routes.of(name, (Api) entries.get(name).body(), entries::get);
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
        assertEquals(Optional.of("place_order"), create.method());
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
              standalone => !api { "/orders/{id}" => !resource {
                GET => !operation { request: order_ref  response: order } } }
            """)).requireComplete();

        Routes.Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.empty(), fetch.method());
        assertEquals(Map.of("id", Placement.Location.PATH), fetch.placement().fields());
    }

    /** {@code implements} is a claim: a method with no operation fails, and a reasoned exemption passes. */
    @Test
    void theImplementsClaimIsHeldTo() {
        String partial = api("""
              orders_api => !api { implements: [orders]
                                   resources: { "/orders" => !resource { POST => !binding { method: place_order } } } }
            """);
        String refused = assertThrows(IllegalStateException.class, () -> routes(partial).requireComplete())
                .getMessage();
        assertTrue(refused.contains("[get_order, list_orders, cancel_order]") && refused.contains("not_bound"),
                refused);

        routes(api("""
              orders_api => !api { implements: [orders]
                                   not_bound: { get_order => "served by the read replica"  list_orders => "not built yet"
                                                cancel_order => "not built yet" }
                                   resources: { "/orders" => !resource { POST => !binding { method: place_order } } } }
            """)).requireComplete();
    }

    /** {@code extends} is walked: implementing {@code orders_v2} claims {@code orders}' methods too. */
    @Test
    void extendsIsWalkedForTheClaim() {
        String refused = assertThrows(IllegalStateException.class, () -> routes(api("""
              v2 => !api { implements: [orders_v2]
                           resources: { "/refunds/{id}" => !resource { POST => !binding { method: refund } } } }
            """)).requireComplete()).getMessage();

        assertTrue(refused.contains("place_order") && refused.contains("refund") == false, refused);
    }

    /**
     * An endpoint is an {@code !operation} or a {@code !binding}, and the tag says which: the base has no data of
     * its own, so an untagged value is refused by the resolver naming both subtypes, and a method on an
     * {@code !operation} is a field it does not have. The one-or-the-other rule needs no reader.
     */
    @Test
    void anEndpointIsTaggedOperationOrBinding() {
        String untagged = only(api("""
              a => !api { "/orders" => !resource { POST => { method: place_order } } }
            """));
        assertTrue(untagged.contains("'endpoint' has no data of its own to bind")
                && untagged.contains("[operation, binding]"), untagged);

        String methodOnAnOperation = only(api("""
              a => !api { "/orders" => !resource { POST => !operation { method: place_order  request: order } } }
            """));
        assertTrue(methodOnAnOperation.contains("unknown field 'method' on 'operation'"), methodOnAnOperation);

        String bareBase = only(api("""
              a => !api { "/orders" => !resource { POST => !endpoint { status: 201 } } }
            """));
        assertTrue(bareBase.contains("has no data of its own to bind"), bareBase);
    }

    /** The cost of relating by identifier, measured: a typo resolves clean and only the reader catches it. */
    @Test
    void aMethodNoImplementedInterfaceDeclaresIsRefusedByTheReader() {
        String typo = api("""
              orders_api => !api { implements: [orders]
                                   resources: { "/orders" => !resource { POST => !binding { method: plaec_order } } } }
            """);
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(typo)).getMessage();
        assertTrue(refused.contains("'plaec_order'") && refused.contains("[orders]"), refused);
    }

    /** Two implemented interfaces declaring one name: ambiguous until {@code interface:} says which. */
    @Test
    void anAmbiguousMethodNameNeedsItsInterface() {
        String ambiguous = api("""
              a => !api { implements: [orders billing]
                          resources: { "/orders/{id}" => !resource { GET => !binding { method: get_order } } } }
            """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> routes(ambiguous)).getMessage()
                .contains("say which with `interface:`"));

        Routes routes = routes(api("""
              a => !api { implements: [orders billing]
                          resources: { "/orders/{id}" => !resource {
                            GET => !binding { method: get_order  interface: billing } } } }
            """));
        assertEquals("order_ref", routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow().request().orElseThrow().name());
    }

    /** A URL segment cannot carry a record: the limit `meta-http-1.tn` could only state, checked. */
    @Test
    void aContainerFieldCannotRideInThePath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              a => !api { implements: [bulk]
                          resources: { "/orders/{items}" => !resource { GET => !binding { method: page_orders } } } }
            """))).getMessage();

        assertTrue(refused.contains("'items'") && refused.contains("not a scalar"), refused);
    }

    /** The borrowed namespaces keep their grammars at the key: a path, a header name, a method name. */
    @Test
    void theBorrowedNamespacesKeepTheirGrammarsAtTheKey() {
        String notAPath = only(api("""
              a => !api { "orders" => !resource { GET => !operation { request: order_ref } } }
            """));
        assertTrue(notAPath.contains("'path_template'") && notAPath.contains("'orders'"), notAPath);

        String notAToken = only(api("""
              a => !api { "/orders" => !resource {
                POST => !operation { request: new_order  headers: { idempotency_key => "Idempotency Key" } } } }
            """));
        assertTrue(notAToken.contains("'header_name'"), notAToken);

        String notAMethodName = only(api("""
              a => !api { implements: [orders]
                          resources: { "/orders" => !resource { POST => !binding { method: "place order" } } } }
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

    static final String LIB_ID = "https://tson.io/2026/34/ltr8/http/service-1.tn";

    static final String LIB_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        {
          method      => <Req, Resp> { request: Req  response: Resp?  safe: boolean ~ false  idempotent: boolean ~ false }
          http_verb   => !enum [GET POST PUT PATCH DELETE HEAD OPTIONS]
          status_code => !integer ^ { min: 100  max: 599 }
          http        => { verb: http_verb  path: text  status: status_code ~ 200 }
        }""".formatted(LIB_ID);

    /**
     * {@code place_order => method<order, order>} alone would be an alias to an instantiation, which has no
     * vocabulary body to compose with (§5.8); the trailing {@code & { … }} is what gives it one.
     */
    static final String IFACE_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
          place_order  => method<order, order> & { errors: [text]? }
          cancel_order => method<order_ref, void> & { idempotent: = true }
        }""".formatted(IFACE_ID, LIB_ID);

    static final String API_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        !!import:"%s"
        {
          create_order => place_order & http & { verb: = POST  path: = "/orders"  status: = 201 }
        }""".formatted(API_ID, LIB_ID, IFACE_ID);

    /**
     * Under plain {@code meta.tn}: the operation IS-A its method, the binding reads back as fixed fields, and a
     * plan step is a value of the method type. The cost shows in the read-back value -- schema facts declared as
     * fields are injected into every instance, so each step carries its own URL.
     */
    @Test
    void aMethodAsATypeCanBeComposedIntoAnOperation() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(LIB_ID, LIB_B);
        lib.put(IFACE_ID, IFACE_B);
        lib.put(API_ID, API_B);
        Tson tson = Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib)).build();
        List<Diagnostic> problems = tson.validateSchema(API_B);
        assertEquals(List.of(), problems, () -> "" + problems);

        var def = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().get("create_order");
        assertTrue(def.supertypes().contains("place_order"), () -> "" + def.supertypes());
        assertTrue(def.supertypes().contains("http"), () -> "" + def.supertypes());

        Map<String, RecordField> fields = new LinkedHashMap<>();
        ((RecordBody) def.body()).fields().forEach(f -> fields.put(f.name(), f));
        assertEquals(FieldState.REQUIRED_FIXED, fields.get("verb").state());
        assertEquals("POST", fields.get("verb").value().orElseThrow().text());
        assertEquals("/orders", fields.get("path").value().orElseThrow().text());
        assertEquals("order", fields.get("request").type().name());

        String step = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 } }""".formatted(API_ID);
        var value = tson.treeReader().read(step);
        assertEquals("POST", value.get("verb").asString().orElseThrow());   // the cost: injected into every value

        String bad = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: GET }""".formatted(API_ID);
        String refused = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bad)).getMessage();
        assertTrue(refused.contains("'verb' is fixed on 'create_order'"), refused);
    }
}
