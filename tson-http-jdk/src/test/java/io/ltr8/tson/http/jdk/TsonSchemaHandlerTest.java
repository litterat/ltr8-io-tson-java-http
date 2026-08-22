package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.tree.TsonValue;
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
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaHandlerTest {

    private static final String HOST = "schemas.example.com";
    private static final String SCHEMA_ID = "https://schemas.example.com/2026/32/app/order-1.tn";
    private static final String SCHEMA_PATH = "/2026/32/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    private HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        TsonHttpCodec codec = new TsonHttpCodec(Tson.builder().build());
        server.createContext("/", TsonHandler.asHttpHandler(codec,
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source())));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** The path is the identity's path, taken from the document -- never chosen by whoever registered it. */
    @Test
    void servesEachSchemaAtItsOwnIdentityPath() throws Exception {
        assertEquals(Set.of(SCHEMA_PATH, "/2026/32/ltr8/http/problem-1.tn"),
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source()).paths());

        HttpResponse<String> response = get(SCHEMA_PATH);
        assertEquals(200, response.statusCode());
        assertEquals("application/tson", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(SCHEMA, response.body());
    }

    /** [TSON-SCHEMA] §10: a published schema's content never changes, so this is the format's rule, not a guess. */
    @Test
    void saysImmutableBecauseTheFormatSaysSo() throws Exception {
        assertTrue(get(SCHEMA_PATH).headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
    }

    /** §2.2.1: a ?sha256= pin is verification metadata, not identity -- both references name one document. */
    @Test
    void ignoresAContentHashPinInTheQuery() throws Exception {
        assertEquals(get(SCHEMA_PATH).body(), get(SCHEMA_PATH + "?sha256=abc123").body());
    }

    /** RFC 9110 §15.4: a HEAD carries the headers its GET would, and no body. */
    @Test
    void headDescribesTheDocumentWithoutSendingIt() throws Exception {
        HttpResponse<String> head = client.send(
                HttpRequest.newBuilder(URI.create(base + SCHEMA_PATH))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, head.statusCode());
        assertEquals("application/tson", head.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("", head.body());
        // The length the GET would have sent, which is the whole use of a HEAD here -- a fetching client
        // size-caps its read. Pinned because it depends on the JDK server honouring a manually set
        // Content-Length alongside a -1 "no body" send, which is not obvious and is easy to regress.
        assertEquals(String.valueOf(SCHEMA.getBytes(StandardCharsets.UTF_8).length),
                head.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        assertEquals(404, get("/2026/32/app/nope-1.tn").statusCode());
    }

    @Test
    void aWriteMethodIs405() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + SCHEMA_PATH))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void refusesASchemaWithNoIdentityToServeItAt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TsonSchemaHandler.of("""
                        !!meta:"https://tson.io/2026/32/m/meta.tn"
                        { thing => { a: text } }"""));
        assertTrue(refused.getMessage().contains("!!id"), refused.getMessage());
    }

    @Test
    void refusesTwoSchemasClaimingOnePath() {
        assertThrows(IllegalArgumentException.class, () -> TsonSchemaHandler.of(SCHEMA, SCHEMA));
    }

    /**
     * The whole loop: this server serves a schema at its identity path, TsonHttpSchemaSource fetches it by
     * that identity, and a document naming it resolves and validates. Serving and fetching are the two halves
     * of the same contract, and only running them against each other shows they agree.
     */
    @Test
    void aServedSchemaIsOneAFetchingClientCanUse() {
        try (TsonHttpSchemaSource source = TsonHttpSchemaSource.builder()
                .mapHost(HOST, base)
                .timeout(Duration.ofSeconds(2))
                .build()) {

            Tson tson = Tson.builder().schemaSource(source).build();
            tson.resolve(source.fetch(SCHEMA_ID));
            TsonHttpCodec codec = new TsonHttpCodec(tson);

            TsonValue order = codec.readTree(new ByteArrayInputStream("""
                    !!schema:"%s"
                    !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_ID)
                    .getBytes(StandardCharsets.UTF_8)), "application/tson");

            assertEquals("ABC-1", order.get("sku").asString().orElseThrow());
            assertEquals(3, order.at("/quantity").asInt().orElseThrow());
        }
    }
}
