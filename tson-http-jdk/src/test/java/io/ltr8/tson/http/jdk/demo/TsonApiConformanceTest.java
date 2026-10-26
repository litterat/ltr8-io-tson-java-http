package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.api.HttpVerb;
import io.ltr8.tson.http.api.InterfaceMethod;
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
                .withMetaNameBinder(TsonApiSchema.metaNameBinder())
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
                !!schema:"%s"
                !order { sku: "%s"  quantity: %d }""".formatted(OrderServer.SCHEMA_ID, sku, quantity);
    }

    @Test
    void theServerPublishesADescriptionOfItself() {
        Set<String> keys = new TreeSet<>();
        api.routes().forEach(route -> keys.add(route.key()));
        assertEquals(Set.of("POST /orders", "GET /{+schemaPath}"), keys);

        Route post = api.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new InterfaceMethod("orders.orders", "place_order")), post.method());
        assertEquals("order", post.request().orElseThrow().name(), "a resolved reference, borrowed from the method");
        assertEquals(Optional.of("Place an order"), post.summary());
        assertEquals(Optional.of("Accept an order and confirm it with the quantity doubled."), post.doc(),
                "the method's own documentation, since the verb key adds none");
    }

    /**
     * Each kind of answer, exercised: the declared success status with the response type, a declared business error
     * at the status its type fixes, and a transport refusal answered as a {@code problem}.
     */
    @Test
    void everyResponseMatchesWhatTheDescriptionDeclares() throws Exception {
        Route post = api.route(HttpVerb.POST, "/orders").orElseThrow();

        assertResponseMatches(post, postOrder(order("ABC-1", 3)), 201);
        assertResponseMatches(post, postOrder("""
                !!schema:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID)), 400);
        assertResponseMatches(post, postOrder(order(OrderServer.UNSTOCKED_SKU, 1)), 404);
    }

    /**
     * The schema route is a document: its success is labelled by the media type the description states rather
     * than read as a value, its request record is placed from the path, and a miss is the boundary's problem.
     */
    @Test
    void theSchemaRouteIsADocumentOfItsMediaType() throws Exception {
        Route getSchema = api.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertEquals(Optional.of("application/tson"), getSchema.mediaType());
        assertEquals("schema_ref", getSchema.request().orElseThrow().name());
        assertEquals(Map.of("schemaPath", RequestPlacement.Location.PATH), getSchema.placement().fields());

        assertResponseMatches(getSchema, get("/2026/38/app/order-1.tn"), 200);
        assertResponseMatches(getSchema, get("/2026/38/app/nope-1.tn"), 404);
    }

    /** The description is served at its own identity's path, by the catalog like every other schema. */
    @Test
    void theDescriptionIsPublishedAtItsOwnIdentityPath() throws Exception {
        HttpResponse<String> response = get(URI.create(OrderServer.API_ID).getPath());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("!!id:\"" + OrderServer.API_ID + "\""), response.body());
    }

    /**
     * <b>An endpoint speaks the encodings it declares, and no others.</b> One that declares JSON is sent JSON
     * and asked for JSON, and each reply is held to the description as a TSON one is -- the status declared,
     * and the body read against the schema its {@code TSON-Schema} header names, at the type the description
     * gives for that status. A JSON body has no type-ref to show, so validating it at the declared type is the
     * check. One that does not declare JSON must refuse it, or the description understates the endpoint.
     */
    @Test
    void everyEndpointSpeaksTheEncodingsItDeclares() throws Exception {
        Tson published = serverResolved();
        Json json = Json.of(ProcessorConfig.defaults().withDataBindContext(published.dataBindContext()))
                .withSchemas(published.schemaRegistry());
        for (Route route : api.routes()) {
            if (route.mediaType().isPresent() || route.verb() != HttpVerb.POST) {
                continue;   // no body to send in either encoding
            }
            if (!route.speaksJson()) {
                assertEquals(415, postJson(route.path(), "{}").statusCode(),
                        route.key() + " does not declare JSON, so it must not read it");
                continue;
            }
            assertJsonResponseMatches(json, route, postJson(route.path(),
                    "{\"sku\": \"ABC-1\", \"quantity\": 3}"), 201);
            assertJsonResponseMatches(json, route, postJson(route.path(), "{}"), 400);
            assertJsonResponseMatches(json, route, postJson(route.path(),
                    "{\"sku\": \"" + OrderServer.UNSTOCKED_SKU + "\", \"quantity\": 1}"), 404);
        }
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, application/problem+json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * The type the description gives a reply at {@code status}: the response type at the endpoint's success status,
     * a business error at the status its type fixes, and otherwise -- a transport refusal, which no endpoint lists
     * because every endpoint has them -- the error boundary's {@code problem}. Any other success status is one the
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
        return "problem";
    }

    /** {@link #assertResponseMatches} for JSON: the schema from the header, the type from the description. */
    private void assertJsonResponseMatches(Json json, Route route, HttpResponse<String> response,
                                           int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        String mediaType = response.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(TsonMediaType.namesJson(mediaType), () -> "a JSON client was answered in " + mediaType);
        String type = declaredType(route, response.statusCode());

        String schema = TsonSchemaHeader.parse(response.headers().firstValue(TsonSchemaHeader.NAME).orElse(null))
                .orElseThrow(() -> new AssertionError("a JSON " + response.statusCode()
                        + " names no schema, and has nowhere but the header to name one"));
        assertEquals(200, get(URI.create(schema).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " reply names " + schema
                        + ", which this server does not publish");
        assertEquals(java.util.List.of(), json.validate(response.body(), schema, type),
                () -> "the " + response.statusCode() + " body is not a " + type + " of " + schema + ": "
                        + response.body());
    }

    /**
     * Holds one response to the description: a document's success must carry its media type, and any other reply
     * must be self-describing -- its own {@code !!schema} and type-ref the ones the description gives that status.
     * Responses being self-describing is what makes this checkable without the description carrying examples.
     */
    private void assertResponseMatches(Route route, HttpResponse<String> response, int expectedStatus)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        if (route.mediaType().isPresent() && response.statusCode() == route.status()) {
            String contentType = response.headers().firstValue("Content-Type").orElseThrow();
            assertTrue(contentType.startsWith(route.mediaType().get()),
                    () -> route.key() + " is a document of " + route.mediaType().get() + ", answered " + contentType);
            return;
        }
        String type = declaredType(route, response.statusCode());
        assertTrue(response.body().contains("!" + type + " "),
                () -> "the " + response.statusCode() + " body is not a " + type + ": " + response.body());

        // And the schema it names must be one this server publishes -- fetched, not assumed.
        TsonDocumentHeader header = TsonDocumentPeek.of(response.body()).header();
        assertTrue(header.schema().isPresent(), () -> "a self-describing body: " + response.body());
        assertEquals(200, get(URI.create(header.schema().orElseThrow()).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " body names " + header.schema().orElseThrow()
                        + ", which this server does not publish");
    }
}
