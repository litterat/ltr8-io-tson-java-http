package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.jdk.TsonHandler;
import io.ltr8.tson.http.jdk.TsonSchemaHandler;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * A runnable TSON server on the JDK's own {@code com.sun.net.httpserver}, with no external dependency of any
 * kind. {@code ./gradlew :tson-http-jdk:runDemo}
 *
 * <p>The two routes are the whole story: one that accepts a validated order, and one that publishes the schema
 * it validates against so a client can go and read it.
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
     * Starts the server on {@code port} -- 0 for an ephemeral one -- and returns it, so a test can drive the
     * real demo rather than a copy of it.
     *
     * <p><b>Every schema is resolved here, on this thread, before a request can arrive.</b> That is the shape a
     * server must use: resolution mutates the registry and is not concurrent-safe, while reads against an
     * already-compiled schema are. Resolving lazily from a handler would be a race.
     */
    public static HttpServer start(int port) throws IOException {
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // The handler never mentions validation. Reading is what validates: a body that breaks the schema
        // never reaches this code, and the client gets a 400 carrying every diagnostic at once.
        server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
            exchange.requireMethod("POST");
            Order order = exchange.readObject(Order.class);
            exchange.respond(201, new Order(order.sku(), order.quantity() * 2));
        }));

        // Publishing the schemas at their own identity paths is what makes the URL in a !!schema directive --
        // and the one in an error body -- something a client can actually dereference.
        server.createContext("/", TsonHandler.asHttpHandler(codec,
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source())));

        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = start(port);
        Demo.announce("JDK com.sun.net.httpserver", server.getAddress().getPort());
    }
}
