package io.ltr8.tson.http.jdk;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.http.TsonHttpException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Serves schema documents at the paths their own {@code !!id}s name, so the URL in a
 * <code>!!schema:"https://…"</code> reference is something a client can actually dereference.
 *
 * <p><b>The path comes from the document, never from the caller.</b> A schema is registered by handing over its
 * source text; this parses the {@code !!id} out of it and derives the path from that. [TSON-DATA] §2.2.1 makes
 * canonical identity the lowercase host plus path, so serving a document anywhere other than its own identity
 * path is serving it under a name that is not its own -- and the loader on the other side would reject it. There
 * is deliberately no way to register a schema at a path of the caller's choosing.
 *
 * <pre>{@code
 * TsonSchemaHandler schemas = TsonSchemaHandler.of(orderSchemaText, TsonProblemSchema.source());
 * server.createContext("/", TsonHandler.asHttpHandler(codec, schemas));
 * }</pre>
 *
 * <p><b>The query is ignored.</b> A reference may carry a {@code ?sha256=} pin, which §2.2.1 calls verification
 * metadata and not identity: the pinned and the plain reference name one document, so both are served the same
 * bytes and the client verifies the pin it brought.
 *
 * <p><b>Responses say immutable, and mean it.</b> [TSON-SCHEMA] §10's immutability rule is that a published
 * schema's content never changes -- a shape change is published under a new name -- so {@code immutable} with a
 * year of {@code max-age} is a statement of the format's own rule rather than an optimistic guess about this
 * deployment.
 */
public final class TsonSchemaHandler implements TsonHandler {

    /** A year, in seconds -- the longest {@code max-age} RFC 9111 §5.2.1 suggests anyone bother with. */
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final Map<String, byte[]> byPath;

    private TsonSchemaHandler(Map<String, byte[]> byPath) {
        this.byPath = Map.copyOf(byPath);
    }

    /**
     * A handler serving each of {@code schemaTexts} at the path its own {@code !!id} names.
     *
     * @throws IllegalArgumentException if a document declares no {@code !!id}, declares one that is not a URI
     *                                  with a path, or names a path another already claims
     */
    public static TsonSchemaHandler of(String... schemaTexts) {
        Map<String, byte[]> byPath = new LinkedHashMap<>();
        for (String text : schemaTexts) {
            String id = new TsonSchemaParser(text).parseSchemaDocument().id()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "a schema served by identity must declare an !!id; this one declares none"));
            String path = pathOf(id);
            byte[] existing = byPath.put(path, text.getBytes(StandardCharsets.UTF_8));
            if (existing != null) {
                throw new IllegalArgumentException("two schemas claim the path '" + path + "'; '" + id
                        + "' is the second");
            }
        }
        return new TsonSchemaHandler(byPath);
    }

    /** The identity paths this handler serves, in the order they were registered. */
    public Set<String> paths() {
        return byPath.keySet();
    }

    @Override
    public void handle(TsonExchange exchange) {
        exchange.requireMethod("GET", "HEAD");
        byte[] document = byPath.get(exchange.uri().getPath());
        if (document == null) {
            throw new TsonHttpException(404, "No such schema",
                    "this server serves no schema at '" + exchange.uri().getPath() + "'", java.util.List.of(),
                    null);
        }
        exchange.setHeader("Cache-Control", IMMUTABLE);
        // Buffered rather than streamed: a schema is small, its length is known, and a Content-Length is worth
        // more to a fetching client -- which is size-capping the read -- than not materialising a few kilobytes.
        exchange.respondBytes(200, document);
    }

    /** The path component of an {@code !!id}, which is the second half of its canonical identity. */
    private static String pathOf(String id) {
        try {
            URI uri = new URI(id);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                throw new IllegalArgumentException("'" + id + "' has no path to serve it at");
            }
            return path;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("'" + id + "' is not a URI: " + e.getMessage(), e);
        }
    }
}
