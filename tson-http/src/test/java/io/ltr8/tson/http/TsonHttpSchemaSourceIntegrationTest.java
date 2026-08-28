package io.ltr8.tson.http;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.TsonHttpSchemaSource;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaFetchException.Reason;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How this project uses {@link TsonHttpSchemaSource}, which is upstream's class rather than this module's.
 *
 * <p><b>The policy is not retested here.</b> The allow-list, the refusal to follow a redirect, the caps on size
 * and time, the identity rules and the cache all belong to the class, and upstream's own suite covers them --
 * duplicating it here would only give the security check a second place to drift lenient, which is the reason
 * the class stopped being this repo's in the first place. What is left is what upstream cannot know: how a
 * fetch failure becomes a status, and that the codec really does read a document whose schema arrived over the
 * wire.
 *
 * <p>Driven against a real HTTP server on an ephemeral port, because a stub would only assert about itself.
 */
class TsonHttpSchemaSourceIntegrationTest {

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    /** The identity host schemas are named by -- never where they are fetched from. §2.2.1 forbids a port here. */
    private static final String HOST = "schemas.example.com";

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * The whole point, end to end: a document naming a schema over HTTP resolves, validates, and reads --
     * with resolution done at startup, on this thread, exactly as CLAUDE.md requires.
     */
    @Test
    void aDocumentNamingAnHttpSchemaResolvesAndValidates() {
        String schemaUri = reference("/order-1.tn");
        serve("/order-1.tn", schemaAt("/order-1.tn"));
        try (TsonHttpSchemaSource source = allowingThisServer()) {
            Tson tson = Tson.builder().schemaSource(source).build();
            tson.resolve(source.fetch(schemaUri));
            TsonHttpCodec codec = new TsonHttpCodec(tson);

            TsonValue order = codec.readTree(body("""
                    !!schema:"%s"
                    !order { sku: "ABC-1"  quantity: 3 }""".formatted(schemaUri)), "application/tson");
            assertEquals("ABC-1", order.get("sku").asString().orElseThrow());

            TsonHttpException rejected = assertThrows(TsonHttpException.class, () -> codec.readTree(body("""
                    !!schema:"%s"
                    !order { }""".formatted(schemaUri)), "application/tson"));
            assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
            assertEquals(2, rejected.diagnostics().size());
        }
    }

    /**
     * <b>A fetch failure splits by whose fault it is.</b> A document naming a schema this server will not load,
     * or one that does not exist, is the document's problem: 400. A permitted origin that is unreachable,
     * oversized or slow is this server's dependency failing while the request was perfectly good, which is what
     * 502 and 504 are for -- and the retry advice differs, so collapsing them would either blame a client for
     * an outage or hide an outage as a client error.
     *
     * <p>Exhaustive over {@link Reason} on purpose: the mapping lives in a switch over that enum, so a member
     * added upstream must be given a status here rather than defaulting to one.
     */
    @Test
    void anUnfetchableSchemaReferenceBecomesTheRightStatus() {
        assertEquals(TsonHttpException.BAD_REQUEST, statusFor(Reason.NOT_PERMITTED));
        assertEquals(TsonHttpException.BAD_REQUEST, statusFor(Reason.NOT_FOUND));
        assertEquals(TsonHttpException.GATEWAY_TIMEOUT, statusFor(Reason.TIMEOUT));
        assertEquals(TsonHttpException.BAD_GATEWAY, statusFor(Reason.TRANSPORT));
        assertEquals(TsonHttpException.BAD_GATEWAY, statusFor(Reason.TOO_LARGE));
        assertEquals(Reason.values().length, 5, "a new Reason needs a status, not a default");
    }

    /**
     * <b>And the collecting path is the one that actually fires</b>, which is why the mapping above is not the
     * whole story. Every read through the codec collects, so an unfetchable {@code !!schema} does not arrive
     * as {@link TsonSchemaFetchException} at all -- it is reported as a {@code SCHEMA_UNAVAILABLE} diagnostic
     * and the {@code Reason} is gone by the time a status is chosen. Asserted end to end, against a source
     * that permits nothing, because the two channels disagreeing here is exactly what would go unnoticed:
     * {@code statusFor(NOT_PERMITTED)} says 400 and this says 502, for one underlying failure.
     */
    @Test
    void anUnfetchableSchemaInACollectingReadIsNotABadRequest() {
        try (TsonHttpSchemaSource denyAll = TsonHttpSchemaSource.builder().build()) {
            TsonHttpCodec codec = new TsonHttpCodec(Tson.builder().schemaSource(denyAll).build());

            TsonHttpException thrown = assertThrows(TsonHttpException.class, () -> codec.readTree(body("""
                    !!schema:"%s"
                    !order { sku: "ABC-1"  quantity: 3 }""".formatted(reference("/order-1.tn"))),
                    "application/tson"));

            assertEquals(TsonHttpException.BAD_GATEWAY, thrown.status());
            assertEquals(List.of(Diagnostic.Code.SCHEMA_UNAVAILABLE),
                    thrown.diagnostics().stream().map(Diagnostic::code).toList());
        }
    }

    private static int statusFor(Reason reason) {
        return TsonHttpException.from(
                new TsonSchemaFetchException("https://example.com/x.tn", reason, "test", null)).status();
    }

    /** Serves {@code body} at {@code path}. */
    private void serve(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/tson");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /** The reference a document would write: identity, not location. */
    private static String reference(String path) {
        return "https://" + HOST + path;
    }

    private static String schemaAt(String path) {
        return SCHEMA.formatted(reference(path));
    }

    /** Names are on {@link #HOST}; the bytes come from the test server. That split is the point of mapHost. */
    private TsonHttpSchemaSource allowingThisServer() {
        return TsonHttpSchemaSource.builder().mapHost(HOST, base).timeout(Duration.ofSeconds(2)).build();
    }

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
