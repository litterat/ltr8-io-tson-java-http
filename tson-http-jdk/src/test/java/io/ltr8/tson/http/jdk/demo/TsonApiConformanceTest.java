package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.http.TsonBindings;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.http.TsonSchemaCatalog;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.api.ExchangeName;
import io.ltr8.tson.http.api.HttpVerb;
import io.ltr8.tson.http.api.RequestPlacement;
import io.ltr8.tson.http.api.Route;
import io.ltr8.tson.http.api.TsonApiDescription;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo server checked against its own API description.
 *
 * <p><b>This is the point of writing a description at all.</b> A description nothing executes is documentation
 * that quietly stops being true — the lesson the demo servers here already taught, and the reason they are
 * driven by tests rather than only printed. So this fetches the description <em>from the running server</em>
 * and holds the server to it.
 *
 * <p><b>Resolving it is most of the check, now that it is a schema.</b> The description is loaded through a
 * schema source that fetches from the server itself, so resolution proves in one step what used to take two
 * assertions and forty lines of application code: every schema the description references is published and
 * reachable, and every payload type it names actually exists in one of them. What is left to assert by hand
 * is the part no compiler can know — that the server's real responses match what it declared.
 */
class TsonApiConformanceTest {

    private HttpServer server;
    private HttpClient client;
    private String base;
    private TsonApiDescription api;

    @BeforeEach
    void startServer() throws Exception {
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        api = TsonApiSchema.describedBy(serverResolved(), OrderServer.API_ID);
    }

    /**
     * The description resolved against the schemas <em>the same server publishes</em> — every import fetched
     * over HTTP from it. A description referencing a schema the server does not serve, or naming a type none
     * of them declares, fails here rather than being published as a contract nobody can act on.
     */
    private Tson serverResolved() throws Exception {
        String description = get(URI.create(OrderServer.API_ID).getPath()).body();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withDataBindContext(TsonBindings.of(TsonApiSchema.bindings()))
                .withSchemaAccess(SchemaAccess.of(uri -> {
                    String metaLayer = TsonApiSchema.publishedById().get(uri);
                    if (metaLayer != null) {
                        return metaLayer;
                    }
                    try {
                        return get(URI.create(uri).getPath()).body();
                    } catch (Exception e) {
                        throw new IllegalStateException("not published by this server: " + uri, e);
                    }
                })));
        tson.resolve(description);
        return tson;
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postOrder(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String order(String sku, int quantity) {
        return """
                !!import:"%s"
                !order { sku: "%s"  quantity: %d }""".formatted(OrderServer.SCHEMA_ID, sku, quantity);
    }

    @Test
    void theServerPublishesADescriptionOfItself() {
        Set<String> keys = new TreeSet<>();
        api.routes().forEach(route -> keys.add(route.key()));
        assertEquals(Set.of("POST /orders", "GET /{+schemaPath}"), keys);

        Route post = api.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new ExchangeName("orders", "place_order")), post.exchange());
        assertEquals("order", post.request().orElseThrow().name(), "a resolved reference, borrowed from the exchange");
        assertEquals(Optional.of("Place an order"), post.summary());
        assertEquals(Optional.of("Accept an order and confirm it with the quantity doubled."), post.doc(),
                "the exchange's own documentation, since the verb key adds none");
    }

    /**
     * Each kind of answer, exercised: the declared success status with the response type, a declared business error
     * at the status its type fixes, and a transport refusal answered as a {@code problem.details}.
     */
    @Test
    void everyResponseMatchesWhatTheDescriptionDeclares() throws Exception {
        Route post = api.route(HttpVerb.POST, "/orders").orElseThrow();

        assertResponseMatches(post, postOrder(order("ABC-1", 3)), 201);
        assertResponseMatches(post, postOrder("""
                !!import:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID)), 400);
        assertResponseMatches(post, postOrder(order(OrderServer.UNSTOCKED_SKU, 1)), 404);
    }

    /**
     * The schema route is a document: its success is labelled by the media type the description lists rather
     * than read as a value, its request record is placed from the path, and a miss is the boundary's problem.
     */
    @Test
    void theSchemaRouteIsADocumentOfItsMediaType() throws Exception {
        Route getSchema = api.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertTrue(getSchema.document());
        assertEquals(Set.of("application/tson"), getSchema.mediaTypes());
        assertEquals("schema_ref", getSchema.request().orElseThrow().name());
        assertEquals(Map.of("schemaPath", RequestPlacement.Location.PATH), getSchema.placement().fields());

        assertEquals(Map.of("cache-control", TsonSchemaCatalog.IMMUTABLE), getSchema.responseHeaders(),
                "a published schema never changes (§3.5), and the description says so to a cache");

        assertResponseMatches(getSchema, get("/2026/40/app/order-1.tn"), 200);
        assertResponseMatches(getSchema, get("/2026/40/app/nope-1.tn"), 404);
    }

    /** The description is served at its own identity's path, by the catalog like every other schema. */
    @Test
    void theDescriptionIsPublishedAtItsOwnIdentityPath() throws Exception {
        HttpResponse<String> response = get(URI.create(OrderServer.API_ID).getPath());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("!!id:\"" + OrderServer.API_ID + "\""), response.body());
    }

    /**
     * <b>An endpoint speaks the media types it lists.</b> One listing a JSON label is sent JSON under that label
     * and asked for it, and each reply is held to the description as a TSON one is -- the status declared, the
     * label the one asked for, and the body read against the schema its {@code TSON-Schema} header names, at the
     * type the description gives for that status. A JSON body has no type-ref to show, so validating it at the
     * declared type is the check. One listing no JSON label must refuse JSON, or the description understates the
     * endpoint.
     */
    @Test
    void everyEndpointSpeaksTheMediaTypesItLists() throws Exception {
        Tson published = serverResolved();
        Json json = Json.of(ProcessorConfig.defaults().withDataBindContext(published.dataBindContext()))
                .withSchemas(published.schemaRegistry());
        for (Route route : api.routes()) {
            if (route.document() || route.verb() != HttpVerb.POST) {
                continue;   // no body to send in either encoding
            }
            if (!route.speaksJson()) {
                assertEquals(415, postJson(route.path(), "application/json", "{}").statusCode(),
                        route.key() + " lists no JSON label, so it must not read JSON");
                continue;
            }
            for (String label : route.mediaTypes()) {
                if (!TsonMediaType.namesJson(label)) {
                    continue;
                }
                assertJsonResponseMatches(json, route, label, postJson(route.path(), label,
                        "{\"sku\": \"ABC-1\", \"quantity\": 3}"), 201);
                assertJsonResponseMatches(json, route, label, postJson(route.path(), label, "{}"), 400);
                assertJsonResponseMatches(json, route, label, postJson(route.path(), label,
                        "{\"sku\": \"" + OrderServer.UNSTOCKED_SKU + "\", \"quantity\": 1}"), 404);
            }
        }
    }

    private HttpResponse<String> postJson(String path, String label, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", label)
                        .header("Accept", label + ", application/problem+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * The type the description gives a reply at {@code status}: the response type at the endpoint's success status,
     * a business error at the status its type fixes, and otherwise -- a transport refusal, which no endpoint lists
     * because every endpoint has them -- the error boundary's {@code problem.details}. Any other success status is one the
     * description does not declare, a contract violation however good the body.
     */
    private static String declaredType(Route route, int status) {
        if (status == route.status()) {
            return route.response().orElseThrow(() -> new AssertionError(route.key() + " declares no response "
                    + "type for " + status)).name();
        }
        Optional<String> error = route.errorFor(status).map(ref -> ref.name());
        if (error.isPresent()) {
            return error.get();
        }
        if (status < 400) {
            throw new AssertionError(route.key() + " answered " + status + ", which its description does not declare");
        }
        return "problem.details";
    }

    /**
     * {@link #assertResponseMatches} for JSON: a success under the label asked for, any reply in JSON, the schema
     * from the header, the type from the description.
     */
    private void assertJsonResponseMatches(Json json, Route route, String label, HttpResponse<String> response,
                                           int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        String mediaType = response.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(TsonMediaType.namesJson(mediaType), () -> "a JSON client was answered in " + mediaType);
        if (response.statusCode() == route.status()) {
            assertTrue(mediaType.startsWith(label), () -> "asked for " + label + ", answered " + mediaType);
            assertDeclaredHeaders(route, response);
        }
        String type = declaredType(route, response.statusCode());

        java.util.List<String> named =
                TsonSchemaHeader.parse(response.headers().firstValue(TsonSchemaHeader.NAME).orElse(null));
        assertEquals(1, named.size(), () -> "a JSON " + response.statusCode() + " names " + named
                + " in its header, which is the only place it has to name its one schema");
        String schema = named.getFirst();
        assertEquals(200, get(URI.create(schema).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " reply names " + schema
                        + ", which this server does not publish");
        assertEquals(java.util.List.of(), json.validate(response.body(), schema, type),
                () -> "the " + response.statusCode() + " body is not a " + type + " of " + schema + ": "
                        + response.body());
    }

    /**
     * A success reply carries every literal response header the route declares, with the value declared. A template
     * with a variable expands from the response value, which a client checks against the body it got; the demos
     * declare none.
     */
    private static void assertDeclaredHeaders(Route route, HttpResponse<String> response) {
        route.responseHeaders().forEach((name, value) -> {
            if (!value.contains("{")) {
                assertEquals(Optional.of(value), response.headers().firstValue(name),
                        () -> route.key() + " declares '" + name + ": " + value + "' on a success");
            }
        });
    }

    /**
     * Holds one response to the description: a document's success must carry a media type it lists, and any other
     * reply must be self-describing -- its own {@code !!import} and type-ref the ones the description gives that status.
     * Responses being self-describing is what makes this checkable without the description carrying examples.
     */
    private void assertResponseMatches(Route route, HttpResponse<String> response, int expectedStatus)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        if (response.statusCode() == route.status()) {
            assertDeclaredHeaders(route, response);
        }
        if (route.document() && response.statusCode() == route.status()) {
            String contentType = response.headers().firstValue("Content-Type").orElseThrow();
            assertTrue(route.mediaTypes().stream().anyMatch(contentType::startsWith),
                    () -> route.key() + " is a document of " + route.mediaTypes() + ", answered " + contentType);
            return;
        }
        String type = declaredType(route, response.statusCode());
        assertTrue(response.body().contains("!" + type + " "),
                () -> "the " + response.statusCode() + " body is not a " + type + ": " + response.body());

        // And the schema it names must be one this server publishes -- fetched, not assumed.
        TsonDocumentHeader header = TsonDocumentPeek.of(response.body()).header();
        assertEquals(1, header.imports().size(), () -> "a self-describing body: " + response.body());
        String imported = header.imports().getFirst();
        assertEquals(200, get(URI.create(imported).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " body imports " + imported
                        + ", which this server does not publish");
    }
}
