package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblemDiagnostic;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public static final String SCHEMA = schema("order-1.tn");

    /** The identity of this service's business-error schema. */
    public static final String ERRORS_ID = "https://schemas.example.com/2026/32/app/orders-errors-1.tn";

    /**
     * This service's business errors, composed on the transport-level problem schema.
     *
     * <p>{@code sku_not_found} composes {@code problem} (§5.8), so it carries RFC 9457's members and adds its
     * own. A record is closed under its type (§7.2), so the extension is <em>declared</em> rather than assumed
     * — which is what RFC 9457 does with an open JSON object, made explicit.
     *
     * <p>Both imports are named, which is the clearer spelling. {@code text} would arrive through
     * the problem schema's own import of {@code core.tn} either way — imports are transitive — but a schema
     * that uses a name should say where it comes from. Naming both was rejected until {@code UPSTREAM.md} #11
     * was fixed.
     */
    public static final String ERRORS = schema("orders-errors-1.tn");

    /**
     * A description of this service, as a schema governed by {@code meta-http-1.tn} -- published, resolved at
     * startup, and checked by a conformance test.
     *
     * <p><b>The payload types are references, not strings.</b> {@code request: order} is resolved through the
     * imports above when this schema loads, so a description naming a type nothing declares does not start
     * this server. The data-shaped predecessor spelled the same thing as {@code "order"} and needed forty
     * lines of its own to notice.
     */
    public static final String API_ID = "https://schemas.example.com/2026/32/app/orders-api-1.tn";

    public static final String API = schema("orders-api-1.tn");

    /** The one SKU this demo does not stock, so the business-error path is reachable by hand. */
    public static final String UNSTOCKED_SKU = "GONE-1";

    /**
     * A bound {@code sku_not_found}: every member of {@code problem}, plus this error's own {@code sku}.
     * Verbose on purpose -- a record is closed under its type, so composing one in the schema means spelling
     * the composed shape here too.
     */
    @Typename(name = "sku_not_found")
    public record SkuNotFound(Optional<String> type, String title, int status, Optional<String> detail,
                              Optional<String> instance, List<TsonProblemDiagnostic> errors, String sku) {

        static SkuNotFound of(String sku) {
            return new SkuNotFound(Optional.of(TsonHttpException.TYPES + "sku-not-found"), "No such SKU", 404,
                    Optional.of("'" + sku + "' is not stocked"), Optional.empty(), List.of(), sku);
        }
    }

    /** A bound order. Public because binding only ever touches public constructors and methods. */
    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }


    /**
     * A demo schema, from {@code demo/schemas} on the classpath.
     *
     * <p><b>Real {@code .tn} files rather than Java text blocks</b>, shared by all three adapters' demos so a
     * change cannot land in one and not the others. They name their imports literally, as a published
     * document must -- {@link #identitiesMatchTheConstants} is what holds those literals to the constants
     * this class exposes.
     */
    private static String schema(String name) {
        try (java.io.InputStream in = OrderServer.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the demo classpath");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
        Map<String, Class<?>> bindings = Map.of("order", Order.class, "sku_not_found", SkuNotFound.class);
        Map<String, String> schemas = Map.of(SCHEMA_ID, SCHEMA, ERRORS_ID, ERRORS,
                TsonProblemSchema.ID, TsonProblemSchema.source(),
                TsonApiSchema.ID, TsonApiSchema.source(), API_ID, API);
        // metaNameBinder, not bindings: one binds the data a schema describes, the other a governing meta's
        // own vocabulary. Without it the API schema below declares `operation` and nothing can build one.
        Tson tson = Tson.builder().schemaSource(schemas::get).bindings(bindings)
                .metaNameBinder(TsonApiSchema.metaNameBinder()).build();
        tson.resolve(SCHEMA);
        tson.resolve(ERRORS);
        // Resolving the description is what checks it: a payload type nothing declares fails startup here,
        // rather than being published as a contract no client can act on.
        tson.resolve(API);
        TsonHttpCodec codec = new TsonHttpCodec(tson);
        // Every type this server writes, resolved now rather than on a request thread. Descriptor resolution
        // is lazy and its check-then-act is not atomic, so a concurrent first write of a class races
        // (UPSTREAM.md #8). Reads happen to warm it here, but a route that only writes would not.
        codec.prepareToWrite(Order.class, SkuNotFound.class);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // The handler never mentions validation. Reading is what validates: a body that breaks the schema
        // never reaches this code, and the client gets a 400 carrying every diagnostic at once.
        server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
            exchange.requireMethod("POST");
            Order order = exchange.readObject(Order.class);
            if (UNSTOCKED_SKU.equals(order.sku())) {
                // A business error is written, not thrown: it composes problem and carries fields no problem
                // has, so the boundary -- which only knows how to render a problem -- could not produce it.
                exchange.setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(ERRORS_ID));
                exchange.respondBytes(404, exchange.codec()
                        .write(SkuNotFound.of(order.sku()), ERRORS_ID, "sku_not_found"));
                return;
            }
            // Self-describing: the reply names the schema governing it, which this server also publishes, so
            // a client can validate what it got without being told anything out of band.
            exchange.respondBytes(201, exchange.codec()
                    .write(new Order(order.sku(), order.quantity() * 2), SCHEMA_ID, "order"));
        }));

        // Publishing the schemas at their own identity paths is what makes the URL in a !!schema directive --
        // and the one in an error body -- something a client can actually dereference. The API description is
        // a schema too, so it needs no route of its own: the catalog serves it like everything else.
        server.createContext("/", TsonHandler.asHttpHandler(codec,
                TsonSchemaHandler.of(catalog())));

        server.start();
        return server;
    }

    /**
     * This server's whole published schema history: the order schema, and every version of the error-body
     * schema. §10 makes a published schema immutable, so a superseded one stays served -- a document that named
     * it must go on resolving, even though nothing new is written against it.
     */
    private static TsonSchemaCatalog catalog() {
        List<String> published = new ArrayList<>();
        published.add(SCHEMA);
        published.add(ERRORS);
        published.addAll(TsonProblemSchema.publishedSources());
        published.addAll(TsonApiSchema.publishedSources());
        published.add(API);
        return TsonSchemaCatalog.of(published);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = start(port);
        Demo.announce("JDK com.sun.net.httpserver", server.getAddress().getPort());
    }
}
