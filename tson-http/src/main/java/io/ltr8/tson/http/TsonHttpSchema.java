package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.http.schema.ApiKey;
import io.ltr8.tson.http.schema.ApiKeyIn;
import io.ltr8.tson.http.schema.Binding;
import io.ltr8.tson.http.schema.Document;
import io.ltr8.tson.http.schema.Endpoint;
import io.ltr8.tson.http.schema.Exemption;
import io.ltr8.tson.http.schema.HttpAuth;
import io.ltr8.tson.http.schema.HttpProjection;
import io.ltr8.tson.http.schema.HttpVerb;
import io.ltr8.tson.http.schema.Operation;
import io.ltr8.tson.http.schema.Resource;
import io.ltr8.tson.http.schema.SecurityScheme;
import io.ltr8.tson.service.TsonServiceSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code http.tn} -- the vocabulary a schema imports to describe how HTTP exposes a service -- as source text a
 * server can publish, as the bindings that make it usable, and as the reader of a description written in it.
 *
 * <h2>What this is for</h2>
 *
 * <p>A schema importing this one declares http projections whose endpoints bind a protocol's exchanges, carry a
 * signature inline, or answer a document. {@code http.tn} imports {@code service.tn} ({@link TsonServiceSchema}),
 * and a schema's namespace is its own entries and its whole import closure's, so a description importing this schema
 * alone declares its protocols with {@code service.tn}'s names too. Every name {@code http.tn} declares sits under its
 * one root map, {@code http}, as every name {@code service.tn} declares sits under {@code service}, so a description
 * writes each tag and annotation qualified, the exchange facts under {@code service} even before a verb key:
 *
 * <pre>{@code
 * !!import:"https://tson.io/2026/40/io/ltr8/http/http.tn"
 * !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
 * !schema {
 *   orders => !service.protocol { exchanges: { place_order => { request: order  response: order } } }
 *
 *   orders_http => !http.projection {
 *     binds: [orders]
 *     resources: {
 *       "/orders" => { POST => !http.binding { exchange: orders.exchanges.place_order  status: 201 } }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>{@code order} there is a type reference, not a string, and {@code orders} in {@code binds} and {@code
 * orders.exchanges.place_order} are references to values</b> -- a protocol, and an exchange reached through the
 * protocol's entry, its {@code exchanges} field and the exchange's key. The compiler resolves each and refuses a name
 * nothing declares. A description is a schema because others refer into it: a data document may hold references into
 * the schemas it imports, but nothing can refer into a data document, so a binding or a coverage claim naming an
 * exchange of one would carry bare names, and every consumer would need its own resolver for them, in every language
 * that reads it.
 *
 * <h2>Wiring</h2>
 *
 * <p>Two things, and there is nothing else: both schemas reachable from the {@code schemaSource}, which {@link
 * #publishedById()} holds, and the bound classes of both layers in the config's one bind context, beside the
 * application's own. One context binds a schema's value entries and a data document's values alike, so {@link
 * #bindingsWith} merges the maps.
 *
 * <pre>{@code
 * Tson tson = Tson.of(ProcessorConfig.defaults()
 *         .withSchemaAccess(SchemaAccess.of(source))
 *         .withDataBindContext(TsonBindings.of(TsonHttpSchema.bindingsWith(Map.of("order", Order.class)))));
 * tson.resolve(description);
 * TsonServiceDescription described = TsonHttpSchema.describedBy(tson, descriptionId);
 * }</pre>
 */
public final class TsonHttpSchema {

    /** This schema's identity -- the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/40/io/ltr8/http/http.tn";

    private static final String SOURCE = readResource("/http.tn");

    private static final Map<String, Class<?>> OWN = Map.ofEntries(Map.entry("http.projection", HttpProjection.class),
            Map.entry("http.resource", Resource.class), Map.entry("http.endpoint", Endpoint.class),
            Map.entry("http.operation", Operation.class), Map.entry("http.binding", Binding.class),
            Map.entry("http.document", Document.class), Map.entry("http.exemption", Exemption.class),
            Map.entry("http.verb", HttpVerb.class), Map.entry("http.security_scheme", SecurityScheme.class),
            Map.entry("http.auth", HttpAuth.class), Map.entry("http.api_key", ApiKey.class),
            Map.entry("http.api_key_in", ApiKeyIn.class));

    private static final Map<String, Class<?>> BINDINGS = TsonServiceSchema.bindingsWith(OWN);

    private static final Map<String, String> PUBLISHED;

    static {
        Map<String, String> published = new LinkedHashMap<>(TsonServiceSchema.publishedById());
        published.put(ID, SOURCE);
        PUBLISHED = Map.copyOf(published);
    }

    private TsonHttpSchema() {
    }

    /** The schema source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /**
     * Every version of this schema that is still published, current first: {@code http.tn}'s own, not the {@code
     * service.tn} it imports, which {@link TsonServiceSchema#publishedSources()} holds.
     *
     * <p>§3.5 binds a <em>published</em> schema: a shape change is a new name and the superseded document
     * stays served. Nothing here is published, so there is one version and it is edited in place.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE);
    }

    /**
     * Every published version of this schema and of the {@code service.tn} it imports, keyed by identity -- what a
     * schema source needs to resolve a description importing this schema, and what a server publishing one serves
     * beside it, since a client fetching {@code http.tn} fetches its import next. Wiring this rather than {@link
     * #source()} keeps a call site right whichever versions those are.
     */
    public static Map<String, String> publishedById() {
        return PUBLISHED;
    }

    /**
     * Both layers' records by their qualified names, the ones {@link TsonServiceSchema#bindings()} holds and this
     * vocabulary's, mapped to their classes, since a description declares a protocol and projects it in one closure:
     * {@code http.projection}, the one a description's projection entries apply, and what it holds; and the security
     * schemes, {@code http.auth} and {@code http.api_key} under the base {@code http.security_scheme}, whose value
     * entries a projection and an endpoint name. The map type {@code http.resources} needs no class: a field it types
     * binds to an {@code AnnotatedMap} of the value type's class. The map is explicit, so a class in {@code
     * io.ltr8.tson.http.schema} binds only by being named here, and a record no class binds -- {@code
     * http.placement}, folded into each endpoint -- is not listed.
     *
     * <p>A description will not read without them: an entry no class binds is held as a tree, and {@link
     * #describedBy} reads bound records. Bound per schema instead ({@code TsonBindings.bySchema}), this map may stand
     * under this schema's identity and {@link TsonServiceSchema#bindings()} under {@code service.tn}'s.
     */
    public static Map<String, Class<?>> bindings() {
        return BINDINGS;
    }

    /**
     * {@code application}'s bindings and both layers', as the one map a bind context is built from.
     *
     * @throws IllegalArgumentException if {@code application} binds a name either vocabulary binds: within one
     *                                  closure a name means one thing
     */
    public static Map<String, Class<?>> bindingsWith(Map<String, Class<?>> application) {
        Map<String, Class<?>> merged = new LinkedHashMap<>(application);
        OWN.forEach((name, bound) -> {
            if (merged.putIfAbsent(name, bound) != null) {
                throw new IllegalArgumentException("'" + name + "' is bound by the application and by "
                        + "http.tn's vocabulary; within one closure a name means one thing");
            }
        });
        return TsonServiceSchema.bindingsWith(merged);
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
        try (InputStream in = TsonHttpSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
