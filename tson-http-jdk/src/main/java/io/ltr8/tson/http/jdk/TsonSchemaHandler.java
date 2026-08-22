package io.ltr8.tson.http.jdk;

import io.ltr8.tson.http.TsonSchemaCatalog;

import java.util.Set;

/**
 * Serves a {@link TsonSchemaCatalog} over {@code com.sun.net.httpserver} -- the routing around the catalog,
 * and nothing else. The lookup, the identity-path rule and the cache policy all live in the catalog, because
 * every adapter needs exactly the same ones.
 *
 * <pre>{@code
 * server.createContext("/", TsonHandler.asHttpHandler(codec,
 *         TsonSchemaHandler.of(orderSchemaText, TsonProblemSchema.source())));
 * }</pre>
 */
public final class TsonSchemaHandler implements TsonHandler {

    private final TsonSchemaCatalog catalog;

    public TsonSchemaHandler(TsonSchemaCatalog catalog) {
        this.catalog = catalog;
    }

    /** A handler over a catalog of {@code schemaTexts}. */
    public static TsonSchemaHandler of(String... schemaTexts) {
        return new TsonSchemaHandler(TsonSchemaCatalog.of(schemaTexts));
    }

    /** A handler over an already-built catalog. */
    public static TsonSchemaHandler of(TsonSchemaCatalog catalog) {
        return new TsonSchemaHandler(catalog);
    }

    /** The identity paths this handler serves. */
    public Set<String> paths() {
        return catalog.paths();
    }

    @Override
    public void handle(TsonExchange exchange) {
        exchange.requireMethod("GET", "HEAD");
        String path = exchange.uri().getPath();
        byte[] document = catalog.find(path).orElseThrow(() -> catalog.noSuchSchema(path));
        exchange.setHeader("Cache-Control", TsonSchemaCatalog.IMMUTABLE);
        // Buffered rather than streamed: a schema is small, its length is known, and a Content-Length is worth
        // more to a fetching client -- which is size-capping its read -- than not materialising a few kilobytes.
        exchange.respondBytes(200, document);
    }
}
