package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonSchemaHeader;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code TSON-Schema} header over real HTTP: routing by it, agreeing with the body's directive, and the
 * case it exists for -- a JSON body, which cannot carry a {@code !!schema} at all, validated against a TSON
 * schema the header names.
 */
class TsonSchemaHeaderRoutingTest {

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
        TsonHttpCodec boundary = versions.codecFor(V1_ID);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", TsonHandler.asHttpHandler(boundary, exchange -> {
            exchange.requireMethod("POST");
            var routed = versions.route(exchange.exchange().getRequestBody(),
                    exchange.header(TsonSchemaHeader.NAME));
            String reply = switch (routed.schemaId()) {
                case V1_ID -> {
                    OrderV1 order = routed.codec().readObject(routed.body(),
                            exchange.header("Content-Type"), OrderV1.class);
                    yield "v1:" + order.sku() + ":" + order.quantity();
                }
                case V2_ID -> {
                    OrderV2 order = routed.codec().readObject(routed.body(),
                            exchange.header("Content-Type"), OrderV2.class);
                    yield "v2:" + order.sku() + ":" + order.quantity() + ":" + order.currency();
                }
                default -> throw new IllegalStateException("unserved " + routed.schemaId());
            };
            // The response says what governs it in both channels, which is what permitting both is for.
            exchange.setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(routed.schemaId()));
            exchange.respondBytes(200, reply.getBytes(StandardCharsets.UTF_8));
        }));

        // A JSON-reading route: the header is the only channel a JSON body has, and the root type comes from
        // the route rather than the document, since JSON carries no type-ref either.
        TsonHttpCodec json = versions.codecFor(V2_ID).acceptingJson();
        server.createContext("/orders-json", TsonHandler.asHttpHandler(boundary, exchange -> {
            exchange.requireMethod("POST");
            var governing = TsonSchemaHeader.resolve(exchange.exchange().getRequestBody(),
                    exchange.header(TsonSchemaHeader.NAME));
            String schemaId = governing.schema().orElseThrow(() -> new IllegalStateException("no schema"));
            OrderV2 order = json.readObjectAs(governing.body(), exchange.header("Content-Type"), schemaId,
                    "order", OrderV2.class);
            exchange.respondBytes(200,
                    ("json:" + order.sku() + ":" + order.quantity() + ":" + order.currency())
                            .getBytes(StandardCharsets.UTF_8));
        }));

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private HttpResponse<String> post(String path, String contentType, String body, String schemaHeader)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (schemaHeader != null) {
            request.header(TsonSchemaHeader.NAME, schemaHeader);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Routing on the header alone -- what a gateway would do, and the body says nothing. */
    @Test
    void routesOnTheHeaderWhenTheBodyNamesNothing() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!order { sku: \"A\" quantity: 3 }", TsonSchemaHeader.format(V1_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v1:A:3", response.body());
    }

    /** Both channels, agreeing: routable by the header and self-describing in the body. */
    @Test
    void acceptsBothWhenTheyAgree() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!!schema:\"" + V2_ID + "\"\n!order { sku: \"B\" quantity: 4 currency: \"AUD\" }",
                TsonSchemaHeader.format(V2_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v2:B:4:AUD", response.body());
    }

    @Test
    void refusesAHeaderAndBodyThatDisagree() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!!schema:\"" + V1_ID + "\"\n!order { sku: \"A\" quantity: 3 }",
                TsonSchemaHeader.format(V2_ID));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Conflicting schema"), response.body());
    }

    /** A pin is verification metadata, not identity (§2.2.1), so this is agreement. */
    @Test
    void aPinnedHeaderAgreesWithAPlainDirective() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!!schema:\"" + V1_ID + "\"\n!order { sku: \"A\" quantity: 3 }",
                TsonSchemaHeader.format(V1_ID + "?sha256=abc123"));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v1:A:3", response.body());
    }

    @Test
    void refusesAnUnquotedHeader() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!order { sku: \"A\" quantity: 3 }", V1_ID);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("quoted"), response.body());
    }

    @Test
    void aResponseNamesWhatGovernsItInTheHeaderToo() throws Exception {
        HttpResponse<String> response = post("/orders", "application/tson",
                "!order { sku: \"A\" quantity: 3 }", TsonSchemaHeader.format(V1_ID));
        assertEquals(TsonSchemaHeader.format(V1_ID),
                response.headers().firstValue(TsonSchemaHeader.NAME).orElseThrow());
    }

    /**
     * <b>The case the header exists for.</b> §6 makes every valid JSON document a valid TSON document, but
     * {@code !!schema} is directive syntax and not JSON -- so before this, a JSON payload had no way to say
     * which schema governed it, and this project could only ever reject one with a 415.
     */
    @Test
    void aJsonBodyIsValidatedAgainstTheSchemaTheHeaderNames() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/json",
                "{\"sku\": \"ABC-1\", \"quantity\": 3, \"currency\": \"AUD\"}",
                TsonSchemaHeader.format(V2_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("json:ABC-1:3:AUD", response.body());
    }

    /** And it is genuinely validated, not merely parsed. */
    @Test
    void anInvalidJsonBodyIsRejectedWithEveryDiagnostic() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/json",
                "{\"sku\": \"ABC-1\"}", TsonSchemaHeader.format(V2_ID));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("FIELD_REQUIRED"), response.body());
        assertTrue(response.body().contains("2 problems"), "quantity and currency: " + response.body());
    }

    /** JSON is admitted only where the endpoint says so; the TSON-only route still answers 415. */
    @Test
    void jsonIsOptInPerEndpoint() throws Exception {
        HttpResponse<String> response = post("/orders", "application/json", "{\"sku\": \"A\"}",
                TsonSchemaHeader.format(V1_ID));
        assertEquals(415, response.statusCode());
    }
}
