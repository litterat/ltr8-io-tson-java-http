package io.ltr8.tson.service;

import io.ltr8.tson.service.schema.Exchange;
import io.ltr8.tson.service.schema.Protocol;
import io.ltr8.tson.service.schema.Signature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code service.tn} -- the vocabulary a schema imports to describe what a service can be asked to do -- as source
 * text a server can publish, and as the bindings that make it usable.
 *
 * <h2>What this is for</h2>
 *
 * <p>A schema importing this one declares protocols of exchanges, and the facts each exchange states about itself.
 * Nothing in it names a transport: how HTTP exposes a protocol is {@code http.tn}, which imports this schema and
 * whose holder is {@code tson-http}'s {@code TsonHttpSchema}, so a description using HTTP is wired through that class
 * rather than this one. Every name this layer declares sits under its one root map, {@code service}, so it reserves
 * that one root name only against a root that is no schema map: a nested schema map is open, so another schema's
 * {@code service => !schema { ... }} adds its members to this one, and only a path both declare collides. A
 * description writes each tag and annotation qualified:
 *
 * <pre>{@code
 * !!import:"https://tson.io/2026/40/io/ltr8/http/service.tn"
 * !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
 * !schema {
 *   orders => !service.protocol {
 *     exchanges: { @service.safe get_order => { request: order_ref  response: order } }
 *   }
 * }
 * }</pre>
 *
 * <p><b>{@code order_ref} and {@code order} there are type references, not strings</b>; the compiler resolves each
 * and refuses a name nothing declares. A description is a schema because others refer into it: an exchange is
 * reached from outside as {@code orders.exchanges.get_order}, the protocol's entry, its {@code exchanges} field and
 * the exchange's key, and nothing can refer into a data document.
 *
 * <h2>Wiring</h2>
 *
 * <p>Two things: this schema reachable from the {@code schemaSource}, and the bound classes in {@code
 * io.ltr8.tson.service.schema} in the config's one bind context, beside the application's own. One context binds a
 * schema's value entries and a data document's values alike, so {@link #bindingsWith} merges the two maps. {@link
 * TsonServiceProtocol#join} then joins a protocol the description declares to the Java interface that serves it.
 *
 * <pre>{@code
 * Tson tson = Tson.of(ProcessorConfig.defaults()
 *         .withSchemaAccess(SchemaAccess.of(source))
 *         .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindingsWith(Map.of("order", Order.class)))));
 * tson.resolve(description);
 * }</pre>
 */
public final class TsonServiceSchema {

    /** This schema's identity -- the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/40/io/ltr8/http/service.tn";

    private static final String SOURCE = readResource("/service.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.of("service.protocol", Protocol.class,
            "service.exchange", Exchange.class, "service.signature", Signature.class);

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
     * This vocabulary's records by their qualified names, mapped to the classes in {@code io.ltr8.tson.service.schema}:
     * {@code service.protocol}, the one a description's entries apply, and what it holds, {@code service.exchange}
     * and {@code service.signature}. The namespace type {@code service.exchanges} needs no class: a field it types binds
     * to a {@code Namespace} of the value type's class. The map is explicit, so a class in that package binds only by
     * being named here.
     *
     * <p>A protocol will not join without them: an entry no class binds is held as a tree, and {@link
     * TsonServiceProtocol#join} reads a bound {@link Protocol}.
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
