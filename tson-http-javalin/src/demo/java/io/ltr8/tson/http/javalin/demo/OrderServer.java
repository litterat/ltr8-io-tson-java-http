package io.ltr8.tson.http.javalin.demo;

import io.javalin.Javalin;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
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
import io.ltr8.tson.http.javalin.TsonHandler;
import io.ltr8.tson.http.javalin.TsonSchemaHandler;

/**
 * The same server as the JDK and Helidon demos, on Javalin 6. {@code ./gradlew :tson-http-javalin:runDemo}
 *
 * <p>Deliberately the same routes and the same behaviour: three adapters over one codec should be
 * indistinguishable from a client's side, and the demos are where that is easiest to check by hand -- the same
 * curl commands work against all three.
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
     * Starts the server on {@code port} -- 0 for an ephemeral one -- and returns it.
     *
     * <p><b>Every schema is resolved here, on this thread, before a request can arrive.</b> Resolution mutates
     * the registry and is not concurrent-safe; reads against an already-compiled schema are. Resolving lazily
     * from a handler would be a race.
     */
    public static Javalin start(int port) {
        Map<String, Class<?>> bindings = Map.of("order", Order.class, "sku_not_found", SkuNotFound.class);
        Map<String, String> schemas = Map.of(SCHEMA_ID, SCHEMA, ERRORS_ID, ERRORS,
                TsonProblemSchema.ID, TsonProblemSchema.source());
        Tson tson = Tson.builder().schemaSource(schemas::get).bindings(bindings).build();
        tson.resolve(SCHEMA);
        tson.resolve(ERRORS);
        TsonHttpCodec codec = new TsonHttpCodec(tson);
        // Every type this server writes, resolved now rather than on a request thread. Descriptor resolution
        // is lazy and its check-then-act is not atomic, so a concurrent first write of a class races
        // (UPSTREAM.md #8). Reads happen to warm it here, but a route that only writes would not.
        codec.prepareToWrite(Order.class, SkuNotFound.class);

        Javalin app = Javalin.create(config -> config.showJavalinBanner = false).start(port);

        // So a TsonHttpException escaping any route -- not only the ones written as a TsonHandler -- still
        // becomes a TSON problem body rather than Javalin's own error page.
        TsonHandler.install(app, codec);

        // The handler never mentions validation. Reading is what validates: a body that breaks the schema
        // never reaches this code, and the client gets a 400 carrying every diagnostic at once.
        app.post("/orders", TsonHandler.asHandler(codec, tsonContext -> {
            Order order = tsonContext.readObject(Order.class);
            if (UNSTOCKED_SKU.equals(order.sku())) {
                // A business error is written, not thrown: it composes problem and carries fields no problem
                // has, so the boundary -- which only knows how to render a problem -- could not produce it.
                tsonContext.setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(ERRORS_ID));
                tsonContext.respondBytes(404, tsonContext.codec()
                        .write(SkuNotFound.of(order.sku()), ERRORS_ID, "sku_not_found"));
                return;
            }
            // Self-describing: the reply names the schema governing it, which this server also publishes, so
            // a client can validate what it got without being told anything out of band.
            tsonContext.respondBytes(201, tsonContext.codec()
                    .write(new Order(order.sku(), order.quantity() * 2), SCHEMA_ID, "order"));
        }));

        // `<path>` rather than `{path}`: an identity path has slashes in it, and only the angle form matches
        // across them. Publishing at the identity path is what makes a !!schema URL dereferenceable.
        // The description is a data document, not a schema, so it is served on its own route rather than
        // through the schema catalog -- at its identity's path, like everything else here.
        app.get("/2026/32/app/orders-api-4.tn", TsonHandler.asHandler(codec, tsonContext ->
                tsonContext.respondBytes(200, API.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        app.get("/<path>", TsonHandler.asHandler(codec,
                TsonSchemaHandler.of(catalog())));

        return app;
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

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Javalin app = start(port);
        Demo.announce("Javalin 6", app.port());
    }
}
