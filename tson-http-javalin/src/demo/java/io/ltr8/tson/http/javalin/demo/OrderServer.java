package io.ltr8.tson.http.javalin.demo;

import io.javalin.Javalin;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonProblemSchema;
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
        DataNameBinder binder = name -> "order".equals(name) ? Order.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(uri -> SCHEMA).dataBindContext(bind).build();
        tson.resolve(SCHEMA);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        Javalin app = Javalin.create(config -> config.showJavalinBanner = false).start(port);

        // So a TsonHttpException escaping any route -- not only the ones written as a TsonHandler -- still
        // becomes a TSON problem body rather than Javalin's own error page.
        TsonHandler.install(app, codec);

        // The handler never mentions validation. Reading is what validates: a body that breaks the schema
        // never reaches this code, and the client gets a 400 carrying every diagnostic at once.
        app.post("/orders", TsonHandler.asHandler(codec, tsonContext -> {
            Order order = tsonContext.readObject(Order.class);
            tsonContext.respond(201, new Order(order.sku(), order.quantity() * 2));
        }));

        // `<path>` rather than `{path}`: an identity path has slashes in it, and only the angle form matches
        // across them. Publishing at the identity path is what makes a !!schema URL dereferenceable.
        app.get("/<path>", TsonHandler.asHandler(codec,
                TsonSchemaHandler.of(SCHEMA, TsonProblemSchema.source())));

        return app;
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Javalin app = start(port);
        Demo.announce("Javalin 6", app.port());
    }
}
