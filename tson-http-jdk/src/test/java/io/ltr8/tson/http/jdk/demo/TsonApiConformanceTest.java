package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonDocumentPeek;
import io.ltr8.tson.http.api.HttpMethod;
import io.ltr8.tson.http.api.Operation;
import io.ltr8.tson.http.api.Parameter;
import io.ltr8.tson.http.api.ParameterLocation;
import io.ltr8.tson.http.api.Response;
import io.ltr8.tson.http.api.TsonApiDescription;
import io.ltr8.tson.http.api.TsonApiSchema;
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
        Tson tson = Tson.builder()
                .metaNameBinder(TsonApiSchema.metaNameBinder())
                .schemaSource(uri -> {
                    if (uri.startsWith(TsonApiSchema.ID)) {
                        return TsonApiSchema.source();
                    }
                    try {
                        return get(URI.create(uri).getPath()).body();
                    } catch (Exception e) {
                        throw new IllegalStateException("not published by this server: " + uri, e);
                    }
                })
                .build();
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
        assertEquals(200, get("/2026/32/app/order-1.tn").statusCode());
        assertTrue(getSchema.responseFor(200).isPresent());
        assertResponseMatches(getSchema, get("/2026/32/app/nope-1.tn"), 404);

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
        TsonDocumentPeek peek = TsonDocumentPeek.of(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        assertTrue(peek.schema().isPresent(), () -> "a self-describing body: " + response.body());
        assertEquals(200, get(URI.create(peek.schema().orElseThrow()).getPath()).statusCode(),
                () -> "the " + response.statusCode() + " body names " + peek.schema().orElseThrow()
                        + ", which this server does not publish");
    }
}
