package io.ltr8.tson.http.javalin;

import io.javalin.Javalin;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately the same tests as {@code tson-http-jdk}'s {@code TsonJdkAdapterTest}, asserting the same
 * behaviour: two adapters over one codec should be indistinguishable from a client's side, and the only way to
 * show that is to ask them the same questions.
 */
class TsonJavalinAdapterTest {

    private static final String SCHEMA_ID = "https://schemas.example.com/2026/34/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/34/app/order-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private Javalin app;
    private HttpClient client;
    private String base;
    private TsonHttpCodec codec;

    @BeforeEach
    void startServer() {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);

        app = Javalin.create(config -> config.showJavalinBanner = false).start(0);
        base = "http://127.0.0.1:" + app.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        app.stop();
        client.close();
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
        app.post("/orders", TsonHandler.asHandler(codec, tson -> {
            Order order = tson.readObject(Order.class);
            tson.respond(201, new Order(order.sku(), order.quantity() * 2));
        }));

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
        app.post("/orders", TsonHandler.asHandler(codec, tson -> tson.respond(201, tson.readObject(Order.class))));

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
        app.post("/orders", TsonHandler.asHandler(codec, onlyPost));
        app.get("/orders", TsonHandler.asHandler(codec, onlyPost));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/orders")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void aNonTsonBodyIs415() throws Exception {
        app.post("/orders", TsonHandler.asHandler(codec, tson -> tson.respond(201, tson.readObject(Order.class))));
        assertEquals(415, post("/orders", "{}", "Content-Type", "application/json").statusCode());
    }

    /** Checked in the boundary, so it cannot be forgotten -- and before the handler does the work. */
    @Test
    void anUnacceptableAcceptIs406AndTheHandlerNeverRuns() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        app.post("/orders", TsonHandler.asHandler(codec, tson -> {
            ran.set(true);
            tson.respondEmpty(204);
        }));

        HttpResponse<String> response = post("/orders", "{ a: 1 }",
                "Content-Type", "application/tson", "Accept", "application/json");

        assertEquals(406, response.statusCode());
        assertFalse(ran.get(), "the boundary must refuse before the handler does the work");
    }

    /** A gap is 501, never 400 -- a client told to fix a document that is not wrong cannot ever succeed. */
    @Test
    void aLibraryGapIs501NotABadRequest() throws Exception {
        app.post("/gap", TsonHandler.asHandler(codec, tson -> {
            throw new UnsupportedOperationException("not implemented yet");
        }));
        assertEquals(501, post("/gap", "{ a: 1 }").statusCode());
    }

    /** An internal message can name a class, a path or an internal host, and a client is not the audience. */
    @Test
    void aServerErrorSaysNothingAboutWhy() throws Exception {
        app.post("/boom", TsonHandler.asHandler(codec, tson -> {
            throw new IllegalArgumentException("connection to db-primary.internal:5432 refused");
        }));

        HttpResponse<String> response = post("/boom", "{ a: 1 }");

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("db-primary.internal"), response.body());
        assertEquals(Optional.empty(), problemFrom(response).detail(), "a 5xx carries no detail");
    }

    @Test
    void aClientErrorKeepsItsDetail() throws Exception {
        app.post("/orders", TsonHandler.asHandler(codec, tson -> {
            throw new TsonHttpException(409, "Conflict", "order ABC-1 already exists", List.of(), null);
        }));

        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(409, response.statusCode());
        assertEquals("order ABC-1 already exists", problemFrom(response).detail().orElseThrow());
    }

    @Test
    void aHandlerThatAnswersNothingIsAServerError() throws Exception {
        app.post("/silent", TsonHandler.asHandler(codec, tson -> { }));
        assertEquals(500, post("/silent", "{ a: 1 }").statusCode());
    }

    /**
     * install is for the routes not written as a TsonHandler: one application should answer failures one way,
     * whether the failure comes from a TSON route or from a service layer inside a plain Javalin one.
     */
    @Test
    void installMakesAPlainJavalinRouteFailTheSameWay() throws Exception {
        TsonHandler.install(app, codec);
        app.post("/plain", context -> {
            throw new TsonHttpException(409, "Conflict", "order ABC-1 already exists", List.of(), null);
        });

        HttpResponse<String> response = post("/plain", "{ a: 1 }");
        assertEquals(409, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/tson"));
        assertEquals("order ABC-1 already exists", problemFrom(response).detail().orElseThrow());
    }

    /**
     * A response too large for Jetty's output buffer must go out chunked -- which is the observable proof that
     * the document was written into the stream rather than materialised first.
     *
     * <p><b>A small one is not proof of anything</b>, and this is where the two adapters visibly differ:
     * {@code com.sun.net.httpserver} chunks whatever it is given a length of 0 for, while Jetty holds a short
     * response in its buffer, discovers the length, and sends {@code Content-Length} after all. Both are
     * correct HTTP and a client must depend on neither, so the test uses a body big enough to settle it.
     */
    @Test
    void aLargeStreamedResponseIsChunked() throws Exception {
        String longSku = "A".repeat(64 * 1024);
        app.post("/orders", TsonHandler.asHandler(codec, tson -> tson.respond(200, new Order(longSku, 3))));

        HttpResponse<String> response = post("/orders", "{ a: 1 }");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(longSku), "the whole document must arrive");
        assertEquals(Optional.empty(), response.headers().firstValue("Content-Length"),
                "a response past the buffer cannot know its length when the headers go out");
    }

    /** A body already in hand gets a real Content-Length -- which is why both paths exist. */
    @Test
    void aBufferedResponseCarriesItsLength() throws Exception {
        app.post("/orders", TsonHandler.asHandler(codec,
                tson -> tson.respondBytes(200, tson.codec().write(new Order("ABC-1", 3)))));
        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(String.valueOf(response.body().length()),
                response.headers().firstValue("Content-Length").orElseThrow());
    }
}
