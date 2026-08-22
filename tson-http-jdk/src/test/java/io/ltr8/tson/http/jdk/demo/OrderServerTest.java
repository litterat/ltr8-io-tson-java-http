package io.ltr8.tson.http.jdk.demo;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblem;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real demo server, not a copy of it -- which is the point of the demo living in a source set the
 * tests can see. A demo nobody exercises is documentation that quietly stops being true.
 */
class OrderServerTest {

    private com.sun.net.httpserver.HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** The first curl the demo prints. */
    @Test
    void acceptsAValidOrder() throws Exception {
        HttpResponse<String> response = post("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(OrderServer.SCHEMA_ID));

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("ABC-1"), response.body());
        assertTrue(response.body().contains("6"), response.body());
    }

    /** The second: every problem at once, not just the first. */
    @Test
    void rejectsAnInvalidOrderWithEveryDiagnostic() throws Exception {
        HttpResponse<String> response = post("""
                !!schema:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID));

        assertEquals(400, response.statusCode());
        TsonHttpCodec problems = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = problems.readObjectAs(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
        assertEquals(2, problem.errors().size());
        assertTrue(problem.errors().stream().allMatch(e -> e.code() == Diagnostic.Code.FIELD_REQUIRED));
    }

    /** The third. */
    @Test
    void rejectsABodyThatIsNotTson() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, response.statusCode());
    }

    /** The last two: both schemas published at their own identity paths. */
    @Test
    void publishesBothSchemasAtTheirIdentityPaths() throws Exception {
        assertEquals(OrderServer.SCHEMA, get("/2026/32/app/order-1.tn").body());
        assertEquals(TsonProblemSchema.source(), get("/2026/32/ltr8/http/problem-2.tn").body());
    }
}
