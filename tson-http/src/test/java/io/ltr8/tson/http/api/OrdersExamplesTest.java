package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link OrdersExamples} resolve against the shipped layer, and the two http projections read into route tables -- so an
 * example that stops being true fails the build rather than the reader.
 *
 * <p>The placement {@code orders-api-1.tn} declares, as the reader computes it:
 * <pre>
 * binding              exchange's request                    path  query      header           body
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
    static TsonApiDescription routesOf(Tson tson, String file, String httpName) {
        httpOf(tson, file, httpName);
        return TsonApiSchema.describedBy(tson, OrdersExamples.id(file));
    }

    /** The entry applying {@code service.http} named {@code httpName}: its resources are keyed by path. */
    static Http httpOf(Tson tson, String file, String httpName) {
        List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source(file));
        assertEquals(List.of(), problems, () -> file + ": " + problems);
        var entries = tson.schemaRegistry().get(OrdersExamples.id(file)).orElseThrow().schema().entries();
        return OrdersExamples.applying(entries, Http.class).get(httpName);
    }

    static Resource resource(Http http, String path) {
        return assertInstanceOf(Resource.class, http.resources().get(path));
    }

    @Test
    void everyExampleResolves() {
        Tson tson = tson();
        for (String file : OrdersExamples.FILES) {
            List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source(file));
            assertEquals(List.of(), problems, () -> file + ": " + problems);
        }
    }

    /** Documentation is annotation on the key: {@code @service.summary} the short form, {@code @doc} the long. */
    @Test
    void anEndpointsSummaryAndDocRideOnItsKey() {
        var http = httpOf(tson(), "orders-api-1.tn", "orders_http");
        var post = resource(http, "/orders").endpoints().getAnnotations("POST");
        assertEquals("Place an order.", post.value("service.summary", String.class).orElseThrow());
        assertTrue(post.value("doc", String.class).orElseThrow().startsWith("Accepts a new order"));
    }

    @Test
    void theProtocolOnlyExampleResolves() {
        Tson tson = tson();
        List<Diagnostic> problems = tson.validateSchema(OrdersExamples.source("orders-1.tn"));
        assertEquals(List.of(), problems, () -> "" + problems);

        var entries = tson.schemaRegistry().get(OrdersExamples.id("orders-1.tn")).orElseThrow().schema().entries();
        var protocols = OrdersExamples.applying(entries, Protocol.class);
        var orders = protocols.get("orders").exchanges();
        assertEquals(List.of("place_order", "get_order", "list_orders", "cancel_order"), List.copyOf(orders.keySet()));
        assertInstanceOf(Exchange.class, orders.get("place_order"));
        assertEquals("Cancel an order. Cancelling twice is the same as cancelling once.",
                orders.getAnnotations("cancel_order").value("doc", String.class).orElseThrow());
        assertEquals(List.of("refund"), List.copyOf(protocols.get("orders_v2").exchanges().keySet()),
                "a protocol lists its own exchanges and nothing more");
        // Facts about an exchange are bare annotations on its key, not fields of its signature.
        assertTrue(orders.getAnnotations("get_order").has("service.safe"));
        assertTrue(orders.getAnnotations("cancel_order").has("service.idempotent"));
        assertFalse(orders.getAnnotations("place_order").has("service.safe"));
    }

    @Test
    void theWebServiceOnlyExampleReadsIntoRoutes() {
        TsonApiDescription routes = routesOf(tson(), "orders-api-inline-1.tn", "orders_http");

        assertEquals(3, routes.routes().size());
        Route items = routes.route(HttpVerb.GET, "/orders/{id}/items").orElseThrow();
        assertEquals(Map.of("id", RequestPlacement.Location.PATH), items.placement().fields());
        assertEquals(201, routes.route(HttpVerb.POST, "/orders").orElseThrow().status());
        // On an operation the marker sits before the verb key, the same way -- and the reader checks it.
        var http = httpOf(tson(), "orders-api-inline-1.tn", "orders_http");
        assertTrue(resource(http, "/orders/{id}/items").endpoints().getAnnotations("GET").has("service.safe"));
    }

    @Test
    void theBothExampleReadsIntoRoutesAndHonoursItsClaim() {
        TsonApiDescription routes = routesOf(tson(), "orders-api-1.tn", "orders_http");

        assertEquals(4, routes.routes().size());
        Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(List.of("order"), create.placement().at(RequestPlacement.Location.BODY));
        assertEquals(List.of("idempotency_key"), create.placement().at(RequestPlacement.Location.HEADER));
        assertEquals("new_order", create.request().orElseThrow().name());
        assertEquals(List.of("status", "page", "page_size"),
                routes.route(HttpVerb.GET, "/orders").orElseThrow().placement().at(RequestPlacement.Location.QUERY));
        assertEquals(Map.of("id", RequestPlacement.Location.PATH),
                routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow().placement().fields());
        assertEquals(Optional.of(new ExchangeName("orders", "place_order")), create.exchange(),
                "the qualified name: the protocol's entry, its exchanges field, then the exchange's key");
        assertEquals(List.of("orders"), httpOf(tson(), "orders-api-1.tn", "orders_http").binds().stream()
                .map(reference -> reference.name()).toList(), "the claim, a field the resolver judged");
    }

    /**
     * The schemes in force per endpoint, as the reader resolves them: the projection's default inherited, replaced by
     * an endpoint's own list, and none where the endpoint writes {@code security: []}.
     */
    @Test
    void theSecuredExampleResolvesTheSchemesInForce() {
        TsonApiDescription routes = routesOf(tson(), "orders-api-secured-1.tn", "orders_http");
        HttpAuth bearer = new HttpAuth("bearer");
        ApiKey partner = new ApiKey(ApiKeyIn.header, "X-Api-Key");

        assertEquals(List.of(bearer), routes.route(HttpVerb.POST, "/orders").orElseThrow().security(), "inherited");
        assertEquals(List.of(partner), routes.route(HttpVerb.GET, "/orders").orElseThrow().security(), "replaced");
        Route fetch = routes.route(HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals(List.of(), fetch.security(), "security: [] is open");
        assertEquals(List.of(bearer, partner), routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow().security());
        assertEquals(Map.of("cache-control", "private, max-age=60"), fetch.responseHeaders(), "the name held folded");
    }
}
