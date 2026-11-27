package io.ltr8.tson.http.helidon.demo;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpSchema;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaCatalog;
import io.ltr8.tson.http.schema.problem.TsonProblem;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.service.TsonServiceSchema;
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
                !!import:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(OrderServer.SCHEMA_ID));

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("ABC-1"), response.body());
        assertTrue(response.body().contains("6"), response.body());
    }

    /** The second: every problem at once, not just the first. */
    @Test
    void rejectsAnInvalidOrderWithEveryDiagnostic() throws Exception {
        HttpResponse<String> response = post("""
                !!import:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID));

        assertEquals(400, response.statusCode());
        TsonHttpCodec problems = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = problems.readObjectAs(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)),
                "application/tson", TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(2, problem.errors().size());
        assertTrue(problem.errors().stream().allMatch(e -> e.code() == Diagnostic.Code.FIELD_REQUIRED));
    }

    /** The third: the same order as JSON, which the description says create_order speaks, answered in JSON. */
    @Test
    void acceptsAJsonOrderAndAnswersInJson() throws Exception {
        HttpResponse<String> response = postJson("{\"sku\": \"ABC-1\", \"quantity\": 3}");
        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"),
                response.headers().toString());
        JsonValue order = Json.parse(response.body());
        assertEquals("ABC-1", order.get("sku").asString());
        assertEquals(6, order.get("quantity").asInt());
    }

    /** And an invalid JSON order is refused in JSON, with every problem at once, as a TSON one is. */
    @Test
    void refusesAnInvalidJsonOrderInJson() throws Exception {
        HttpResponse<String> response = postJson("{}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"),
                response.headers().toString());
        assertEquals(400, Json.parse(response.body()).get("status").asInt());
    }

    /** The fourth. */
    @Test
    void rejectsABodyThatIsNeitherTsonNorJson() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, response.statusCode());
    }

    private HttpResponse<String> postJson(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** The last two: both schemas published at their own identity paths. */
    @Test
    void publishesBothSchemasAtTheirIdentityPaths() throws Exception {
        assertEquals(OrderServer.SCHEMA, get("/2026/40/app/order-1.tn").body());
        assertEquals(TsonProblemSchema.source(), get("/2026/40/io/ltr8/http/problem.tn").body());
    }

    /**
     * The description imports {@code http.tn}, which imports {@code service.tn}, so a client resolving the description
     * fetches both: each is published at its own identity path.
     */
    @Test
    void publishesBothServiceLayersAtTheirIdentityPaths() throws Exception {
        assertEquals(TsonHttpSchema.source(), get(URI.create(TsonHttpSchema.ID).getPath()).body());
        assertEquals(TsonServiceSchema.source(), get(URI.create(TsonServiceSchema.ID).getPath()).body());
    }

    /**
     * The schema route carries the headers its description declares on a success, and a miss carries none of them,
     * so a cache keeps a published schema and never a 404.
     */
    @Test
    void theSchemaRouteCarriesTheHeadersItsDescriptionDeclares() throws Exception {
        assertEquals(Optional.of(TsonSchemaCatalog.IMMUTABLE),
                get("/2026/40/app/order-1.tn").headers().firstValue("Cache-Control"));
        HttpResponse<String> miss = get("/2026/40/app/nope-1.tn");
        assertEquals(404, miss.statusCode());
        assertEquals(Optional.empty(), miss.headers().firstValue("Cache-Control"));
    }
    /**
     * <b>The schemas name their identities literally, so something has to hold them to the constants.</b>
     * A {@code .tn} file cannot interpolate {@code TsonProblemSchema.ID}, and a published document naming its
     * imports literally is correct anyway; this holds each identity to its constant.
     */
    @Test
    void identitiesMatchTheConstants() {
        assertTrue(OrderServer.SCHEMA.startsWith("!!id:\"" + OrderServer.SCHEMA_ID + "\""),
                OrderServer.SCHEMA.lines().findFirst().orElse(""));
        assertTrue(OrderServer.API.startsWith("!!id:\"" + OrderServer.API_ID + "\""),
                OrderServer.API.lines().findFirst().orElse(""));

        assertTrue(OrderServer.SCHEMA.contains("!!import:\"" + TsonProblemSchema.ID + "\""),
                "the error schema composes the CURRENT problem schema");
        assertTrue(OrderServer.API.contains("!!import:\"" + TsonHttpSchema.ID + "\""),
                "the description imports the CURRENT http.tn");
    }


    /**
     * <b>A document naming a schema this server does not publish is the sender's mistake, and is told so.</b>
     * The caller chooses that identity, so a 500 would let any client manufacture one; and among the 4xx and
     * 5xx answers, which is right turns on <em>whose</em> doing it was. Nothing serves that identity, which is
     * {@code NOT_FOUND} -- the reference is wrong and the sender is who can fix it -- where a host that timed
     * out would be a 504 and one that failed a 502.
     *
     * <p>Two upstream changes had to land for this to be answerable. {@code SchemaSource.ofMap} refuses by
     * the contract, where a {@code Map::get} returning {@code null} reached the registry and threw a
     * {@code NullPointerException} the boundary could only read as an internal fault. And
     * the reason survives the collecting receiver as the diagnostic's own code -- one per reason -- where
     * before it was gone by the time a status was chosen and the whole class rounded to 502.
     */
    @Test
    void aDocumentNamingAnUnknownSchemaIsTheSendersMistake() throws Exception {
        HttpResponse<String> response = post("""
                !!import:"https://schemas.example.com/2026/40/app/nowhere-1.tn"
                !order { sku: "ABC-1"  quantity: 3 }""");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("unusable-schema-reference"), response.body());
    }

}
