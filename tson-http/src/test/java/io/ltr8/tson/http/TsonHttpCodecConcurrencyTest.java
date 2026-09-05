package io.ltr8.tson.http;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One codec, many threads -- the shape every adapter in this project actually runs in, and the one every other
 * test in it avoids.
 *
 * <p><b>Why this exists.</b> `CLAUDE.md`, `TsonHttpCodec` and `TsonHttpSchemaSource` all state the same
 * invariant: resolve and compile every schema during single-threaded startup, then share the result for reads.
 * Until this test, that was an assertion. Three servers hold one codec across their request threads, so a codec
 * that is wrong under concurrency would pass all of the rest of the suite -- every other test here drives it
 * from one thread.
 *
 * <p>It is also this project's own measurement of the contract {@code Tson}'s Javadoc states -- concurrent reads
 * through one instance are safe -- taken from the consumer's side, where such a claim is worth checking rather
 * than trusting.
 *
 * <p><b>Correctness, not just absence of exceptions.</b> Each task checks its own result, because the failure
 * this is looking for is a torn read or a crossed binding -- a value that is wrong rather than a call that
 * threw. A race that only ever produced exceptions would be the easy case.
 */
class TsonHttpCodecConcurrencyTest {

    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final int ITERATIONS = 150;

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

    private TsonHttpCodec codec;

    /**
     * Resolved once, on this thread, before anything is shared -- the invariant under test.
     *
     * <p>{@code prepareToWrite} is the other half of it, but a warm-up rather than a correctness measure:
     * descriptor resolution once raced on a concurrent first write and the loser got {@code Class already
     * registered}, which this line was what stopped. It now settles by keeping the winner's entry, so deleting
     * the line costs latency and nothing else. Kept because moving that work off the request thread is still
     * what a server wants.
     */
    @BeforeEach
    void setUp() {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        codec = new TsonHttpCodec(tson);
        codec.prepareToWrite(Order.class);
    }

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    private static String order(String sku, int quantity) {
        return """
                !!schema:"%s"
                !order { sku: "%s"  quantity: %d }""".formatted(SCHEMA_ID, sku, quantity);
    }

    /**
     * Runs {@code task} on every thread at once, {@code ITERATIONS} times each, and fails with the first
     * problem any of them saw. A start latch means they contend rather than politely queue.
     */
    private void hammer(ThreadTask task) throws Exception {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int thread = 0; thread < THREADS; thread++) {
                int id = thread;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ITERATIONS; i++) {
                            task.run(id, i);
                            completed.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish -- a deadlock, most likely");
        }

        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " of " + THREADS + " threads failed; first: " + first,
                    first);
        }
        assertEquals(THREADS * ITERATIONS, completed.get());
    }

    /** Each thread reads a document only it writes, so a crossed result is a wrong value, not just a throw. */
    @Test
    @Timeout(120)
    void readsIntoATreeConcurrently() throws Exception {
        hammer((thread, i) -> {
            String sku = "SKU-" + thread + "-" + i;
            TsonValue order = codec.readTree(body(order(sku, thread * 1000 + i)), "application/tson");
            assertEquals(sku, order.get("sku").asString().orElseThrow());
            assertEquals(thread * 1000 + i, order.at("/quantity").asInt().orElseThrow());
        });
    }

    /** The binding path, which caches class descriptors -- the likeliest place for a shared-state fault. */
    @Test
    @Timeout(120)
    void bindsToObjectsConcurrently() throws Exception {
        hammer((thread, i) -> {
            Order expected = new Order("SKU-" + thread + "-" + i, thread * 1000 + i);
            Order read = codec.readObject(body(order(expected.sku(), expected.quantity())), "application/tson",
                    Order.class);
            assertEquals(expected, read);
        });
    }

    /**
     * Writers hold a bind context and build a fresh emitter per call; this is the claim that they may be
     * shared -- given the descriptor for what is being written has been prepared. See {@link #setUp}.
     */
    @Test
    @Timeout(120)
    void writesConcurrently() throws Exception {
        hammer((thread, i) -> {
            Order value = new Order("SKU-" + thread + "-" + i, thread * 1000 + i);
            String written = new String(codec.write(value), StandardCharsets.UTF_8);
            assertTrue(written.contains(value.sku()), written);
            assertTrue(written.contains(String.valueOf(value.quantity())), written);
        });
    }

    /** A rejected read must report its own document's problems, not another thread's. */
    @Test
    @Timeout(120)
    void collectsDiagnosticsPerThreadNotGlobally() throws Exception {
        hammer((thread, i) -> {
            String invalid = """
                    !!schema:"%s"
                    !order { }""".formatted(SCHEMA_ID);
            try {
                codec.readTree(body(invalid), "application/tson");
                throw new AssertionError("expected a rejection");
            } catch (TsonHttpException rejected) {
                assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
                assertEquals(2, rejected.diagnostics().size(),
                        "exactly this document's two problems -- a shared collector would accumulate");
                assertTrue(rejected.diagnostics().stream()
                        .allMatch(d -> d.code() == Diagnostic.Code.FIELD_REQUIRED));
                assertEquals(List.of("/sku", "/quantity"),
                        rejected.diagnostics().stream().map(d -> d.path().orElseThrow()).toList());
            }
        });
    }

    /** Reads, writes and rejections interleaved -- the mix a real server produces. */
    @Test
    @Timeout(120)
    void survivesAMixedLoad() throws Exception {
        hammer((thread, i) -> {
            switch (i % 4) {
                case 0 -> {
                    Order value = new Order("SKU-" + thread, i);
                    assertEquals(value, codec.readObject(body(order(value.sku(), value.quantity())),
                            "application/tson", Order.class));
                }
                case 1 -> assertTrue(codec.write(new Order("SKU-" + thread, i)).length > 0);
                case 2 -> assertEquals("SKU-" + thread,
                        codec.readTree(body(order("SKU-" + thread, i)), "application/tson")
                                .get("sku").asString().orElseThrow());
                default -> {
                    TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "invalid-document", 400, "Invalid TSON document", "thread " + thread,
                            List.of());
                    assertTrue(codec.writeProblem(problem).length > 0);
                }
            }
        });
    }

    @FunctionalInterface
    private interface ThreadTask {
        void run(int thread, int iteration) throws Exception;
    }
}
