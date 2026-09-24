package io.ltr8.tson.http.helidon;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.TsonDiagnostics;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblem;
import io.ltr8.tson.http.TsonProblemDiagnostic;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately the same tests as the JDK and Javalin adapters', asserting the same behaviour: three adapters
 * over one codec should be indistinguishable from a client's side, and the only way to show that is to ask them
 * the same questions.
 */
class TsonHelidonAdapterTest {

    private static final String SCHEMA_ID = "https://schemas.example.com/2026/36/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/36/app/order-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private WebServer server;
    private HttpClient client;
    private String base;
    private TsonHttpCodec codec;
    private TsonHttpCodec jsonCodec;

    @BeforeEach
    void setUp() {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(uri -> SCHEMA))
                .withDataBindContext(bind));
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);
        jsonCodec = codec.acceptingJson();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        client.close();
    }

    /** Helidon fixes its routing at build time, so each test starts its own server. */
    private void start(Consumer<HttpRouting.Builder> routes) {
        server = WebServer.builder().host("127.0.0.1").port(0).routing(routes).build().start();
        base = "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers.length == 0) {
            request.header("Content-Type", "application/tson");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** The error body is TSON, so read it as TSON -- asserting on substrings would prove nothing about it. */
    private TsonProblem problemFrom(HttpResponse<String> response) {
        TsonHttpCodec problems = new TsonHttpCodec(TsonProblemSchema.tson());
        return problems.readObjectAs(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
    }

    @Test
    void readsAValidatedBodyAndAnswersWithTson() throws Exception {
        start(routing -> routing.post("/orders", TsonHandler.asHandler(codec, tson -> {
            Order order = tson.readObject(Order.class);
            tson.respond(201, new Order(order.sku(), order.quantity() * 2));
        })));

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID));

        assertEquals(201, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/tson"));
        assertTrue(response.body().contains("ABC-1"), response.body());
        assertTrue(response.body().contains("6"), response.body());
    }

    @Test
    void anInvalidBodyBecomesA400CarryingEveryDiagnostic() throws Exception {
        start(routing -> routing.post("/orders",
                TsonHandler.asHandler(codec, tson -> tson.respond(201, tson.readObject(Order.class)))));

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { }""".formatted(SCHEMA_ID));

        assertEquals(400, response.statusCode());
        TsonProblem problem = problemFrom(response);
        assertEquals(400, problem.status());
        assertEquals(2, problem.errors().size(), "both missing fields");
        assertTrue(problem.errors().stream().allMatch(e -> e.code() == Diagnostic.Code.FIELD_REQUIRED));
        assertEquals("/sku", problem.errors().getFirst().path().orElseThrow());
    }

    @Test
    void aWrongMethodIs405WithAllow() throws Exception {
        TsonHandler onlyPost = tson -> {
            tson.requireMethod("POST");
            tson.respondEmpty(204);
        };
        start(routing -> routing
                .post("/orders", TsonHandler.asHandler(codec, onlyPost))
                .get("/orders", TsonHandler.asHandler(codec, onlyPost)));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/orders")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void aNonTsonBodyIs415() throws Exception {
        start(routing -> routing.post("/orders",
                TsonHandler.asHandler(codec, tson -> tson.respond(201, tson.readObject(Order.class)))));
        assertEquals(415, post("/orders", "{}", "Content-Type", "application/json").statusCode());
    }

    /** Checked in the boundary, so it cannot be forgotten -- and before the handler does the work. */
    @Test
    void anUnacceptableAcceptIs406AndTheHandlerNeverRuns() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        start(routing -> routing.post("/orders", TsonHandler.asHandler(codec, tson -> {
            ran.set(true);
            tson.respondEmpty(204);
        })));

        HttpResponse<String> response = post("/orders", "{ a: 1 }",
                "Content-Type", "application/tson", "Accept", "application/json");

        assertEquals(406, response.statusCode());
        assertFalse(ran.get(), "the boundary must refuse before the handler does the work");
    }

    /** A gap is 501, never 400 -- a client told to fix a document that is not wrong cannot ever succeed. */
    @Test
    void aLibraryGapIs501NotABadRequest() throws Exception {
        start(routing -> routing.post("/gap", TsonHandler.asHandler(codec, tson -> {
            throw new UnsupportedOperationException("not implemented yet");
        })));
        assertEquals(501, post("/gap", "{ a: 1 }").statusCode());
    }

    /** An internal message can name a class, a path or an internal host, and a client is not the audience. */
    @Test
    void aServerErrorSaysNothingAboutWhy() throws Exception {
        start(routing -> routing.post("/boom", TsonHandler.asHandler(codec, tson -> {
            throw new IllegalArgumentException("connection to db-primary.internal:5432 refused");
        })));

        HttpResponse<String> response = post("/boom", "{ a: 1 }");

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("db-primary.internal"), response.body());
        assertEquals(Optional.empty(), problemFrom(response).detail(), "a 5xx carries no detail");
    }

    /**
     * <b>A 501 carries the violations the read did find.</b> The status says this server could not check the
     * body; it does not say nothing was learned about it. Dropping the real problems left a sender with
     * nothing to act on, so the next request was byte-for-byte the same one -- a loop that cannot terminate,
     * which is the failure a 501 exists to prevent rather than cause.
     *
     * <p><b>And the {@code SCHEMA_UNREACHABLE} beside them is still withheld</b>, host and all. That is the
     * pair that shows the rule is about content: three diagnostics, one status, and what reaches the client is
     * decided per diagnostic by whom the message describes -- not by the status they arrived under. A gap
     * outranks a fetch failure, so this mixture is reachable and not contrived.
     */
    @Test
    void aGapStillReportsTheProblemsItDidFind() throws Exception {
        start(routing -> routing.post("/mixed", TsonHandler.asHandler(codec, tson -> {
            throw TsonHttpException.invalidDocument(java.util.List.of(
                    TsonDiagnostics.ofSchemaGap(SCHEMA_ID, "order", "generic templates are not implemented yet",
                            Optional.empty()),
                    TsonDiagnostics.ofSchemaError(SCHEMA_ID, "order", "'quantity' is required", Optional.empty()),
                    TsonDiagnostics.ofSchemaUnavailable(SCHEMA_ID, "order", new SchemaFetchException(
                            "https://mirror.internal/x.tn", SchemaFetchException.Reason.TRANSPORT,
                            "connect to mirror.internal failed", null), Optional.empty())));
        })));

        HttpResponse<String> response = post("/mixed", "{ a: 1 }");

        assertEquals(501, response.statusCode());
        TsonProblem problem = problemFrom(response);
        assertEquals(java.util.List.of(Diagnostic.Code.NOT_IMPLEMENTED, Diagnostic.Code.SCHEMA_ERROR),
                problem.errors().stream().map(TsonProblemDiagnostic::code).toList(),
                "the gap and the violation are the client's to see; the unreachable host is not");
        assertFalse(response.body().contains("mirror.internal"), response.body());
        assertTrue(problem.detail().orElseThrow().contains("could not be checked"), problem.detail().toString());
    }

    /**
     * <b>A schema origin that failed still says nothing about itself.</b> The half of the rule that does not
     * change: the message names a host, which under a {@code mapHost} is not even the identity the sender
     * wrote, so status, type and title are the whole body.
     */
    @Test
    void aSchemaOriginFailureNamesNoHost() throws Exception {
        start(routing -> routing.post("/origin", TsonHandler.asHandler(codec, tson -> {
            throw TsonHttpException.invalidDocument(java.util.List.of(
                    TsonDiagnostics.ofSchemaUnavailable(SCHEMA_ID, "order", new SchemaFetchException(
                            "https://mirror.internal/x.tn", SchemaFetchException.Reason.TRANSPORT,
                            "connect to mirror.internal failed", null), Optional.empty())));
        })));

        HttpResponse<String> response = post("/origin", "{ a: 1 }");

        assertEquals(502, response.statusCode());
        assertFalse(response.body().contains("mirror.internal"), response.body());
        TsonProblem problem = problemFrom(response);
        assertEquals(Optional.empty(), problem.detail());
        assertEquals(java.util.List.of(), problem.errors());
    }

    @Test
    void aClientErrorKeepsItsDetail() throws Exception {
        start(routing -> routing.post("/orders", TsonHandler.asHandler(codec, tson -> {
            throw new TsonHttpException(409, "Conflict", "order ABC-1 already exists", List.of(), null);
        })));

        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(409, response.statusCode());
        assertEquals("order ABC-1 already exists", problemFrom(response).detail().orElseThrow());
    }

    @Test
    void aHandlerThatAnswersNothingIsAServerError() throws Exception {
        start(routing -> routing.post("/silent", TsonHandler.asHandler(codec, tson -> { })));
        assertEquals(500, post("/silent", "{ a: 1 }").statusCode());
    }

    /** install is for the routes not written as a TsonHandler -- one application answers failures one way. */
    @Test
    void installMakesAPlainHelidonRouteFailTheSameWay() throws Exception {
        start(routing -> {
            TsonHandler.install(routing, codec);
            routing.post("/plain", (request, response) -> {
                throw new TsonHttpException(409, "Conflict", "order ABC-1 already exists", List.of(), null);
            });
        });

        HttpResponse<String> response = post("/plain", "{ a: 1 }");
        assertEquals(409, response.statusCode());
        assertEquals("order ABC-1 already exists", problemFrom(response).detail().orElseThrow());
    }

    /** A response past the server's output buffer must go out chunked -- proof the document was streamed. */
    @Test
    void aLargeStreamedResponseIsChunked() throws Exception {
        String longSku = "A".repeat(64 * 1024);
        start(routing -> routing.post("/orders",
                TsonHandler.asHandler(codec, tson -> tson.respond(200, new Order(longSku, 3)))));

        HttpResponse<String> response = post("/orders", "{ a: 1 }");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(longSku), "the whole document must arrive");
        assertEquals(Optional.empty(), response.headers().firstValue("Content-Length"),
                "a response past the buffer cannot know its length when the headers go out");
    }

    /** A body already in hand gets a real Content-Length -- which is why both paths exist. */
    @Test
    void aBufferedResponseCarriesItsLength() throws Exception {
        start(routing -> routing.post("/orders", TsonHandler.asHandler(codec,
                tson -> tson.respondBytes(200, tson.codec().write(new Order("ABC-1", 3))))));
        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(String.valueOf(response.body().length()),
                response.headers().firstValue("Content-Length").orElseThrow());
    }

    /** A route whose codec admits and produces JSON as well as TSON -- the only route of its server. */
    private void jsonRoute(String path, TsonHandler handler) {
        start(routing -> routing.post(path, TsonHandler.asHandler(jsonCodec, handler)));
    }

    // ── JSON responses: the same route, answering in what the client negotiated ────────────────────────

    /** Reads an order in either encoding and answers with the doubled order, naming its schema. */
    private void doublingJsonRoute() {
        jsonRoute("/orders", tson -> {
            Order order = tson.readObjectAs(SCHEMA_ID, "order", Order.class);
            tson.respondDescribed(201, new Order(order.sku(), order.quantity() * 2), SCHEMA_ID, "order");
        });
    }

    /**
     * <b>A JSON client gets JSON, and the schema in the header.</b> A JSON body has no in-band channel, so
     * {@code TSON-Schema} is the only place a reply written as JSON can name what governs it ([TSON-JSON] §3.5).
     */
    @Test
    void aJsonClientGetsJsonNamingItsSchemaInTheHeader() throws Exception {
        doublingJsonRoute();

        HttpResponse<String> response = post("/orders", "{\"sku\": \"ABC-1\", \"quantity\": 3}",
                "Content-Type", "application/tson+json", "Accept", "application/tson+json");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("application/tson+json", mediaType(response));
        assertEquals(TsonSchemaHeader.format(SCHEMA_ID),
                response.headers().firstValue(TsonSchemaHeader.NAME).orElseThrow());
        JsonValue order = Json.parse(response.body());
        assertEquals("ABC-1", order.get("sku").asString());
        assertEquals(6, order.get("quantity").asInt());
    }

    /** The same route answers a client with no preference in TSON, the encoding that describes itself in band. */
    @Test
    void aClientWithNoPreferenceGetsSelfDescribingTson() throws Exception {
        doublingJsonRoute();

        HttpResponse<String> response = post("/orders", "{\"sku\": \"ABC-1\", \"quantity\": 3}",
                "Content-Type", "application/json", "Accept", "*/*");

        assertEquals(201, response.statusCode(), response.body());
        assertEquals("application/tson", mediaType(response));
        assertTrue(response.body().contains("!!schema:\"" + SCHEMA_ID + "\""), response.body());
    }

    /**
     * <b>A JSON client's problem is RFC 9457's own format</b>: {@code problem-1.tn}'s {@code problem} written as
     * JSON is an {@code application/problem+json} body, labelled so where the client accepts that type and with
     * the JSON type it asked for where it does not.
     */
    @Test
    void aJsonClientsProblemIsRfc9457Json() throws Exception {
        doublingJsonRoute();

        HttpResponse<String> labelled = post("/orders", "{}", "Content-Type", "application/json",
                "Accept", "application/json, application/problem+json");
        assertEquals(400, labelled.statusCode());
        assertEquals("application/problem+json", mediaType(labelled));
        JsonValue problem = Json.parse(labelled.body());
        assertEquals(400, problem.get("status").asInt());
        assertEquals(2, problem.get("errors").asList().size(), "both missing fields");
        assertEquals("FIELD_REQUIRED", problem.get("errors").get(0).get("code").asString());

        HttpResponse<String> plain = post("/orders", "{}", "Content-Type", "application/json",
                "Accept", "application/json");
        assertEquals(400, plain.statusCode());
        assertEquals("application/json", mediaType(plain));
    }

    /**
     * <b>A response only TSON can carry is sent as TSON wherever the client takes it at all</b> -- a server may
     * send any representation the client accepts -- and is a 406 where it takes none, answered in the JSON it
     * did ask for.
     */
    @Test
    void aTsonOnlyResponseFallsBackToTsonOrIsA406() throws Exception {
        jsonRoute("/bytes", tson -> tson.respondBytes(200, tson.codec().write(new Order("ABC-1", 3))));

        HttpResponse<String> fallback = post("/bytes", "{ a: 1 }",
                "Content-Type", "application/tson", "Accept", "application/json, application/tson;q=0.1");
        assertEquals(200, fallback.statusCode());
        assertEquals("application/tson", mediaType(fallback));

        HttpResponse<String> refused = post("/bytes", "{ a: 1 }",
                "Content-Type", "application/tson", "Accept", "application/json");
        assertEquals(406, refused.statusCode());
        assertEquals("application/json", mediaType(refused));
        assertEquals(406, Json.parse(refused.body()).get("status").asInt());
    }

    /** A response's media type without parameters -- a framework may add a {@code charset} of its own. */
    private static String mediaType(HttpResponse<String> response) {
        TsonMediaType type = TsonMediaType.parse(response.headers().firstValue("Content-Type").orElseThrow());
        return type.type() + "/" + type.subtype();
    }
}
