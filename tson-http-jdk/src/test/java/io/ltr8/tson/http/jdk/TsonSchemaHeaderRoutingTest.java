package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
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

    private static final String V1_ID = "https://schemas.example.com/2026/36/app/order-1.tn";
    private static final String V2_ID = "https://schemas.example.com/2026/36/app/order-2.tn";

    private static final String V1 = """
            !!id:"https://schemas.example.com/2026/36/app/order-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            { order => { sku: text  quantity: int32 } }""";

    private static final String V2 = """
            !!id:"https://schemas.example.com/2026/36/app/order-2.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            { order => { sku: text  quantity: int32  currency: text } }""";

    private static final SchemaSource SOURCE = SchemaSource.ofMap(Map.of(V1_ID, V1, V2_ID, V2));

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
        // The same two versions, every codec admitting JSON: JSON is opt-in per endpoint.
        TsonSchemaVersions jsonVersions = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", OrderV1.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", OrderV2.class))
                .acceptingJson()
                .build();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", TsonHandler.asHttpHandler(versions.codecFor(V1_ID), routedBy(versions)));
        server.createContext("/orders-json",
                TsonHandler.asHttpHandler(jsonVersions.codecFor(V1_ID), routedBy(jsonVersions)));

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    /**
     * One handler for both routes: routed by whichever channel names the version, and read by {@code
     * readObjectAs}, which continues a TSON body's peek and reads a JSON one from its stream -- so the handler
     * writes one read per version, not one per version per encoding. JSON names no root type, which is why it is
     * given here.
     */
    private static TsonHandler routedBy(TsonSchemaVersions versions) {
        return exchange -> {
            exchange.requireMethod("POST");
            var routed = versions.route(exchange.exchange().getRequestBody(),
                    exchange.header(TsonSchemaHeader.NAME), exchange.header("Content-Type"));
            String reply = switch (routed.schemaId()) {
                case V1_ID -> {
                    OrderV1 order = routed.readObjectAs("order", OrderV1.class);
                    yield "v1:" + order.sku() + ":" + order.quantity();
                }
                case V2_ID -> {
                    OrderV2 order = routed.readObjectAs("order", OrderV2.class);
                    yield "v2:" + order.sku() + ":" + order.quantity() + ":" + order.currency();
                }
                default -> throw new IllegalStateException("unserved " + routed.schemaId());
            };
            // The response says what governs it in both channels, which is what permitting both is for.
            exchange.setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(routed.schemaId()));
            exchange.respondBytes(200, reply.getBytes(StandardCharsets.UTF_8));
        };
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
     * <b>The case the header exists for.</b> {@code !!schema} is directive syntax and not JSON, so the header is
     * the only channel a JSON payload has to say which schema governs it ([TSON-JSON] §3.4's out-of-band route,
     * which §3.5 makes this header's).
     */
    @Test
    void aJsonBodyIsValidatedAgainstTheSchemaTheHeaderNames() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/json",
                "{\"sku\": \"ABC-1\", \"quantity\": 3, \"currency\": \"AUD\"}",
                TsonSchemaHeader.format(V2_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v2:ABC-1:3:AUD", response.body());
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

    /**
     * <b>[TSON-JSON]'s own media type, read by the JSON reader.</b> {@code application/tson+json} is the encoding
     * the header was defined for (§3.5), and the body is read as JSON means it: {@code \/} is RFC 8259's escaped
     * solidus, which the TSON reader refused.
     */
    @Test
    void aTsonJsonBodyIsReadAsJson() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/tson+json",
                "{\"sku\": \"ABC\\/1\", \"quantity\": 3, \"currency\": \"AUD\"}",
                TsonSchemaHeader.format(V2_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v2:ABC/1:3:AUD", response.body());
    }

    /**
     * <b>A JSON body is routed by version too</b>, on the header alone and without a peek: the same endpoint reads
     * a v1 JSON order into the v1 class, which is the safety the routing exists for.
     */
    @Test
    void aJsonBodyIsRoutedToTheVersionItsHeaderNames() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/json",
                "{\"sku\": \"A\", \"quantity\": 3}", TsonSchemaHeader.format(V1_ID));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("v1:A:3", response.body());
    }

    /** A JSON body naming no version has nothing to be routed by -- it cannot carry a directive to fall back on. */
    @Test
    void aJsonBodyNamingNoVersionIsRefused() throws Exception {
        HttpResponse<String> response = post("/orders-json", "application/json",
                "{\"sku\": \"A\", \"quantity\": 3}", null);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("no-schema-declared"), response.body());
    }

    /** JSON is admitted only where the endpoint says so; the TSON-only route still answers 415. */
    @Test
    void jsonIsOptInPerEndpoint() throws Exception {
        HttpResponse<String> response = post("/orders", "application/json", "{\"sku\": \"A\"}",
                TsonSchemaHeader.format(V1_ID));
        assertEquals(415, response.statusCode());
    }
}
