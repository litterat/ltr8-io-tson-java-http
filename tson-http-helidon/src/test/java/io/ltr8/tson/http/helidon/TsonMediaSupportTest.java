package io.ltr8.tson.http.helidon;

import io.helidon.http.media.MediaContext;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Helidon adapter's distinctive half: with TSON registered as a media type, an ordinary Helidon handler
 * reads and writes it through {@code req.content().as(...)} and {@code res.send(...)} with no TSON-specific
 * code -- and still gets the same validation and the same failures.
 */
class TsonMediaSupportTest {

    private static final String SCHEMA_ID = "https://schemas.example.com/2026/32/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
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

    @BeforeEach
    void setUp() {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        client.close();
    }

    /** TSON registered with Helidon's entity machinery, and install so a rejection is still a TSON problem. */
    private void start(Consumer<HttpRouting.Builder> routes) {
        server = WebServer.builder()
                .host("127.0.0.1")
                .port(0)
                .mediaContext(MediaContext.builder()
                        .addMediaSupport(TsonMediaSupport.create(codec))
                        .build())
                .routing(routing -> {
                    TsonHandler.install(routing, codec);
                    routes.accept(routing);
                })
                .build()
                .start();
        base = "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private TsonProblem problemFrom(HttpResponse<String> response) {
        TsonHttpCodec problems = new TsonHttpCodec(TsonProblemSchema.tson());
        return problems.readObjectAs(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
    }

    /** No TSON-specific code in the handler at all -- content().as() and send() do both halves. */
    @Test
    void aPlainHelidonHandlerReadsAndWritesTson() throws Exception {
        start(routing -> routing.post("/orders", (request, response) -> {
            Order order = request.content().as(Order.class);
            response.status(201).send(new Order(order.sku(), order.quantity() * 2));
        }));

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID));

        assertEquals(201, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/tson"));
        assertTrue(response.body().contains("ABC-1"), response.body());
        assertTrue(response.body().contains("6"), response.body());
    }

    /**
     * The read happens inside Helidon's entity machinery, before any handler code runs, so there is no handler
     * boundary to catch the rejection -- which is exactly why install is not optional here. With it, a body
     * that breaks its schema still becomes a 400 carrying every diagnostic.
     */
    @Test
    void aBodyThatBreaksItsSchemaIsStillA400WithEveryDiagnostic() throws Exception {
        start(routing -> routing.post("/orders", (request, response) ->
                response.status(201).send(request.content().as(Order.class))));

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { }""".formatted(SCHEMA_ID));

        assertEquals(400, response.statusCode());
        TsonProblem problem = problemFrom(response);
        assertEquals(2, problem.errors().size(), "both missing fields");
        assertTrue(problem.errors().stream().allMatch(e -> e.code() == Diagnostic.Code.FIELD_REQUIRED));
    }

    /** What the media support writes must be what the codec writes -- otherwise which path ran is observable. */
    @Test
    void whatItWritesMatchesTheCodecExactly() throws Exception {
        start(routing -> routing.post("/orders", (request, response) ->
                response.send(request.content().as(Order.class))));

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID));

        assertEquals(new String(codec.write(new Order("ABC-1", 3)), StandardCharsets.UTF_8), response.body());
    }
}
