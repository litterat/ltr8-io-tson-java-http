package io.ltr8.tson.http.api;

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
import io.ltr8.tson.http.api.RequestPlacement.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interface declared on its own, and a web service in a second document that maps it, read through {@link
 * TsonApiDescription}.
 *
 * <p>The interface is a map of methods; the api is a map of paths, each a map of verbs. A binding's {@code method}
 * is a reference the resolver judges, so a misspelt method and a reference to the wrong kind of thing are refused
 * at load, pinned here beside the rule that an endpoint is an {@code !operation} or a {@code !binding}. The api's
 * {@code @implements} and {@code @not_bound} are annotations on its entry, whose references the resolver does not
 * judge, so a name there naming no interface or no method is the reader's to refuse. What relates two things is the
 * reader's too: the placement of each method's request record, a method of an interface the api does not
 * implement, a container in a path, a verb or a status against the method, and the {@code @implements} claim held
 * to -- every method bound or exempted with a reason.
 *
 * <p>The last probe is the alternative model kept for comparison: a method as a <em>type</em> under plain
 * {@code meta.tn}, composing into an operation with fixed binding fields, and the cost that shows in its values.
 */
class TsonApiDescriptionTest {

    static final String ERR_ID = "https://schemas.example.com/2026/38/app/orders-errors-1.tn";

    /** Business errors, each fixing the status it inherits from {@code problem}. */
    static final String ERRORS = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"%s"
            {
              sku_not_found   => problem & { status: = 404  sku: text }
              order_not_found => problem & { status: = 404 }
            }""".formatted(ERR_ID, TsonProblemSchema.ID);

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

          orders => !interface {
            @doc:"Accept an order and confirm it with the quantity doubled."
            place_order  => { request: new_order    response: order       errors: [sku_not_found] }
            @safe get_order    => { request: order_ref    response: order       errors: [order_not_found] }
            @safe list_orders  => { request: order_query  response: order_page }
            @idempotent cancel_order => { request: order_ref    errors: [order_not_found] }
          }

          @doc:"Refunds, offered beside orders: an api offering both implements both."
          orders_v2 => !interface { @idempotent refund => { request: order_ref } }

          @doc:"Declares a name orders also declares; a qualified name says which is meant."
          billing => !interface { @safe get_order => { request: order_ref  response: order } }

          bulk => !interface { @safe page_orders => { request: order_page  response: order_page } }

          order_search => { tags: [text]  status?: text }
          @doc:"A repeatable query parameter, and a second declaration of `list_orders`."
          search => !interface { @safe search_orders => { request: order_search  response: order_page }
                                 @safe list_orders   => { request: order_query  response: order_page } }

          @doc:"A request that is not a record at all, which the placement cannot distribute."
          scalars => !interface { by_text => { request: text } }
        }""".formatted(IFACE_ID, TsonApiSchema.ID, ERR_ID);

    /**
     * The web service, in a second document: the same four methods, each looking as different from its method
     * as HTTP wants -- a body plus a header, a path segment, a query string, a bare DELETE.
     */
    static final String API = api("""
          @implements:[orders]
          orders_api => !api {
            "/orders" => {
              @doc:"Place an order."
              POST => !binding { method: orders.place_order  status: 201
                                 body: order  headers: { idempotency_key => "Idempotency-Key" } }
              GET  => !binding { method: orders.list_orders }
            }
            "/orders/{id}" => {
              GET    => !binding { method: orders.get_order }
              DELETE => !binding { method: orders.cancel_order  status: 204 }
            }
          }
        """);

    static String api(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/38/m/core.tn"
        !!import:"%s"
        {
        %s
        }""".formatted(API_ID, TsonApiSchema.ID, IFACE_ID, entries);
    }

    static Tson tson(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.putAll(TsonApiSchema.publishedById());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        lib.put(ERR_ID, ERRORS);
        lib.put(IFACE_ID, IFACE);
        lib.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))
                .withMetaNameBinder(TsonApiSchema.metaNameBinder()));
    }

    /** Resolves the api document and reads its description; the resolver's verdict is asserted clean first. */
    static TsonApiDescription routes(String api) {
        Tson tson = tson(api);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);
        return TsonApiSchema.describedBy(tson, API_ID);
    }

    /**
     * Where the flexibility lives: one request record per method, distributed by the binding. A body that is
     * one field unwrapped beside a header; a path segment; a GET whose remainder is the query string; a DELETE
     * with nothing left over. And the api's {@code implements} claim holds: all four methods are bound.
     */
    @Test
    void anInterfaceAndTheWebServiceThatMapsIt() {
        TsonApiDescription routes = routes(API);
        assertEquals(4, routes.routes().size());

        Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new InterfaceMethod("orders", "place_order")), create.method());
        assertEquals("new_order", create.request().orElseThrow().name());   // borrowed from the method
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

    /** A web service on its own terms: no interface, every endpoint an {@code !operation} with its signature inline. */
    @Test
    void anApiWithNoInterfaceCarriesItsSignaturesInline() {
        TsonApiDescription routes = routes(api("""
              standalone => !api { "/orders/{id}" => {
                @safe GET => !operation { request: order_ref  response: order } } }
            """));

        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.empty(), fetch.method());
        assertEquals(Map.of("id", Location.PATH), fetch.placement().fields());
    }

    /** {@code @implements} is a claim: a method with no operation fails, and a reasoned exemption passes. */
    @Test
    void theImplementsClaimIsHeldTo() {
        String partial = api("""
              @implements:[orders]
              orders_api => !api { "/orders" => { POST => !binding { method: orders.place_order } } }
            """);
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(partial))
                .getMessage();
        assertTrue(refused.contains("[orders.get_order, orders.list_orders, orders.cancel_order]")
                        && refused.contains("@not_bound"), refused);

        routes(api("""
              @implements:[orders]
              @not_bound:[ { method: orders.get_order     reason: "served by the read replica" }
                           { method: orders.list_orders   reason: "not built yet" }
                           { method: orders.cancel_order  reason: "not built yet" } ]
              orders_api => !api { "/orders" => { POST => !binding { method: orders.place_order } } }
            """));
    }

    /**
     * {@code @implements} names interfaces, and the resolver does not judge a reference inside an annotation's
     * value, so the reader does: a name nothing declares, and an entry that is not an interface, are both refused
     * when the description is read.
     */
    @Test
    void anImplementsNamingNoInterfaceIsRefusedByTheReader() {
        String unknown = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[no_such]
              a => !api { "/o" => { @safe GET => !operation { request: order_ref  response: order } } }
            """))).getMessage();
        assertTrue(unknown.contains("'a' implements 'no_such'") && unknown.contains("no entry of the description "
                + "applying interface"), unknown);

        String notAnInterface = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[order]
              a => !api { "/o" => { @safe GET => !operation { request: order_ref  response: order } } }
            """))).getMessage();
        assertTrue(notAnInterface.contains("'a' implements 'order'"), notAnInterface);

        String anApi = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              other => !api { "/p" => { @safe GET => !operation { request: order_ref  response: order } } }
              @implements:[other]
              a => !api { "/o" => { @safe GET => !operation { request: order_ref  response: order } } }
            """))).getMessage();
        assertTrue(anApi.contains("'a' implements 'other'"), anApi);
    }

    /**
     * An endpoint is an {@code !operation}, a {@code !binding} or a {@code !document}, and the tag says which: the
     * base has no data of its own, so an untagged value is refused by the resolver naming the subtypes, and a method on an
     * {@code !operation} is a field it does not have. The one-or-the-other rule needs no reader.
     */
    @Test
    void anEndpointIsTaggedOperationOrBinding() {
        String untagged = only(api("""
              a => !api { "/orders" => { POST => { method: place_order } } }
            """));
        assertTrue(untagged.contains("'endpoint' has no data of its own to bind")
                && untagged.contains("[document, operation, binding]"), untagged);

        String methodOnAnOperation = only(api("""
              a => !api { "/orders" => { POST => !operation { method: place_order  request: order } } }
            """));
        assertTrue(methodOnAnOperation.contains("unknown field 'method' on 'operation'"), methodOnAnOperation);

        String bareBase = only(api("""
              a => !api { "/orders" => { POST => !endpoint { status: 201 } } }
            """));
        assertTrue(bareBase.contains("has no data of its own to bind"), bareBase);
    }

    /**
     * A misspelt method on a binding is the resolver's to refuse, through the two-hop name, naming the keys the
     * interface does hold.
     */
    @Test
    void aMisspeltMethodIsRefusedByTheResolver() {
        String refused = only(api("""
              @implements:[orders]
              orders_api => !api { "/orders" => { POST => !binding { method: orders.plaec_order } } }
            """));
        assertTrue(refused.contains("'orders' has no key 'plaec_order' -- it holds place_order, get_order"), refused);
    }

    /** A binding's reference names the kind of thing its slot wants: an interface where a method is wanted is refused. */
    @Test
    void aReferenceToTheWrongKindOfThingIsRefusedByTheResolver() {
        String notAMethod = only(api("""
              a => !api { "/orders" => { POST => !binding { method: orders } } }
            """));
        assertTrue(notAMethod.contains("wants a 'method'")
                && notAMethod.contains("'orders' is an entry whose body is built by 'interface'"), notAMethod);
    }

    /** A sound reference to a method of an interface the api does not implement is the reader's to refuse. */
    @Test
    void aMethodOfAnInterfaceNotImplementedIsRefusedByTheReader() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[orders]
              a => !api { "/orders/{id}" => { GET => !binding { method: billing.get_order } } }
            """))).getMessage();
        assertTrue(refused.contains("'billing.get_order'") && refused.contains("does not implement")
                && refused.contains("[orders]"), refused);
    }

    /**
     * Two implemented interfaces declaring one name are not ambiguous: the qualified name says which interface
     * declares the method a binding borrows.
     */
    @Test
    void aQualifiedNameSaysWhichInterface() {
        TsonApiDescription routes = routes(api("""
              @implements:[orders billing]
              @not_bound:[ { method: orders.place_order   reason: "elsewhere" }
                           { method: orders.get_order     reason: "elsewhere" }
                           { method: orders.list_orders   reason: "elsewhere" }
                           { method: orders.cancel_order  reason: "elsewhere" } ]
              a => !api { "/orders/{id}" => { GET => !binding { method: billing.get_order } } }
            """));
        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(Optional.of(new InterfaceMethod("billing", "get_order")), fetch.method());
        assertEquals("order_ref", fetch.request().orElseThrow().name());
    }

    /** A URL segment cannot carry a record: the limit a parameter list could only state, checked. */
    @Test
    void aContainerFieldCannotRideInThePath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[bulk]
              a => !api { "/orders/{items}" => { GET => !binding { method: bulk.page_orders } } }
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
              @implements:[scalars]
              a => !api { "/things" => { POST => !binding { method: scalars.by_text } } }
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
        TsonApiDescription routes = routes(api("""
              @implements:[search]
              @not_bound:[ { method: search.list_orders  reason: "elsewhere" } ]
              a => !api { "/search" => { GET => !binding { method: search.search_orders } } }
            """));
        assertEquals(List.of("tags", "status"),
                routes.route(HttpVerb.GET, "/search").orElseThrow().placement().at(Location.QUERY));

        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[bulk]
              a => !api { "/pages" => { GET => !binding { method: bulk.page_orders } } }
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
              @implements:[orders]
              a => !api { "/orders/{id}" => { GET => !binding { method: orders.cancel_order } } }
            """))).getMessage();
        assertTrue(refused.contains("GET is safe") && refused.contains("@safe"), refused);

        // A safe method is idempotent too, so DELETE takes one without saying so twice.
        routes(api("""
              @implements:[orders]
              @not_bound:[ { method: orders.place_order   reason: "elsewhere" }
                           { method: orders.list_orders   reason: "elsewhere" }
                           { method: orders.cancel_order  reason: "elsewhere" } ]
              a => !api { "/orders/{id}" => { DELETE => !binding { method: orders.get_order  status: 204 } } }
            """));
    }

    /** A response-less endpoint answers with no body, and the default 200 says otherwise. */
    @Test
    void aResponselessEndpointCannotAnswer200() {
        String refused = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[orders]
              a => !api { "/orders/{id}" => { DELETE => !binding { method: orders.cancel_order } } }
            """))).getMessage();
        assertTrue(refused.contains("declares no response") && refused.contains("204"), refused);
    }

    /**
     * Coverage is per (interface, method): two implemented interfaces may both declare {@code list_orders}, and
     * binding one says nothing about the other; and an api implementing {@code orders} and {@code orders_v2} claims
     * the methods of each, since an interface lists its own. An exemption is held as hard as a binding: one naming a
     * method of an interface the api does not implement is the reader's to refuse.
     */
    @Test
    void theClaimIsHeldPerInterfaceNotPerName() {
        String bothClaimed = api("""
              @implements:[orders search]
              @not_bound:[ { method: orders.place_order    reason: "elsewhere" }
                           { method: orders.get_order      reason: "elsewhere" }
                           { method: orders.cancel_order   reason: "elsewhere" }
                           { method: search.search_orders  reason: "elsewhere" }
                           { method: search.list_orders    reason: "elsewhere" } ]
              a => !api { "/orders" => { GET => !binding { method: orders.list_orders } } }
            """);
        // orders' list_orders is bound and search's exempted; neither stands in for the other.
        routes(bothClaimed);

        String twoInterfaces = api("""
              @implements:[orders orders_v2]
              @not_bound:[ { method: orders.get_order     reason: "elsewhere" }
                           { method: orders.list_orders   reason: "elsewhere" }
                           { method: orders.cancel_order  reason: "elsewhere" } ]
              a => !api { "/orders" => { POST => !binding { method: orders.place_order  status: 201 } } }
            """);
        String refund = assertThrows(IllegalArgumentException.class, () -> routes(twoInterfaces)).getMessage();
        assertTrue(refund.contains("[orders_v2.refund]"), refund);
        routes(twoInterfaces.replace("a => !api {",
                "a => !api { \"/orders/{id}/refund\" => { PUT => !binding { method: orders_v2.refund  status: 204 } }"));

        String elsewhere = api("""
              @implements:[orders]
              @not_bound:[ { method: billing.get_order  reason: "elsewhere" } ]
              a => !api { "/orders" => { POST => !binding { method: orders.place_order } } }
            """);
        String notClaimed = assertThrows(IllegalArgumentException.class, () -> routes(elsewhere))
                .getMessage();
        assertTrue(notClaimed.contains("exempts 'billing.get_order'")
                && notClaimed.contains("not a method of an interface it implements"), notClaimed);
    }

    /**
     * An exemption's {@code method} sits in an annotation's value, where the resolver does not judge a reference, so
     * one naming a key the interface does not hold loads clean and the reader refuses it -- as it refuses one that is
     * no qualified name at all.
     */
    @Test
    void anExemptionNamingNoMethodIsRefusedByTheReader() {
        String typo = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[orders]
              @not_bound:[ { method: orders.plaec_order  reason: "elsewhere" } ]
              a => !api { "/orders" => { POST => !binding { method: orders.place_order } } }
            """))).getMessage();
        assertTrue(typo.contains("exempts 'orders.plaec_order'")
                && typo.contains("not a method of an interface it implements"), typo);

        String unqualified = assertThrows(IllegalArgumentException.class, () -> routes(api("""
              @implements:[orders]
              @not_bound:[ { method: 42  reason: "elsewhere" } ]
              a => !api { "/orders" => { POST => !binding { method: orders.place_order } } }
            """))).getMessage();
        assertTrue(unqualified.contains("'42' is not a method's qualified name"), unqualified);
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
        Resource orders = (Resource) ((Namespace<?>) entries.get("orders_api")).get("/orders");
        assertEquals(Map.of("idempotency_key", "idempotency-key"), orders.endpoints().get("POST").headers());
    }

    /** The borrowed namespaces keep their grammars at the key: a path, a header name, a method name. */
    @Test
    void theBorrowedNamespacesKeepTheirGrammarsAtTheKey() {
        String notAPath = only(api("""
              a => !api { "orders" => { GET => !operation { request: order_ref } } }
            """));
        assertTrue(notAPath.contains("'path_template'") && notAPath.contains("'orders'"), notAPath);

        String notAToken = only(api("""
              a => !api { "/orders" => {
                POST => !operation { request: new_order  headers: { idempotency_key => "Idempotency Key" } } } }
            """));
        assertTrue(notAToken.contains("'header_name'"), notAToken);

        String notAMethodName = only(api("""
              x => !interface { "place order" => { request: new_order } }
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
