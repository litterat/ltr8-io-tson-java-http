package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonApi;
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

    public static final String SCHEMA = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
                order => { sku: text  quantity: int32 }
            }""";

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
     * {@code problem-3.tn}'s own import of {@code core.tn} either way — imports are transitive — but a schema
     * that uses a name should say where it comes from. Naming both was rejected until {@code UPSTREAM.md} #11
     * was fixed.
     */
    public static final String ERRORS = """
            !!id:"https://schemas.example.com/2026/32/app/orders-errors-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            !!import:"https://tson.io/2026/32/ltr8/http/problem-3.tn"
            @doc:"Business errors: the request was schema-valid, and the domain still said no."
            {
                sku_not_found => problem & { sku: text }
            }""";

    /** A description of this service, governed by api-1.tn -- published, and checked by a conformance test. */
    public static final String API_ID = "https://schemas.example.com/2026/32/app/orders-api-4.tn";

    public static final String API = """
            !!id:"https://schemas.example.com/2026/32/app/orders-api-4.tn"
            !!schema:"%s"
            !api {
                title: "Orders"
                version: "1"
                imports: [ "%s"  "%s"  "%s" ]
                operations: [
                    !operation {
                        method: POST
                        path: "/orders"
                        summary: "Accept an order and confirm it with the quantity doubled"
                        parameters: []
                        request: "order"
                        responses: [
                            !response { status: 201  body: "order"
                                        description: "The confirmed order" }
                            !response { status: 400  body: "problem"
                                        description: "The body is not a valid order" }
                            !response { status: 404  body: "sku_not_found"
                                        description: "The order names a SKU this service does not stock" }
                        ]
                    }
                    !operation {
                        method: GET
                        path: "/{schemaPath}"
                        summary: "Fetch a schema this service publishes, at its own identity path"
                        parameters: [
                            !parameter { name: "schemaPath"  in: PATH  type: "text"  required: true
                                         description: "The path component of the schema's own !!id" }
                        ]
                        responses: [
                            !response { status: 200  description: "The schema document, served as bytes" }
                            !response { status: 404  body: "problem"
                                        description: "No schema is published there" }
                        ]
                    }
                ]
            }""".formatted(TsonApi.SCHEMA_ID, SCHEMA_ID, ERRORS_ID, TsonProblemSchema.ID);

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
        DataNameBinder binder = name -> bindings.getOrDefault(name, null) != null
                ? bindings.get(name) : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Map<String, String> schemas = Map.of(SCHEMA_ID, SCHEMA, ERRORS_ID, ERRORS,
                TsonProblemSchema.ID, TsonProblemSchema.source());
        Tson tson = Tson.builder().schemaSource(schemas::get).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        tson.resolve(ERRORS);
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
        // and the one in an error body -- something a client can actually dereference.
        // The description is a data document, not a schema, so it is not in the schema catalog -- but it has
        // an identity like everything else here, and is served at that identity's path.
        server.createContext("/2026/32/app/orders-api-4.tn", TsonHandler.asHttpHandler(codec, exchange -> {
            exchange.requireMethod("GET", "HEAD");
            exchange.respondBytes(200, API.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }));

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
        published.addAll(TsonApi.publishedSources());
        return TsonSchemaCatalog.of(published);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = start(port);
        Demo.announce("JDK com.sun.net.httpserver", server.getAddress().getPort());
    }
}
