package io.ltr8.tson.http.helidon.demo;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblem;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.ltr8.tson.http.api.TsonApiSchema;
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

    private io.helidon.webserver.WebServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop();
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
        assertEquals(OrderServer.SCHEMA, get("/2026/35/app/order-1.tn").body());
        assertEquals(TsonProblemSchema.source(), get("/2026/35/ltr8/http/problem-1.tn").body());
    }
    /**
     * <b>The schemas name their identities literally, so something has to hold them to the constants.</b>
     * They were Java text blocks interpolating {@code TsonProblemSchema.ID}; as real {@code .tn} files they
     * cannot, and a published document naming its imports literally is correct anyway. This is what the
     * interpolation used to guarantee for free.
     */
    @Test
    void identitiesMatchTheConstants() {
        assertTrue(OrderServer.SCHEMA.startsWith("!!id:\"" + OrderServer.SCHEMA_ID + "\""),
                OrderServer.SCHEMA.lines().findFirst().orElse(""));
        assertTrue(OrderServer.ERRORS.startsWith("!!id:\"" + OrderServer.ERRORS_ID + "\""),
                OrderServer.ERRORS.lines().findFirst().orElse(""));
        assertTrue(OrderServer.API.startsWith("!!id:\"" + OrderServer.API_ID + "\""),
                OrderServer.API.lines().findFirst().orElse(""));

        assertTrue(OrderServer.ERRORS.contains("!!import:\"" + TsonProblemSchema.ID + "\""),
                "the error schema composes the CURRENT problem schema");
        assertTrue(OrderServer.API.contains("!!meta:\"" + TsonApiSchema.ID + "\""),
                "the description is governed by the CURRENT meta layer");
    }


    /**
     * <b>A document naming a schema this server does not publish is the sender's mistake, and is told so.</b>
     * The caller chooses that identity, so a 500 would let any client manufacture one; and among the 4xx and
     * 5xx answers, which is right turns on <em>whose</em> doing it was. Nothing serves that identity, which is
     * {@code NOT_FOUND} -- the reference is wrong and the sender is who can fix it -- where a host that timed
     * out would be a 504 and one that failed a 502.
     *
     * <p>Two upstream changes had to land for this to be answerable. {@code TsonSchemaSource.ofMap} refuses by
     * the contract, where a {@code Map::get} returning {@code null} reached the registry and threw a
     * {@code NullPointerException} the boundary could only read as an internal fault. And
     * the reason survives the collecting receiver as the diagnostic's own code -- one per reason -- where
     * before it was gone by the time a status was chosen and the whole class rounded to 502.
     */
    @Test
    void aDocumentNamingAnUnknownSchemaIsTheSendersMistake() throws Exception {
        HttpResponse<String> response = post("""
                !!schema:"https://schemas.example.com/2026/35/app/nowhere-1.tn"
                !order { sku: "ABC-1"  quantity: 3 }""");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("unusable-schema-reference"), response.body());
    }

}
