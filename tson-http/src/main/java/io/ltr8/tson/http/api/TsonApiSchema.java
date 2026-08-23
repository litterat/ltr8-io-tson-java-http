package io.ltr8.tson.http.api;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code meta-http-1.tn} — the meta layer a schema names to describe an HTTP API — as source text a server
 * can publish, and as the binder that makes it usable.
 *
 * <h2>What this is for</h2>
 *
 * <p>A schema governed by this one declares its operations as entries:
 *
 * <pre>{@code
 * !!meta:"https://tson.io/2026/32/ltr8/http/meta-http-1.tn"
 * !!import:"https://schemas.example.com/2026/32/app/order-1.tn"
 * {
 *   create_order => !operation {
 *     method: POST  path: "/orders"  parameters: []  request: order
 *     responses: [ !response { status: 201  body: order } ]
 *   }
 * }
 * }</pre>
 *
 * <p><b>{@code order} there is a type reference, not a string.</b> The compiler resolves it through the
 * imports and refuses a name nothing declares — which is the whole reason to describe an API at the schema
 * layer. A description written as data can name a schema but cannot hold a reference to a type, so it carries
 * bare names and every consumer needs its own resolver for them — in every language that reads it.
 *
 * <h2>Wiring</h2>
 *
 * <p>Three things, and there is nothing else: this schema reachable from the {@code schemaSource}, the bound
 * classes in this package, and {@link #metaNameBinder()} on the config — <em>not</em> {@code bindings}, which
 * binds the data a schema describes. A meta layer's own vocabulary is a separate namespace, because one
 * holding both would collide the first time a schema type and a constructor shared a name.
 *
 * <pre>{@code
 * Tson tson = Tson.builder()
 *         .schemaSource(source)
 *         .metaNameBinder(TsonApiSchema.metaNameBinder())
 *         .build();
 * tson.resolve(description);
 * }</pre>
 */
public final class TsonApiSchema {

    /** This meta layer's identity — the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/32/ltr8/http/meta-http-1.tn";

    private static final String SOURCE = readResource("/meta-http-1.tn");

    private TsonApiSchema() {
    }

    /** The schema source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /**
     * Every version of this schema that is still published, current first — one, today.
     *
     * <p>§10 binds a <em>published</em> schema: a shape change is a new name and the superseded document
     * stays served. Nothing here is published, so there is one version and it is edited in place.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE);
    }

    /**
     * The binder for this meta layer's own vocabulary — {@code operation} and the records it carries, mapped
     * to the classes in this package.
     *
     * <p>Pass it to {@code TsonConfig.metaNameBinder}, which composes it over the kernel's own vocabulary
     * rather than replacing it. A schema governed by this meta layer will not resolve without it: the
     * constructor is declared, and nothing can build the value it constructs.
     */
    public static DataNameBinder metaNameBinder() {
        return new DataNameBinder.DefaultDataNameBinder(Set.of("io.ltr8.tson.http.api"), Map.of());
    }

    /**
     * Reads the API a resolved schema describes.
     *
     * @param tson     a {@code Tson} built with {@link #metaNameBinder()}, with {@code schemaId} resolved
     * @param schemaId the identity of the description schema
     * @throws IllegalArgumentException if {@code schemaId} is not resolved in {@code tson}
     */
    public static TsonApiDescription describedBy(Tson tson, String schemaId) {
        return TsonApiDescription.of(tson, schemaId);
    }

    private static String readResource(String path) {
        try (InputStream in = TsonApiSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
