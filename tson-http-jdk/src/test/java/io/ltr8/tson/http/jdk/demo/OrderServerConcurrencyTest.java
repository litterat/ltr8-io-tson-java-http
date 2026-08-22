package io.ltr8.tson.http.jdk.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real demo server under concurrent load -- the whole stack, not just the codec: this adapter's per-request
 * wrapper, its error boundary, and one shared {@code TsonHttpCodec} behind them all.
 *
 * <p>Each request carries a value only its own thread sent, so a crossed response is a wrong body rather than a
 * thrown exception. That is the failure worth looking for; one that threw would be the easy case.
 */
class OrderServerConcurrencyTest {

    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final int REQUESTS = 25;

    private com.sun.net.httpserver.HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    @Test
    @Timeout(180)
    void answersConcurrentRequestsCorrectly() throws Exception {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int thread = 0; thread < THREADS; thread++) {
                int id = thread;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < REQUESTS; i++) {
                            // Alternating valid and invalid, so the error path -- which renders a problem body
                            // through the shared codec -- is under contention too, not just the happy path.
                            if (i % 2 == 0) {
                                String sku = "SKU-" + id + "-" + i;
                                HttpResponse<String> ok = post("""
                                        !!schema:"%s"
                                        !order { sku: "%s"  quantity: %d }"""
                                        .formatted(OrderServer.SCHEMA_ID, sku, i));
                                assertEquals(201, ok.statusCode(), ok.body());
                                assertTrue(ok.body().contains(sku), "crossed response: " + ok.body());
                                assertTrue(ok.body().contains(String.valueOf(i * 2)), ok.body());
                            } else {
                                HttpResponse<String> bad = post("""
                                        !!schema:"%s"
                                        !order { }""".formatted(OrderServer.SCHEMA_ID));
                                assertEquals(400, bad.statusCode(), bad.body());
                                assertTrue(bad.body().contains("FIELD_REQUIRED"), bad.body());
                                assertTrue(bad.body().contains("2 problems"), bad.body());
                            }
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(150, TimeUnit.SECONDS), "requests did not finish -- a deadlock, most likely");
        }

        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " of " + THREADS + " threads failed; first: " + first,
                    first);
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
