package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The orders examples: real documents governed by {@code meta-service.tn}, one per use of the layer, under
 * {@code orders/} beside this class. {@link OrdersExamplesTest} resolves every one against the shipped layer on each
 * build, so they cannot drift from it; the experiments under {@code experiments/} build on them as the interface
 * their wire form and plans call.
 *
 * <ul>
 * <li>{@code orders-types-1.tn} (shared) -- the request and response records: one request record per method, a
 * one-field record for a parameter-only endpoint.</li>
 * <li>{@code orders-errors-1.tn} (shared) -- business errors composing {@code problem} and pinning {@code status},
 * which is how an endpoint's {@code errors} get one.</li>
 * <li>{@code orders-1.tn} (interface only) -- {@code orders} as a map of methods with a {@code @doc} per method, and
 * {@code orders_v2} beside it declaring only {@code refund}.</li>
 * <li>{@code orders-api-inline-1.tn} (web service only) -- an api with no interface behind it, every endpoint an
 * {@code !operation} with its signature inline; a parameter-only endpoint as a one-field record placed from the
 * path.</li>
 * <li>{@code orders-api-1.tn} (both) -- an api whose entry claims {@code @implements:[orders]}, every endpoint a
 * {@code !binding} with its placement.</li>
 * </ul>
 *
 * <p>Both spellings of a resource appear, deliberately: most write their one required field positionally -- {@code
 * "/orders" => { POST => … }} for {@code "/orders" => { endpoints: { POST => … } }}, which [TSON-SCHEMA] §5.6 admits
 * because {@code resource} has exactly one bare required field -- and one is tagged {@code !resource}.
 *
 * <p>Each file is served at the identity its own {@code !!id} declares, so the examples name each other the way
 * published documents do, literally.
 */
public final class OrdersExamples {

    /** The identity prefix every example declares; {@link #id} completes it. */
    public static final String BASE = "https://schemas.example.com/2026/38/orders/";

    /** Every example, dependencies first. */
    public static final List<String> FILES = List.of("orders-types-1.tn", "orders-errors-1.tn", "orders-1.tn",
            "orders-api-inline-1.tn", "orders-api-1.tn");

    private OrdersExamples() {
    }

    /** The identity {@code file} declares. */
    public static String id(String file) {
        return BASE + file;
    }

    /** The source text of one example. */
    public static String source(String file) {
        try (InputStream in = OrdersExamples.class.getResourceAsStream("orders/" + file)) {
            if (in == null) {
                throw new IOException("orders/" + file + " is not beside " + OrdersExamples.class.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every example by its identity, with the meta layer and {@code problem.tn} they are governed by and import: what
     * a schema source needs to resolve any of them. Mutable, so a caller can add what it resolves beside them.
     */
    public static Map<String, String> library() {
        Map<String, String> lib = new LinkedHashMap<>(TsonApiSchema.publishedById());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        FILES.forEach(file -> lib.put(id(file), source(file)));
        return lib;
    }

    /** A {@code Tson} that resolves {@code library} and binds the layer's vocabulary. */
    public static Tson tson(Map<String, String> library) {
        return Tson.of(bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library)))));
    }

    /** The bound classes for the layer's records, on {@code config}. */
    public static ProcessorConfig bindVocabulary(ProcessorConfig config) {
        return config.withMetaNameBinder(TsonApiSchema.metaNameBinder());
    }

    /**
     * Every entry applying the namespace type {@code constructor} -- {@code interface} or {@code api} -- by its name,
     * each as the {@code Namespace} it is: an interface's keys are its methods, an api's its paths.
     */
    public static Map<String, Namespace<?>> applying(Namespace<Top> entries, String constructor) {
        Map<String, Namespace<?>> found = new LinkedHashMap<>();
        entries.forEach((name, value) -> {
            if (value instanceof Namespace<?> namespace && namespace.type().equals(constructor)) {
                found.put(name, namespace);
            }
        });
        return found;
    }
}
