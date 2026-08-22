package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaCatalog;
import io.ltr8.tson.http.TsonSchemaVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One endpoint serving two schema versions over real HTTP, routed by what each document declares -- the shape a
 * server needs once §10's immutability rule means a shape change is a new schema rather than an edit, and a
 * client written against the old one is still out there.
 */
class TsonVersionRoutingTest {

    private static final String V1_ID = "https://schemas.example.com/2026/32/app/order-1.tn";
    private static final String V2_ID = "https://schemas.example.com/2026/32/app/order-2.tn";

    private static final String V1 = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: text  quantity: int32 } }""";

    private static final String V2 = """
            !!id:"https://schemas.example.com/2026/32/app/order-2.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: text  quantity: int32  currency: text } }""";

    private static final TsonSchemaSource SOURCE = Map.of(V1_ID, V1, V2_ID, V2)::get;

    @Typename(name = "order")
    public record OrderV1(String sku, int quantity) {
    }

    @Typename(name = "order")
    public record OrderV2(String sku, int quantity, String currency) {
    }

    private HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        TsonSchemaVersions versions = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", OrderV1.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", OrderV2.class))
                .build();

        // Any codec will do for the boundary's own error rendering; the problem body is version-independent.
        TsonHttpCodec boundary = versions.codecFor(V1_ID);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", TsonHandler.asHttpHandler(boundary, exchange -> {
            exchange.requireMethod("POST");
            var routed = versions.route(exchange.exchange().getRequestBody());
            // Distinct classes per version, so the handler switches -- the alternative is one class with a
            // nullable field for anything not in every version, and no switch at all.
            String reply = switch (routed.schemaId()) {
                case V1_ID -> {
                    OrderV1 order = routed.codec()
                            .readObject(routed.body(), exchange.header("Content-Type"), OrderV1.class);
                    yield "v1:" + order.sku() + ":" + order.quantity() * 2;
                }
                case V2_ID -> {
                    OrderV2 order = routed.codec()
                            .readObject(routed.body(), exchange.header("Content-Type"), OrderV2.class);
                    yield "v2:" + order.sku() + ":" + order.quantity() * 2 + ":" + order.currency();
                }
                default -> throw new IllegalStateException("routed to an unserved version " + routed.schemaId());
            };
            exchange.respondBytes(200, reply.getBytes(StandardCharsets.UTF_8));
        }));

        // Both versions published at their own identity paths, so a client can fetch whichever governs it.
        server.createContext("/", TsonHandler.asHttpHandler(boundary,
                new TsonSchemaHandler(TsonSchemaCatalog.of(V1, V2, TsonProblemSchema.source()))));

        server.start();
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

    private static String order(String schemaId, String fields) {
        return "!!schema:\"" + schemaId + "\"\n!order " + fields;
    }

    @Test
    void oneEndpointServesBothVersions() throws Exception {
        HttpResponse<String> v1 = post(order(V1_ID, "{ sku: \"A\" quantity: 3 }"));
        assertEquals(200, v1.statusCode(), v1.body());
        assertEquals("v1:A:6", v1.body());

        HttpResponse<String> v2 = post(order(V2_ID, "{ sku: \"B\" quantity: 4 currency: \"AUD\" }"));
        assertEquals(200, v2.statusCode(), v2.body());
        assertEquals("v2:B:8:AUD", v2.body());
    }

    /** Each version is validated against its own schema, not the other's. */
    @Test
    void eachVersionKeepsItsOwnRules() throws Exception {
        // v2 requires currency.
        HttpResponse<String> missing = post(order(V2_ID, "{ sku: \"B\" quantity: 4 }"));
        assertEquals(400, missing.statusCode());
        assertTrue(missing.body().contains("FIELD_REQUIRED"), missing.body());

        // v1 has no currency to give.
        HttpResponse<String> extra = post(order(V1_ID, "{ sku: \"A\" quantity: 3 currency: \"AUD\" }"));
        assertEquals(400, extra.statusCode());
        assertTrue(extra.body().contains("UNRECOGNIZED_FIELD"), extra.body());
    }

    /** A version this server does not serve is refused, and told what it does serve. */
    @Test
    void anUnservedVersionIs400() throws Exception {
        HttpResponse<String> response =
                post(order("https://schemas.example.com/2026/32/app/order-3.tn", "{ sku: \"A\" quantity: 1 }"));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("order-3"), response.body());
        assertTrue(response.body().contains("order-1"), "and names what it does serve: " + response.body());
    }

    /**
     * The failure this routing prevents: without it, a v1 handler reading a v2 document would return
     * {@code v1:B:8} and the currency would be gone with nothing said. Here the currency reaches the reply.
     */
    @Test
    void aV2DocumentIsNeverQuietlyHandledAsV1() throws Exception {
        HttpResponse<String> response = post(order(V2_ID, "{ sku: \"B\" quantity: 4 currency: \"AUD\" }"));
        assertTrue(response.body().startsWith("v2:"), response.body());
        assertTrue(response.body().endsWith(":AUD"), "the currency survived: " + response.body());
        assertFalse(response.body().startsWith("v1:"), "must not have been read as v1");
    }

    @Test
    void aDocumentNamingNoVersionIs400() throws Exception {
        HttpResponse<String> response = post("!order { sku: \"A\" quantity: 1 }");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("!!schema"), response.body());
    }

    /** Both schemas are published, so a client can fetch whichever version governs it. */
    @Test
    void bothVersionsArePublished() throws Exception {
        for (String path : new String[] {"/2026/32/app/order-1.tn", "/2026/32/app/order-2.tn"}) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().contains("order =>"), path);
        }
    }
}
