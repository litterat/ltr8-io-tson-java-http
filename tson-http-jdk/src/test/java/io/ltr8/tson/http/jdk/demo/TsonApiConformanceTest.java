package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.http.TsonApi;
import io.ltr8.tson.http.TsonDocumentPeek;
import io.ltr8.tson.http.TsonSchemaCatalog;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo server checked against its own API description.
 *
 * <p><b>This is the point of writing a description at all.</b> A description nothing executes is documentation
 * that quietly stops being true — the lesson the demo servers here already taught, and the reason they are
 * driven by tests rather than only printed. So this fetches the description <em>from the running server</em>,
 * reads it as a TSON document governed by {@code api-1.tn}, and holds the server to it.
 *
 * <p>What it can check without example bodies, because responses are self-describing: every schema the
 * description references is actually published, and every response's status and its body's own
 * {@code !!schema}/type-ref match what the description declares for that status. A server that starts returning
 * a different type, or a status it never declared, fails here.
 */
class TsonApiConformanceTest {

    private HttpServer server;
    private HttpClient client;
    private String base;
    private TsonApi api;

    @BeforeEach
    void startServer() throws Exception {
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        // Fetched over HTTP, not read from the constant: if it is not published, there is no description.
        api = TsonApi.read(get("/2026/32/app/orders-api-1.tn").body());
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

    /** The description resolves against api-1.tn and says what it should — it is a validated document. */
    @Test
    void theServerPublishesADescriptionOfItself() {
        assertEquals("Orders", api.api().title());
        assertEquals(2, api.operations().size());
        TsonApi.Operation post = api.operation(TsonApi.HttpMethod.POST, "/orders").orElseThrow();
        assertEquals(OrderServer.SCHEMA_ID, post.request().orElseThrow().schema());
        assertEquals("order", post.request().orElseThrow().type());
    }

    /**
     * A description referencing a schema its own server does not publish is a contract a client cannot obtain.
     * Derived entirely from the description, so a new operation is covered the moment it is declared.
     */
    @Test
    void everySchemaTheDescriptionReferencesIsPublished() throws Exception {
        assertTrue(api.referencedSchemas().size() >= 3, "expected order, problem and the error schema");
        for (String schema : api.referencedSchemas()) {
            String path = URI.create(schema).getPath();
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), schema + " is referenced but not published at " + path);
            assertTrue(response.body().contains("!!id:\"" + schema + "\""),
                    path + " serves a document that is not " + schema);
        }
    }

    /** Each declared response, exercised: the status is one the description declares, and so is the body. */
    @Test
    void everyResponseMatchesWhatTheDescriptionDeclares() throws Exception {
        TsonApi.Operation post = api.operation(TsonApi.HttpMethod.POST, "/orders").orElseThrow();

        assertResponseMatches(post, postOrder(order("ABC-1", 3)), 201);
        assertResponseMatches(post, postOrder("""
                !!schema:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID)), 400);
        assertResponseMatches(post, postOrder(order(OrderServer.UNSTOCKED_SKU, 1)), 404);
    }

    /** A status the description does not declare is a contract violation even if the body is fine. */
    @Test
    void theSchemaRouteMatchesItsDeclaredResponsesToo() throws Exception {
        TsonApi.Operation getSchema = api.operation(TsonApi.HttpMethod.GET, "/{schemaPath}").orElseThrow();
        assertEquals(200, get("/2026/32/app/order-1.tn").statusCode());
        assertTrue(getSchema.responseFor(200).isPresent());
        assertResponseMatches(getSchema, get("/2026/32/app/nope-1.tn"), 404);

        TsonApi.Parameter path = getSchema.parameters().getFirst();
        assertEquals(TsonApi.ParameterLocation.PATH, path.in());
        assertTrue(path.required());
    }

    /**
     * Holds one response to the description: the status must be declared, and where the description names a
     * body, the response's own {@code !!schema} and type-ref must be the ones it names. Responses being
     * self-describing is what makes this checkable without the description carrying examples.
     */
    private void assertResponseMatches(TsonApi.Operation operation, HttpResponse<String> response,
                                       int expectedStatus) {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        TsonApi.Response declared = operation.responseFor(response.statusCode()).orElseThrow(
                () -> new AssertionError(operation.method() + " " + operation.path() + " answered "
                        + response.statusCode() + ", which its description does not declare"));

        Optional<TsonApi.Body> body = declared.body();
        if (body.isEmpty()) {
            return;
        }
        TsonDocumentPeek peek = TsonDocumentPeek.of(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.of(body.get().schema()), peek.schema(),
                () -> "the " + response.statusCode() + " body names a different schema than declared: "
                        + response.body());
        assertTrue(response.body().contains("!" + body.get().type() + " "),
                () -> "the " + response.statusCode() + " body is not a " + body.get().type() + ": "
                        + response.body());
    }
}
