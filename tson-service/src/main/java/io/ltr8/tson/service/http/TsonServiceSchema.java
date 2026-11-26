package io.ltr8.tson.service.http;

import io.ltr8.tson.service.protocol.Exchange;
import io.ltr8.tson.service.protocol.Protocol;
import io.ltr8.tson.service.protocol.Signature;
import io.ltr8.tson.Tson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code service.tn} — the vocabulary a schema imports to describe a service — as source text a server can publish,
 * and as the bindings that make it usable.
 *
 * <h2>What this is for</h2>
 *
 * <p>A schema importing this one declares protocols of exchanges, and http projections whose endpoints bind them,
 * carry a signature inline, or answer a document. Every name the layer declares sits under its one root map, {@code
 * service}, so it reserves that one root name in every importer and a description writes each tag and annotation
 * qualified:
 *
 * <pre>{@code
 * !!import:"https://tson.io/2026/40/io/ltr8/http/service.tn"
 * !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
 * !schema {
 *   orders => !service.protocol { exchanges: { place_order => { request: order  response: order } } }
 *
 *   orders_http => !service.http {
 *     binds: [orders]
 *     resources: {
 *       "/orders" => { POST => !service.binding { exchange: orders.exchanges.place_order  status: 201 } }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>{@code order} there is a type reference, not a string, and {@code orders} in {@code binds} and {@code
 * orders.exchanges.place_order} are references to values</b> -- a protocol, and an exchange reached through the
 * protocol's entry, its {@code exchanges} field and the exchange's key. The compiler resolves each and refuses a name
 * nothing declares. A description is a schema because others refer into it: a data document may hold references into the
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
 *         .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindingsWith(Map.of("order", Order.class)))));
 * tson.resolve(description);
 * }</pre>
 */
public final class TsonServiceSchema {

    /** This schema's identity — the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/40/io/ltr8/http/service.tn";

    private static final String SOURCE = readResource("/service.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.ofEntries(Map.entry("service.protocol", Protocol.class),
            Map.entry("service.exchange", Exchange.class), Map.entry("service.signature", Signature.class),
            Map.entry("service.http", Http.class), Map.entry("service.resource", Resource.class),
            Map.entry("service.endpoint", Endpoint.class), Map.entry("service.operation", Operation.class),
            Map.entry("service.binding", Binding.class), Map.entry("service.document", Document.class),
            Map.entry("service.exemption", Exemption.class), Map.entry("service.http_verb", HttpVerb.class),
            Map.entry("service.security_scheme", SecurityScheme.class), Map.entry("service.http_auth", HttpAuth.class),
            Map.entry("service.api_key", ApiKey.class), Map.entry("service.api_key_in", ApiKeyIn.class));

    private TsonServiceSchema() {
    }

    /** The schema source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /**
     * Every version of this schema that is still published, current first.
     *
     * <p>§3.5 binds a <em>published</em> schema: a shape change is a new name and the superseded document
     * stays served. Nothing here is published, so there is one version and it is edited in place.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE);
    }

    /**
     * The published history keyed by identity -- what a schema source needs to resolve a description importing
     * this schema, and what a server publishing one serves beside it: the versions {@link #publishedSources()}
     * holds. Wiring this rather than {@link #source()} keeps a call site right whichever versions those are.
     */
    public static Map<String, String> publishedById() {
        return Map.of(ID, SOURCE);
    }

    /**
     * This vocabulary's records by their qualified names, mapped to the classes in this package: {@code
     * service.protocol} and {@code service.http}, the two a description's entries apply, and what they hold; and the
     * security schemes, {@code service.http_auth} and {@code service.api_key} under the base {@code
     * service.security_scheme}, whose value entries a projection and an endpoint name. The namespace type {@code
     * service.exchanges} and the map type {@code service.resources} need no class: a field they type binds to a {@code
     * Namespace} or an {@code AnnotatedMap} of the value type's class. The map is explicit, so a class in this package
     * binds only by being named here, and a record no class binds -- {@code service.placement}, folded into each
     * endpoint -- is not listed.
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
                        + "service.tn's vocabulary; within one closure a name means one thing");
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
     *                                  unsound in a way only a reader can see ({@link TsonServiceDescription})
     */
    public static TsonServiceDescription describedBy(Tson tson, String schemaId) {
        return TsonServiceDescription.of(tson, schemaId);
    }

    private static String readResource(String path) {
        try (InputStream in = TsonServiceSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
