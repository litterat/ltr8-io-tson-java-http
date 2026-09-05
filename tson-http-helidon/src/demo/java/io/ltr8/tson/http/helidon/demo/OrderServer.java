package io.ltr8.tson.http.helidon.demo;

import io.helidon.http.media.MediaContext;
import io.helidon.webserver.WebServer;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.api.Operation;
import io.ltr8.tson.http.api.TsonApiCoverage;
import io.ltr8.tson.http.api.TsonApiDescription;
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
    public static final String SCHEMA_ID = "https://schemas.example.com/2026/35/app/order-1.tn";

    public static final String SCHEMA = schema("order-1.tn");

    /** The identity of this service's business-error schema. */
    public static final String ERRORS_ID = "https://schemas.example.com/2026/35/app/orders-errors-1.tn";

    /**
     * This service's business errors, composed on the transport-level problem schema.
     *
     * <p>{@code sku_not_found} composes {@code problem} (§5.8), so it carries RFC 9457's members and adds its
     * own. A record is closed under its type (§7.2), so the extension is <em>declared</em> rather than assumed
     * — which is what RFC 9457 does with an open JSON object, made explicit.
     *
     * <p>Both imports are named, which is the clearer spelling. {@code text} would arrive through
     * the problem schema's own import of {@code core.tn} either way — imports are transitive — but a schema
     * that uses a name should say where it comes from. A collision is judged by the declaring schema's own
     * identity, so naming a shared dependency twice is redundant rather than an error.
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
    public static final String API_ID = "https://schemas.example.com/2026/35/app/orders-api-1.tn";

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
     * Starts the server on {@code port} -- 0 for an ephemeral one -- and returns it.
     *
     * <p><b>Every schema is resolved here, on this thread, before a request can arrive.</b> Resolution mutates
     * the registry and is not concurrent-safe; reads against an already-compiled schema are. Resolving lazily
     * from a handler would be a race.
     */
    public static WebServer start(int port) {
        Map<String, Class<?>> bindings = Map.of("order", Order.class, "sku_not_found", SkuNotFound.class);
        Map<String, String> schemas = Map.of(SCHEMA_ID, SCHEMA, ERRORS_ID, ERRORS,
                TsonProblemSchema.ID, TsonProblemSchema.source(),
                TsonApiSchema.ID, TsonApiSchema.source(), API_ID, API);
        // metaNameBinder, not bindings: one binds the data a schema describes, the other a governing meta's
        // own vocabulary. Without it the API schema below declares `operation` and nothing can build one.
        Tson tson = Tson.builder().schemaSource(TsonSchemaSource.ofMap(schemas)).bindings(bindings)
                .metaNameBinder(TsonApiSchema.metaNameBinder()).build();
        tson.resolve(SCHEMA);
        tson.resolve(ERRORS);
        // Resolving the description is what checks it: a payload type nothing declares fails startup here,
        // rather than being published as a contract no client can act on.
        tson.resolve(API);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        // From here on the description is the source of truth for what this server publishes and warms.
        TsonApiDescription described = TsonApiSchema.describedBy(tson, API_ID);

        // Every type this server writes, warmed now rather than on a request thread -- derived from the
        // description's payload types rather than listed, so a response type added there cannot be missed.
        // A warm-up, not a correctness measure: a concurrent first write settles by keeping the winner's
        // descriptor, so this buys latency rather than safety.
        codec.prepareToWrite(described.boundClasses(bindings).toArray(Class<?>[]::new));

        // The description is a contract, so hold this server to it before it accepts anything. `serving`
        // hands back the operation, which is where the path below comes from -- and refuses a name the
        // description does not declare, so a renamed operation fails at the handler that outlived it.
        TsonApiCoverage coverage = TsonApiCoverage.of(described);

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
                    routing.post(coverage.serving("create_order").path(), (request, response) -> {
                        Order order = request.content().as(Order.class);
                        if (UNSTOCKED_SKU.equals(order.sku())) {
                            // Written, not thrown: a business error composes problem and carries fields no
                            // problem has, so the error boundary could not produce it.
                            response.status(404)
                                    .header(TsonSchemaHeader.NAME, TsonSchemaHeader.format(ERRORS_ID))
                                    .send(SkuNotFound.of(order.sku()));
                            return;
                        }
                        response.status(201).send(new Order(order.sku(), order.quantity() * 2));
                    });

                    // Publishing at the identity path is what makes a !!schema URL dereferenceable. any(),
                    // because the paths served are the schemas' own, not routes Helidon knows about -- and
                    // not the declared `{schemaPath}` either, which is why coverage is claimed by name.
                    // The API description is a schema too, so the catalog serves it like everything else.
                    coverage.serving("get_schema");
                    routing.any(TsonHandler.asHandler(codec,
                            TsonSchemaHandler.of(catalog(described, schemas))));
                    coverage.requireComplete();
                })
                .build()
                .start();
    }


    /**
     * What this server publishes, <b>derived from its own description</b> rather than listed: the description,
     * the meta layer governing it, and its imports transitively -- everything a client needs to resolve the
     * contract it was given. The bundled standard library is excluded; a client already has it.
     *
     * <p>This used to be five {@code add} calls, and the conformance test asserted they matched what the
     * description references. Deriving makes the two the same thing, so a schema referenced but not published
     * is no longer possible rather than merely tested for.
     *
     * <p>§3.5 still applies to what goes in: when a published schema is superseded, both versions stay served,
     * because a document that named the old one must go on resolving.
     */
    private static TsonSchemaCatalog catalog(TsonApiDescription described, Map<String, String> schemas) {
        List<String> published = new ArrayList<>();
        for (String id : described.referencedSchemas()) {
            String source = schemas.get(id);
            if (source == null) {
                // Reachable only if a schema resolved from somewhere this map does not hold -- which would
                // mean publishing a description a client cannot follow.
                throw new IllegalStateException("the description references '" + id + "', which this server "
                        + "has no source for");
            }
            published.add(source);
        }
        return TsonSchemaCatalog.of(published);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        WebServer server = start(port);
        Demo.announce("Helidon 4 SE", server.port());
    }
}
