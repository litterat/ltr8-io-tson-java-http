package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.api.HttpMethod;
import io.ltr8.tson.http.api.Operation;
import io.ltr8.tson.http.api.Parameter;
import io.ltr8.tson.http.api.ParameterLocation;
import io.ltr8.tson.http.api.Response;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    if (uri.startsWith(TsonApiSchema.ID)) {
                        return TsonApiSchema.source();
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
        assertEquals(java.util.Set.of("create_order", "get_schema"), api.operations().keySet());

        Operation post = api.operation(HttpMethod.POST, "/orders").orElseThrow();
        assertEquals("order", post.request().orElseThrow().name(), "a resolved reference, not a string");
        assertEquals(Optional.of("Place an order"), post.summary());
        assertFalse(post.isDeprecated());
    }

    /** Each declared response, exercised: the status is one the description declares, and so is the body. */
    @Test
    void everyResponseMatchesWhatTheDescriptionDeclares() throws Exception {
        Operation post = api.operation(HttpMethod.POST, "/orders").orElseThrow();

        assertResponseMatches(post, postOrder(order("ABC-1", 3)), 201);
        assertResponseMatches(post, postOrder("""
                !!schema:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID)), 400);
        assertResponseMatches(post, postOrder(order(OrderServer.UNSTOCKED_SKU, 1)), 404);
    }

    /** A status the description does not declare is a contract violation even if the body is fine. */
    @Test
    void theSchemaRouteMatchesItsDeclaredResponsesToo() throws Exception {
        Operation getSchema = api.operation(HttpMethod.GET, "/{schemaPath}").orElseThrow();
        assertEquals(200, get("/2026/36/app/order-1.tn").statusCode());
        assertTrue(getSchema.responseFor(200).isPresent());
        assertResponseMatches(getSchema, get("/2026/36/app/nope-1.tn"), 404);

        Parameter path = getSchema.parameters().getFirst();
        assertEquals(ParameterLocation.PATH, path.in());
        assertTrue(path.required());
        assertEquals("text", path.type().name(), "a scalar, though nothing in the type system enforces that");
    }

    /** The description is served at its own identity's path, by the catalog like every other schema. */
    @Test
    void theDescriptionIsPublishedAtItsOwnIdentityPath() throws Exception {
        HttpResponse<String> response = get(URI.create(OrderServer.API_ID).getPath());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("!!id:\"" + OrderServer.API_ID + "\""), response.body());
    }

    /**
     * <b>An operation speaks the encodings it declares, and no others.</b> One that declares JSON is sent JSON
     * and asked for JSON, and each reply is held to the description as a TSON one is -- the status declared,
     * and the body read against the schema its {@code TSON-Schema} header names, at the type the description
     * gives for that status. A JSON body has no type-ref to show, so validating it at the declared type is the
     * check. One that does not declare JSON must refuse it, or the description understates the endpoint.
     */
    @Test
    void everyOperationSpeaksTheEncodingsItDeclares() throws Exception {
        Tson published = serverResolved();
        Json json = Json.of(ProcessorConfig.defaults().withDataBindContext(published.dataBindContext()))
                .withSchemas(published.schemaRegistry());
        for (Operation operation : api.operations().values()) {
            if (operation.request().isEmpty()) {
                continue;   // no body to send in either encoding
            }
            if (!operation.speaksJson()) {
                assertEquals(415, postJson(operation.path(), "{}").statusCode(),
                        operation.path() + " does not declare JSON, so it must not read it");
                continue;
            }
            assertJsonResponseMatches(json, operation, postJson(operation.path(),
                    "{\"sku\": \"ABC-1\", \"quantity\": 3}"), 201);
            assertJsonResponseMatches(json, operation, postJson(operation.path(), "{}"), 400);
            assertJsonResponseMatches(json, operation, postJson(operation.path(),
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

    /** {@link #assertResponseMatches} for JSON: the schema from the header, the type from the description. */
    private void assertJsonResponseMatches(Json json, Operation operation, HttpResponse<String> response,
                                           int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        String mediaType = response.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(TsonMediaType.namesJson(mediaType), () -> "a JSON client was answered in " + mediaType);
        Response declared = operation.responseFor(response.statusCode()).orElseThrow(
                () -> new AssertionError(operation.method() + " " + operation.path() + " answered "
                        + response.statusCode() + ", which its description does not declare"));
        String type = declared.body().orElseThrow().name();

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
     * Holds one response to the description: the status must be declared, and where the description names a
     * body, the response's own {@code !!schema} and type-ref must be the ones it names. Responses being
     * self-describing is what makes this checkable without the description carrying examples.
     */
    private void assertResponseMatches(Operation operation, HttpResponse<String> response, int expectedStatus)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        Response declared = operation.responseFor(response.statusCode()).orElseThrow(
                () -> new AssertionError(operation.method() + " " + operation.path() + " answered "
                        + response.statusCode() + ", which its description does not declare"));

        Optional<String> type = declared.body().map(ref -> ref.name());
        if (type.isEmpty()) {
            return;
        }
        assertTrue(response.body().contains("!" + type.get() + " "),
                () -> "the " + response.statusCode() + " body is not a " + type.get() + ": " + response.body());

        // And the schema it names must be one this server publishes -- fetched, not assumed.
        TsonDocumentHeader header = TsonDocumentPeek.of(response.body()).header();
        assertTrue(header.schema().isPresent(), () -> "a self-describing body: " + response.body());
        assertEquals(200, get(URI.create(header.schema().orElseThrow()).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " body names " + header.schema().orElseThrow()
                        + ", which this server does not publish");
    }
}
