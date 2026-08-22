package io.ltr8.tson.http.helidon.demo;

import io.helidon.http.media.MediaContext;
import io.helidon.webserver.WebServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaCatalog;

import java.util.ArrayList;
import java.util.List;
import io.ltr8.tson.http.helidon.TsonHandler;
import io.ltr8.tson.http.helidon.TsonMediaSupport;
import io.ltr8.tson.http.helidon.TsonSchemaHandler;

/**
 * The same server as the JDK and Javalin demos, on Helidon 4 SE. {@code ./gradlew :tson-http-helidon:runDemo}
 *
 * <p><b>The order route is a plain Helidon handler.</b> Because {@link TsonMediaSupport} registers TSON with
 * Helidon's own entity machinery, {@code content().as(Order.class)} and {@code send(order)} do both halves --
 * there is no TSON-specific code in the handler at all, and it still validates. That is what this adapter has
 * and the other two cannot: their frameworks have no equivalent seam.
 */
public final class OrderServer {

    /** The schema this server governs orders by. Its identity names a host; where it is served is separate. */
    public static final String SCHEMA_ID = "https://schemas.example.com/2026/32/app/order-1.tn";

    public static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

    /** A bound order. Public because binding only ever touches public constructors and methods. */
    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private OrderServer() {
    }

    /**
     * Starts the server on {@code port} -- 0 for an ephemeral one -- and returns it.
     *
     * <p><b>Every schema is resolved here, on this thread, before a request can arrive.</b> Resolution mutates
     * the registry and is not concurrent-safe; reads against an already-compiled schema are. Resolving lazily
     * from a handler would be a race.
     */
    public static WebServer start(int port) {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        TsonHttpCodec codec = new TsonHttpCodec(tson);
        // Every type this server writes, resolved now rather than on a request thread. Descriptor resolution
        // is lazy and its check-then-act is not atomic, so a concurrent first write of a class races
        // (UPSTREAM.md #8). Reads happen to warm it here, but a route that only writes would not.
        codec.prepareToWrite(Order.class);

        return WebServer.builder()
                .port(port)
                .mediaContext(MediaContext.builder()
                        .addMediaSupport(TsonMediaSupport.create(codec))
                        .build())
                .routing(routing -> {
                    // Not optional alongside TsonMediaSupport: the read happens inside Helidon's entity
                    // machinery, before any handler code runs, so there is no handler boundary to catch a
                    // rejection. Without this, a body that breaks the schema loses its diagnostics to
                    // Helidon's own error page.
                    TsonHandler.install(routing, codec);

                    // Left as a plain Helidon handler, and so *not* self-describing, unlike the JDK and
                    // Javalin demos: the write goes through TsonMediaSupport's EntityWriter, which is handed a
                    // value and a stream and has nowhere to learn a schema URI from. Naming one would mean
                    // configuring the media support per type, which is its own design question. The honest
                    // demonstration is that the native seam costs this, and that a route wanting a
                    // self-describing reply writes through the codec directly, as the other two demos do.
                    routing.post("/orders", (request, response) -> {
                        Order order = request.content().as(Order.class);
                        response.status(201).send(new Order(order.sku(), order.quantity() * 2));
                    });

                    // Publishing at the identity path is what makes a !!schema URL dereferenceable. any(),
                    // because the paths served are the schemas' own, not routes Helidon knows about.
                    routing.any(TsonHandler.asHandler(codec,
                            TsonSchemaHandler.of(catalog())));
                })
                .build()
                .start();
    }

    /**
     * This server's whole published schema history: the order schema, and every version of the error-body
     * schema. §10 makes a published schema immutable, so a superseded one stays served -- a document that named
     * it must go on resolving, even though nothing new is written against it.
     */
    private static TsonSchemaCatalog catalog() {
        List<String> published = new ArrayList<>();
        published.add(SCHEMA);
        published.addAll(TsonProblemSchema.publishedSources());
        return TsonSchemaCatalog.of(published);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        WebServer server = start(port);
        Demo.announce("Helidon 4 SE", server.port());
    }
}
