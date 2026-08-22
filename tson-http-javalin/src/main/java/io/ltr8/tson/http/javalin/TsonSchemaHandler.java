package io.ltr8.tson.http.javalin;

import io.ltr8.tson.http.TsonSchemaCatalog;

import java.util.Set;

/**
 * Serves a {@link TsonSchemaCatalog} through Javalin -- the routing around the catalog, and nothing else. The
 * lookup, the identity-path rule and the cache policy live in the catalog, shared with every other adapter.
 *
 * <p>Register it on a wildcard route, since the paths it serves are the identity paths of the schemas it holds
 * rather than anything Javalin's router knows about:
 *
 * <pre>{@code
 * app.get("/<path>", TsonHandler.asHandler(codec, TsonSchemaHandler.of(orderSchema, problemSchema)));
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
    public void handle(TsonContext context) {
        context.requireMethod("GET", "HEAD");
        byte[] document = catalog.find(context.path()).orElseThrow(() -> catalog.noSuchSchema(context.path()));
        context.setHeader("Cache-Control", TsonSchemaCatalog.IMMUTABLE);
        // Buffered rather than streamed: a schema is small, its length is known, and a Content-Length is worth
        // more to a fetching client -- which is size-capping its read -- than not materialising a few kilobytes.
        context.respondBytes(200, document);
    }
}
