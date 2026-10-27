package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link OrdersExamples} resolve against the shipped layer, and the two apis read into route tables -- so an
 * example that stops being true fails the build rather than the reader.
 *
 * <p>The placement {@code orders-api-1.tn} declares, as the reader computes it:
 * <pre>
 * binding              method's request                      path  query      header           body
 * POST   /orders       new_order { order  idempotency_key }   --    --         idempotency_key  order, unwrapped
 * GET    /orders/{id}  order_ref { id }                      id    --         --               --
 * GET    /orders       order_query { status page page_size }  --    all three  --               --
 * DELETE /orders/{id}  order_ref { id }                      id    --         --               --
 * </pre>
 * The header is {@code Idempotency-Key}; a GET's remainder is the query; a DELETE's is nothing, since {@code id}
 * was the whole record.
 */
class OrdersExamplesTest {

    static Tson tson() {
        return OrdersExamples.tson(OrdersExamples.library());
    }

    /** Resolves one example by identity and reads its description; the resolver's verdict must be clean. */
    static TsonApiDescription routesOf(Tson tson, String file, String apiName) {
        apiOf(tson, file, apiName);
        return TsonApiSchema.describedBy(tson, OrdersExamples.id(file));
    }

    static Api apiOf(Tson tson, String file, String apiName) {
        List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source(file));
        assertEquals(List.of(), problems, () -> file + ": " + problems);
        var entries = tson.schemaRegistry().get(OrdersExamples.id(file)).orElseThrow().schema().entries();
        return assertInstanceOf(Api.class, OrdersExamples.held(entries, "apis", Api.class).get(apiName));
    }

    @Test
    void everyExampleResolves() {
        Tson tson = tson();
        for (String file : OrdersExamples.FILES) {
            List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source(file));
            assertEquals(List.of(), problems, () -> file + ": " + problems);
        }
    }

    /** Documentation is annotation on the key: {@code @summary} the short form, {@code @doc} the long. */
    @Test
    void anEndpointsSummaryAndDocRideOnItsKey() {
        Api api = apiOf(tson(), "orders-api-1.tn", "orders_api");
        var post = api.resources().get("/orders").endpoints().getAnnotations("POST");
        assertEquals("Place an order.", post.value("summary", String.class).orElseThrow());
        assertTrue(post.value("doc", String.class).orElseThrow().startsWith("Accepts a new order"));
    }

    @Test
    void theInterfaceOnlyExampleResolves() {
        Tson tson = tson();
        List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source("orders-1.tn"));
        assertEquals(List.of(), problems, () -> "" + problems);

        var entries = tson.schemaRegistry().get(OrdersExamples.id("orders-1.tn")).orElseThrow().schema().entries();
        var interfaces = OrdersExamples.held(entries, "interfaces", Interface.class);
        Interface orders = interfaces.get("orders");
        assertEquals(List.of("place_order", "get_order", "list_orders", "cancel_order"),
                List.copyOf(orders.methods().keySet()));
        assertEquals("Cancel an order. Cancelling twice is the same as cancelling once.",
                orders.methods().getAnnotations("cancel_order").value("doc", String.class).orElseThrow());
        assertEquals(List.of("orders.orders"), interfaces.get("orders_v2").extended().stream().map(TypeRef::name)
                .toList());
        // Facts about a method are bare annotations on its key, not fields of its signature.
        assertTrue(orders.methods().getAnnotations("get_order").has("safe"));
        assertTrue(orders.methods().getAnnotations("cancel_order").has("idempotent"));
        assertFalse(orders.methods().getAnnotations("place_order").has("safe"));
    }

    @Test
    void theWebServiceOnlyExampleReadsIntoRoutes() {
        TsonApiDescription routes = routesOf(tson(), "orders-api-inline-1.tn", "orders_api");

        assertEquals(3, routes.routes().size());
        Route items = routes.route(HttpVerb.GET, "/orders/{id}/items").orElseThrow();
        assertEquals(Map.of("id", RequestPlacement.Location.PATH), items.placement().fields());
        assertEquals(201, routes.route(HttpVerb.POST, "/orders").orElseThrow().status());
        // On an operation the marker sits before the verb key, the same way -- and the reader checks it.
        Api api = apiOf(tson(), "orders-api-inline-1.tn", "orders_api");
        assertTrue(api.resources().get("/orders/{id}/items").endpoints().getAnnotations("GET").has("safe"));
    }

    @Test
    void theBothExampleReadsIntoRoutesAndHonoursItsClaim() {
        TsonApiDescription routes = routesOf(tson(), "orders-api-1.tn", "orders_api");

        assertEquals(4, routes.routes().size());
        Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(List.of("order"), create.placement().at(RequestPlacement.Location.BODY));
        assertEquals(List.of("idempotency_key"), create.placement().at(RequestPlacement.Location.HEADER));
        assertEquals("new_order", create.request().orElseThrow().name());
        assertEquals(List.of("status", "page", "page_size"),
                routes.route(HttpVerb.GET, "/orders").orElseThrow().placement().at(RequestPlacement.Location.QUERY));
        assertEquals(Map.of("id", RequestPlacement.Location.PATH),
                routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow().placement().fields());
    }
}
