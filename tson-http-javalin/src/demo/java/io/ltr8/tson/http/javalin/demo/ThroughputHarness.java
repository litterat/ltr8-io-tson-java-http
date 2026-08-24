package io.ltr8.tson.http.javalin.demo;

import io.javalin.Javalin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The same load the JDK adapter's {@code AllocationHarness} drives, against Jetty instead.
 *
 * <p><b>Why it exists.</b> Throughput against the JDK demo plateaus around 3x with the machine at a fifth of
 * its capacity, and {@code com.sun.net.httpserver} has a single {@code HTTP-Dispatcher} thread doing all
 * selector work. That is a plausible ceiling but a guess until the same load runs against a server built
 * differently. If Jetty scales past it, the ceiling is the JDK's server rather than anything in TSON.
 */
public final class ThroughputHarness {

    private ThroughputHarness() {
    }

    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 24_000;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        boolean clientPerThread = args.length > 2 && Boolean.parseBoolean(args[2]);

        Javalin app = OrderServer.start(0);
        String base = "http://127.0.0.1:" + app.port();
        String valid = """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(OrderServer.SCHEMA_ID);

        // Built and warmed BEFORE timing: a fresh client pays for connection setup and an unwarmed JIT, and
        // charging that to the server is how a load generator flatters or slanders itself.
        HttpClient[] clients = new HttpClient[clientPerThread ? threads : 1];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = HttpClient.newHttpClient();
            drive(clients[i], base, valid, 2_000);
        }

        var os = (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        long started = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                HttpClient mine = clients[clientPerThread ? t : 0];
                pool.submit(() -> {
                    try {
                        drive(mine, base, valid, requests / threads);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        long cpuMs = (os.getProcessCpuTime() - cpuBefore) / 1_000_000;
        System.out.printf("javalin threads=%-3d client=%-6s %6.0f req/s   cpu=%.1f cores of %d%n",
                threads, clientPerThread ? "each" : "shared", requests * 1000.0 / Math.max(1, ms),
                cpuMs / (double) Math.max(1, ms), Runtime.getRuntime().availableProcessors());
        app.stop();
        for (HttpClient c : clients) {
            c.close();
        }
    }

    private static void drive(HttpClient client, String base, String body, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                    .header("Content-Type", "application/tson")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 201) {
                throw new IllegalStateException("unexpected " + r.statusCode() + ": " + r.body());
            }
        }
    }
}
