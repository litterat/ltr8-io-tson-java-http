package io.ltr8.tson.http;

import io.ltr8.tson.compiler.TsonSchemaParser;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The schema documents a server publishes, indexed by the path each one's own {@code !!id} names -- so the URL
 * in a <code>!!schema:"https://…"</code> reference is something a client can actually dereference.
 *
 * <p>Server-agnostic on purpose: every adapter needs the same lookup and the same two headers, and only the
 * routing around it differs. An adapter's schema handler is a few lines over this.
 *
 * <p><b>The path comes from the document, never from the caller.</b> A schema is registered by handing over its
 * source text; this parses the {@code !!id} out and derives the path from that. [TSON-DATA] §2.2.1 makes
 * canonical identity the lowercase host plus path, so serving a document anywhere other than its own identity
 * path serves it under a name that is not its own -- and a fetching client's loader would reject it on the
 * {@code !!id} cross-check. There is deliberately no way to register a schema at a path of the caller's
 * choosing.
 *
 * <p><b>A query is not part of the lookup.</b> A reference may carry a {@code ?sha256=} pin, which §2.2.1 calls
 * verification metadata and not identity: the pinned and the plain reference name one document, so both get the
 * same bytes and the client verifies the pin it brought.
 */
public final class TsonSchemaCatalog {

    /**
     * What a schema response should say about caching. [TSON-SCHEMA] §10's immutability rule is that a
     * published schema's content never changes -- a shape change is published under a new name -- so this is a
     * statement of the format's own rule rather than an optimistic guess about a deployment.
     */
    public static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final Map<String, byte[]> byPath;

    private TsonSchemaCatalog(Map<String, byte[]> byPath) {
        this.byPath = Map.copyOf(byPath);
    }

    /**
     * A catalog of each of {@code schemaTexts}, indexed at the path its own {@code !!id} names.
     *
     * @throws IllegalArgumentException if a document declares no {@code !!id}, declares one that is not a URI
     *                                  with a path, or names a path another already claims
     */
    public static TsonSchemaCatalog of(String... schemaTexts) {
        Map<String, byte[]> byPath = new LinkedHashMap<>();
        for (String text : schemaTexts) {
            String id = new TsonSchemaParser(text).parseSchemaDocument().id()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "a schema served by identity must declare an !!id; this one declares none"));
            byte[] existing = byPath.put(pathOf(id), text.getBytes(StandardCharsets.UTF_8));
            if (existing != null) {
                throw new IllegalArgumentException("two schemas claim the path '" + pathOf(id) + "'; '" + id
                        + "' is the second");
            }
        }
        return new TsonSchemaCatalog(byPath);
    }

    /** The document at {@code path}, or empty if this catalog publishes none there. */
    public Optional<byte[]> find(String path) {
        return Optional.ofNullable(byPath.get(path)).map(byte[]::clone);
    }

    /** The identity paths this catalog publishes, in registration order. */
    public Set<String> paths() {
        return byPath.keySet();
    }

    /** A 404 for {@code path}, so every adapter phrases it the same way. */
    public TsonHttpException noSuchSchema(String path) {
        return new TsonHttpException(404, "No such schema", "this server publishes no schema at '" + path + "'",
                java.util.List.of(), null);
    }

    /** The path component of an {@code !!id}, which is the second half of its canonical identity. */
    private static String pathOf(String id) {
        try {
            String path = new URI(id).getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                throw new IllegalArgumentException("'" + id + "' has no path to serve it at");
            }
            return path;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("'" + id + "' is not a URI: " + e.getMessage(), e);
        }
    }
}
