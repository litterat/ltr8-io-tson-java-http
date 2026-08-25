package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Drives the demo server under load so an allocation profiler has something to look at.
 *
 * <p>Not a test and not a benchmark -- it answers one question: what does a request allocate, and what of
 * that survives a collection. The second half is the one worth asking of a server, and the answer so far is
 * "nothing": the live heap moves single-digit bytes per request and every surviving sample traces to startup.
 *
 * <p>Run it under JFR, from the demo runtime classpath
 * ({@code ./gradlew :tson-http-jdk:printDemoClasspath -q}):
 *
 * <pre>{@code
 * java -XX:StartFlightRecording=filename=alloc.jfr,settings=profile,\
 *          +jdk.ObjectAllocationSample#enabled=true,\
 *          +jdk.OldObjectSample#enabled=true,+jdk.OldObjectSample#cutoff=0ms \
 *      -XX:FlightRecorderOptions=old-object-queue-size=512 \
 *      -cp "$CP" io.ltr8.tson.http.jdk.demo.AllocationHarness 15000
 *
 * jfr print --events jdk.ObjectAllocationSample --stack-depth 22 alloc.jfr   # what is allocated, and by whom
 * jfr print --events jdk.OldObjectSample        --stack-depth 14 alloc.jfr   # what survived a collection
 * }</pre>
 *
 * <p><b>Client and server share this JVM</b>, so a share-of-total figure from the recording includes
 * {@code HttpClient}'s own allocation. Attribute by stack, not by percentage -- which is how this found a
 * regex being used as a character comparison in the writer's quoting path, since fixed upstream.
 */
public final class AllocationHarness {

    private AllocationHarness() {
    }

    private static long allocatedBytes() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.getCurrentThreadAllocatedBytes();
    }

    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 20_000;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        HttpServer server = OrderServer.start(0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();

        String valid = """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(OrderServer.SCHEMA_ID);
        String invalid = """
                !!schema:"%s"
                !order { }""".formatted(OrderServer.SCHEMA_ID);

        // Warm up: first-write descriptor resolution and JIT, neither of which is per-request cost.
        drive(client, base, valid, invalid, 2_000);

        long before = allocatedBytes();
        long heapBefore = liveHeap();
        long started = System.nanoTime();
        if (threads == 1) {
            drive(client, base, valid, invalid, requests);
        } else {
            // Concurrent, which is the only way a lock on the read path shows itself: every request thread
            // shares one codec, one Tson and one compiled-schema registry, which is the shape a server has.
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            drive(client, base, valid, invalid, requests / threads);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        long clientSide = allocatedBytes() - before;

        System.out.printf("threads=%d requests=%d in %d ms (%.0f req/s)%n",
                threads, requests * 2, elapsedMs, requests * 2 * 1000.0 / Math.max(1, elapsedMs));
        System.out.printf("client-thread bytes/request=%d  (the harness's own cost, not the server's)%n",
                clientSide / (requests * 2));
        System.out.printf("live heap before=%d after=%d delta=%d bytes%n",
                heapBefore, liveHeap(), liveHeap() - heapBefore);
        System.out.printf("live heap delta per request=%.1f bytes%n",
                (liveHeap() - heapBefore) / (double) (requests * 2));
        server.stop(0);
        client.close();
    }

    /** Live heap after a collection -- what survived, which is the question worth asking of a server. */
    private static long liveHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void drive(HttpClient client, String base, String valid, String invalid, int n)
            throws Exception {
        for (int i = 0; i < n; i++) {
            post(client, base, valid);
            post(client, base, invalid);
        }
    }

    private static void post(HttpClient client, String base, String body) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 201 && response.statusCode() != 400) {
            throw new IllegalStateException("unexpected " + response.statusCode() + ": " + response.body());
        }
    }
}
