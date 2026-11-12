package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code meta-service.tn} — the vocabulary a schema imports to describe a service — as source text a server can
 * publish, and as the bindings that make it usable.
 *
 * <h2>What this is for</h2>
 *
 * <p>A schema importing this one declares protocols of exchanges, and http projections whose endpoints bind them,
 * carry a signature inline, or answer a document:
 *
 * <pre>{@code
 * !!import:"https://tson.io/2026/40/io/ltr8/http/meta-service.tn"
 * !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
 * !schema {
 *   orders => !protocol { place_order => { request: order  response: order } }
 *
 *   @binds:[orders]
 *   orders_http => !http { "/orders" => { POST => !binding { exchange: orders.place_order  status: 201 } } }
 * }
 * }</pre>
 *
 * <p><b>{@code order} there is a type reference, not a string, and {@code orders.place_order} is a reference to a
 * value</b> -- the protocol's entry, then the exchange's key. The compiler resolves both and refuses a name nothing
 * declares. A description is a schema because others refer into it: a data document may hold references into the
 * schemas it imports, but nothing can refer into a data document, so a binding or a coverage claim naming an exchange
 * of one would carry bare names, and every consumer would need its own resolver for them, in every language that
 * reads it.
 *
 * <h2>Wiring</h2>
 *
 * <p>Two things, and there is nothing else: this schema reachable from the {@code schemaSource}, and the bound
 * classes in this package in the config's one bind context, beside the application's own. One context binds a
 * schema's value entries and a data document's values alike, so {@link #bindingsWith} merges the two maps.
 *
 * <pre>{@code
 * Tson tson = Tson.of(ProcessorConfig.defaults()
 *         .withSchemaAccess(SchemaAccess.of(source))
 *         .withDataBindContext(TsonBindings.of(TsonApiSchema.bindingsWith(Map.of("order", Order.class)))));
 * tson.resolve(description);
 * }</pre>
 */
public final class TsonApiSchema {

    /** This schema's identity — the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/40/io/ltr8/http/meta-service.tn";

    private static final String SOURCE = readResource("/meta-service.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.of("exchange", Exchange.class, "signature",
            Signature.class, "endpoint", Endpoint.class, "operation", Operation.class, "binding", Binding.class,
            "document", Document.class, "resource", Resource.class, "exemption", Exemption.class, "http_verb",
            HttpVerb.class);

    private TsonApiSchema() {
    }

    /** The schema source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /**
     * Every version of this schema that is still published, current first — one, today.
     *
     * <p>§3.5 binds a <em>published</em> schema: a shape change is a new name and the superseded document
     * stays served. Nothing here is published, so there is one version and it is edited in place.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE);
    }

    /**
     * The published history keyed by identity -- what a schema source needs to resolve a description importing
     * this schema, and what a server publishing one serves beside it. One entry today; wiring this rather
     * than {@link #source()} keeps a call site right when the history grows.
     */
    public static Map<String, String> publishedById() {
        return Map.of(ID, SOURCE);
    }

    /**
     * This vocabulary's records by name, mapped to the classes in this package: what {@code protocol} and {@code
     * http} hold, and the {@code exemption} an http projection's {@code @not_bound} lists. {@code protocol} and
     * {@code http} need no class: a value of a namespace type is a {@code Namespace}. The map is explicit, so a
     * class in this package binds only by being named here, and a record no class binds -- {@code placement},
     * folded into each endpoint -- is not listed.
     *
     * <p>A description will not read without them: an entry no class binds is held as a tree, and {@link
     * #describedBy} reads bound records.
     */
    public static Map<String, Class<?>> bindings() {
        return BINDINGS;
    }

    /**
     * {@code application}'s bindings and this vocabulary's, as the one map a bind context is built from.
     *
     * @throws IllegalArgumentException if {@code application} binds a name this vocabulary binds: within one
     *                                  closure a name means one thing
     */
    public static Map<String, Class<?>> bindingsWith(Map<String, Class<?>> application) {
        Map<String, Class<?>> merged = new LinkedHashMap<>(application);
        BINDINGS.forEach((name, bound) -> {
            if (merged.putIfAbsent(name, bound) != null) {
                throw new IllegalArgumentException("'" + name + "' is bound by the application and by "
                        + "meta-service.tn's vocabulary; within one closure a name means one thing");
            }
        });
        return Map.copyOf(merged);
    }

    /**
     * Reads the service a resolved schema describes, checking what the resolver could not.
     *
     * @param tson     a {@code Tson} binding {@link #bindings()}, with {@code schemaId} resolved
     * @param schemaId the identity of the description schema
     * @throws IllegalArgumentException if {@code schemaId} is not resolved in {@code tson}, or the description is
     *                                  unsound in a way only a reader can see ({@link TsonApiDescription})
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
