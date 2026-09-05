package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblem;
import io.ltr8.tson.http.TsonProblemDiagnostic;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Driven end to end over real HTTP -- the point of an adapter is what a framework does with it. */
class TsonJdkAdapterTest {

    private static final String SCHEMA_ID = "https://schemas.example.com/2026/35/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/35/app/order-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private HttpServer server;
    private HttpClient client;
    private String base;
    private TsonHttpCodec codec;

    @BeforeEach
    void startServer() throws IOException {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private void route(String path, TsonHandler handler) {
        server.createContext(path, TsonHandler.asHttpHandler(codec, handler));
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
        route("/orders", exchange -> {
            exchange.requireMethod("POST");
            Order order = exchange.readObject(Order.class);
            exchange.respond(201, new Order(order.sku(), order.quantity() * 2));
        });

        HttpResponse<String> response = post("/orders", """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID));

        assertEquals(201, response.statusCode());
        assertEquals("application/tson", response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.body().contains("ABC-1"), response.body());
        assertTrue(response.body().contains("6"), response.body());
    }

    /**
     * A handler that only reads gets correct validation behaviour without writing any -- and the client gets
     * every problem in one response, which is what makes a generate-validate-retry loop terminate.
     */
    @Test
    void anInvalidBodyBecomesA400CarryingEveryDiagnostic() throws Exception {
        route("/orders", exchange -> exchange.respond(201, exchange.readObject(Order.class)));

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
        route("/orders", exchange -> {
            exchange.requireMethod("POST");
            exchange.respondEmpty(204);
        });

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/orders")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void aNonTsonBodyIs415() throws Exception {
        route("/orders", exchange -> exchange.respond(201, exchange.readObject(Order.class)));
        assertEquals(415, post("/orders", "{}", "Content-Type", "application/json").statusCode());
    }

    /** Checked in the boundary, so it cannot be forgotten -- and before the handler does the work. */
    @Test
    void anUnacceptableAcceptIs406AndTheHandlerNeverRuns() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        route("/orders", exchange -> {
            ran.set(true);
            exchange.respondEmpty(204);
        });

        HttpResponse<String> response = post("/orders", "{ a: 1 }",
                "Content-Type", "application/tson", "Accept", "application/json");

        assertEquals(406, response.statusCode());
        assertFalse(ran.get(), "the boundary must refuse before the handler does the work");
    }

    /** A gap is 501, never 400 -- a client told to fix a document that is not wrong cannot ever succeed. */
    @Test
    void aLibraryGapIs501NotABadRequest() throws Exception {
        route("/gap", exchange -> {
            throw new UnsupportedOperationException("not implemented yet");
        });
        assertEquals(501, post("/gap", "{ a: 1 }").statusCode());
    }

    /** An internal message can name a class, a path or an internal host, and a client is not the audience. */
    @Test
    void aServerErrorSaysNothingAboutWhy() throws Exception {
        route("/boom", exchange -> {
            throw new IllegalArgumentException("connection to db-primary.internal:5432 refused");
        });

        HttpResponse<String> response = post("/boom", "{ a: 1 }");

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("db-primary.internal"), response.body());
        TsonProblem problem = problemFrom(response);
        assertEquals(Optional.empty(), problem.detail(), "a 5xx carries no detail");
        assertEquals(java.util.List.of(), problem.errors());
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
        route("/mixed", exchange -> {
            throw TsonHttpException.invalidDocument(java.util.List.of(
                    Diagnostic.ofSchemaGap(SCHEMA_ID, "order", "generic templates are not implemented yet",
                            Optional.empty()),
                    Diagnostic.ofSchemaError(SCHEMA_ID, "order", "'quantity' is required", Optional.empty()),
                    Diagnostic.ofSchemaUnavailable(SCHEMA_ID, "order", new TsonSchemaFetchException(
                            "https://mirror.internal/x.tn", TsonSchemaFetchException.Reason.TRANSPORT,
                            "connect to mirror.internal failed", null), Optional.empty())));
        });

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
        route("/origin", exchange -> {
            throw TsonHttpException.invalidDocument(java.util.List.of(
                    Diagnostic.ofSchemaUnavailable(SCHEMA_ID, "order", new TsonSchemaFetchException(
                            "https://mirror.internal/x.tn", TsonSchemaFetchException.Reason.TRANSPORT,
                            "connect to mirror.internal failed", null), Optional.empty())));
        });

        HttpResponse<String> response = post("/origin", "{ a: 1 }");

        assertEquals(502, response.statusCode());
        assertFalse(response.body().contains("mirror.internal"), response.body());
        TsonProblem problem = problemFrom(response);
        assertEquals(Optional.empty(), problem.detail());
        assertEquals(java.util.List.of(), problem.errors());
    }

    /** A 4xx is the opposite: its detail is the entire point, since the client is who can act on it. */
    @Test
    void aClientErrorKeepsItsDetail() throws Exception {
        route("/orders", exchange -> {
            throw new TsonHttpException(409, "Conflict", "order ABC-1 already exists", java.util.List.of(), null);
        });

        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(409, response.statusCode());
        assertEquals("order ABC-1 already exists", problemFrom(response).detail().orElseThrow());
    }

    @Test
    void aHandlerThatAnswersNothingIsAServerError() throws Exception {
        route("/silent", exchange -> { });
        assertEquals(500, post("/silent", "{ a: 1 }").statusCode());
    }

    /**
     * Streamed, so the length is not known when the headers go out and this server chunks it.
     *
     * <p>Unlike Jetty, which holds a short response in its buffer and sends a {@code Content-Length} after
     * all: {@code com.sun.net.httpserver} chunks whatever it is given a length of 0 for, whatever the size.
     * Both are correct HTTP, and a client must depend on neither -- see the Javalin adapter's own version of
     * this test.
     */
    @Test
    void aStreamedResponseCarriesNoContentLength() throws Exception {
        route("/orders", exchange -> exchange.respond(200, new Order("ABC-1", 3)));
        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(Optional.empty(), response.headers().firstValue("Content-Length"));
    }

    /** A body already in hand gets a real Content-Length -- which is why both paths exist. */
    @Test
    void aBufferedResponseCarriesItsLength() throws Exception {
        route("/orders", exchange -> exchange.respondBytes(200, exchange.codec().write(new Order("ABC-1", 3))));
        HttpResponse<String> response = post("/orders", "{ a: 1 }");
        assertEquals(String.valueOf(response.body().length()),
                response.headers().firstValue("Content-Length").orElseThrow());
    }
}
