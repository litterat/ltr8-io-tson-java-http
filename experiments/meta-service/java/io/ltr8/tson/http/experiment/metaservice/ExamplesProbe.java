package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documents under {@code experiments/meta-service/examples/} resolve against the sketch, and the two apis
 * read into route tables -- so an example that stops being true fails the build rather than the reader.
 *
 * <p>Each file is served at the identity its own {@code !!id} declares ({@code TsonDocumentHeader.peek} reads it),
 * so the examples name each other the way published documents do, literally.
 */
class ExamplesProbe {

    static final String EXAMPLES = "https://schemas.example.com/2026/35/experiment/meta-service/";

    static Tson tson() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        Path dir = Path.of(System.getProperty("experiments.dir", "../experiments")).resolve("meta-service/examples");
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".tn")).sorted().forEach(f -> {
                String text = read(f);
                String id = TsonDocumentHeader.peek(text).id()
                        .orElseThrow(() -> new IllegalStateException(f + " declares no !!id"));
                lib.put(id, text);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Resolves one example by identity and hands back its entries; the resolver's verdict must be clean. */
    static Routes routesOf(Tson tson, String file, String apiName) {
        return Routes.of(apiName, apiOf(tson, file, apiName), tson.schemaRegistry().get(EXAMPLES + file)
                .orElseThrow().schema().entries()::get);
    }

    static Api apiOf(Tson tson, String file, String apiName) {
        String id = EXAMPLES + file;
        List<Diagnostic> problems = tson.validateSchema(read(Path.of(System.getProperty("experiments.dir",
                "../experiments")).resolve("meta-service/examples").resolve(file)));
        assertEquals(List.of(), problems, () -> file + ": " + problems);
        var entries = tson.schemaRegistry().get(id).orElseThrow().schema().entries();
        return assertInstanceOf(Api.class, entries.get(apiName).body());
    }

    /** Documentation is annotation on the key: {@code @summary} the short form, {@code @doc} the long. */
    @Test
    void anEndpointsSummaryAndDocRideOnItsKey() {
        Api api = apiOf(tson(), "orders-api-1.tn", "orders_api");
        var post = api.resources().get("/orders").endpoints().getAnnotations("POST");
        assertEquals("Place an order.", post.value("summary", String.class).orElseThrow());
        assertTrue(post.value("doc", String.class).orElseThrow().startsWith("Accepts a new order"));
    }

    @Test
    void theInterfaceOnlyExampleResolves() {
        Tson tson = tson();
        String text = read(Path.of(System.getProperty("experiments.dir", "../experiments"))
                .resolve("meta-service/examples/orders-1.tn"));
        List<Diagnostic> problems = tson.validateSchema(text);
        assertEquals(List.of(), problems, () -> "" + problems);

        var entries = tson.schemaRegistry().get(EXAMPLES + "orders-1.tn").orElseThrow().schema().entries();
        Interface orders = assertInstanceOf(Interface.class, entries.get("orders").body());
        assertEquals(List.of("place_order", "get_order", "list_orders", "cancel_order"),
                List.copyOf(orders.methods().keySet()));
        assertEquals("Cancel an order. Cancelling twice is the same as cancelling once.",
                orders.methods().getAnnotations("cancel_order").value("doc", String.class).orElseThrow());
        assertEquals(List.of("orders"), assertInstanceOf(Interface.class, entries.get("orders_v2").body()).extended());
        // Facts about a method are bare annotations on its key, not fields of its signature.
        assertTrue(orders.methods().getAnnotations("get_order").has("safe"));
        assertTrue(orders.methods().getAnnotations("cancel_order").has("idempotent"));
        assertFalse(orders.methods().getAnnotations("place_order").has("safe"));
    }

    @Test
    void theWebServiceOnlyExampleReadsIntoRoutes() {
        Routes routes = routesOf(tson(), "orders-api-inline-1.tn", "orders_api").requireComplete();

        assertEquals(3, routes.routes().size());
        Routes.Route schema = routes.route(HttpVerb.GET, "/{schemaPath}").orElseThrow();
        assertEquals(Map.of("schemaPath", Placement.Location.PATH), schema.placement().fields());
        assertEquals(201, routes.route(HttpVerb.POST, "/orders").orElseThrow().status());
        // On an operation the marker sits before the verb key, the same way.
        Api api = apiOf(tson(), "orders-api-inline-1.tn", "orders_api");
        assertTrue(api.resources().get("/{schemaPath}").endpoints().getAnnotations("GET").has("safe"));
    }

    @Test
    void theBothExampleReadsIntoRoutesAndHonoursItsClaim() {
        Routes routes = routesOf(tson(), "orders-api-1.tn", "orders_api").requireComplete();

        assertEquals(4, routes.routes().size());
        Routes.Route create = routes.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(List.of("order"), create.placement().at(Placement.Location.BODY));
        assertEquals(List.of("idempotency_key"), create.placement().at(Placement.Location.HEADER));
        assertEquals("new_order", create.request().orElseThrow().name());
        assertEquals(List.of("status", "page", "page_size"),
                routes.route(HttpVerb.GET, "/orders").orElseThrow().placement().at(Placement.Location.QUERY));
        assertEquals(Map.of("id", Placement.Location.PATH),
                routes.route(HttpVerb.DELETE, "/orders/{id}").orElseThrow().placement().fields());
    }
}
