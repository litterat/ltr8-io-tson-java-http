package io.ltr8.tson.http.javalin;

import io.javalin.Javalin;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.TsonHttpSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonJavalinSchemaHandlerTest {

    private static final String HOST = "schemas.example.com";
    private static final String SCHEMA_ID = "https://schemas.example.com/2026/35/app/order-1.tn";
    private static final String SCHEMA_PATH = "/2026/35/app/order-1.tn";

    private static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/35/app/order-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    private Javalin app;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() {
        TsonHttpCodec codec = new TsonHttpCodec(Tson.builder().build());
        app = Javalin.create(config -> config.showJavalinBanner = false).start(0);
        // `<path>` rather than `{path}`: an identity path has slashes in it, and only the angle form matches
        // across them.
        app.get("/<path>", TsonHandler.asHandler(codec,
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source())));
        base = "http://127.0.0.1:" + app.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        app.stop();
        client.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void servesEachSchemaAtItsOwnIdentityPath() throws Exception {
        assertEquals(Set.of(SCHEMA_PATH, "/2026/35/ltr8/http/problem-1.tn"),
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source()).paths());

        HttpResponse<String> response = get(SCHEMA_PATH);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/tson"));
        assertEquals(SCHEMA, response.body());
    }

    /** [TSON-SCHEMA] §3.5: a published schema's content never changes, so this is the format's rule, not a guess. */
    @Test
    void saysImmutableBecauseTheFormatSaysSo() throws Exception {
        assertTrue(get(SCHEMA_PATH).headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
    }

    /** §2.2.1: a ?sha256= pin is verification metadata, not identity -- both references name one document. */
    @Test
    void ignoresAContentHashPinInTheQuery() throws Exception {
        assertEquals(get(SCHEMA_PATH).body(), get(SCHEMA_PATH + "?sha256=abc123").body());
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        assertEquals(404, get("/2026/35/app/nope-1.tn").statusCode());
    }

    /**
     * The whole loop, through Javalin this time: this server publishes a schema at its identity path,
     * TsonHttpSchemaSource fetches it by that identity, and a document naming it resolves and validates.
     * The same test passes against the JDK adapter, which is the point -- the client cannot tell them apart.
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
