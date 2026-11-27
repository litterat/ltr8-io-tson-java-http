package io.ltr8.tson.service;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shop examples under {@code shop/} beside this class, protocol only: {@code shop-types.tn} the records,
 * {@code catalog.tn} a schema the protocols name one type of by extern, and {@code shop.tn} the protocols. Nothing
 * here needs {@code tson-http}: the types compose no {@code problem.details}. Nor does anything need {@code http.tn}:
 * {@code shop.tn} imports {@code service.tn} alone, so the schema source holds {@link TsonServiceSchema}'s published
 * documents and the bind context its records, and no HTTP name or class is in reach.
 */
final class ShopExamples {

    static final String BASE = "https://schemas.example.com/2026/40/shop/";
    static final String TYPES = BASE + "shop-types.tn";
    static final String CATALOG = BASE + "catalog.tn";
    static final String SHOP = BASE + "shop.tn";

    /** {@code shop-types.tn}'s canonical identity: the namespace a reader asks the binder for its types within. */
    static final String TYPES_IDENTITY = CanonicalIdentity.canonicalize(TYPES);

    /** The application's bindings: every record the protocols take or answer, by its name. */
    static final Map<String, Class<?>> BINDINGS = Map.of("item", Shop.Item.class, "item_ref", Shop.ItemRef.class,
            "new_item", Shop.NewItem.class, "stock_query", Shop.StockQuery.class, "item_missing",
            Shop.ItemMissing.class, "out_of_stock", Shop.OutOfStock.class, "widget", Shop.Widget.class);

    private ShopExamples() {
    }

    /** A {@code Tson} binding {@link #BINDINGS} and the layer's records, with {@code shop.tn} resolved. */
    static Tson tson() {
        return tson(BINDINGS);
    }

    /** A {@code Tson} binding {@code application} and the layer's records, with {@code shop.tn} resolved. */
    static Tson tson(Map<String, Class<?>> application) {
        return resolved(ProcessorConfig.defaults()
                .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindingsWith(application))));
    }

    /**
     * A {@code Tson} binding {@code application} alone, without the layer's records, with {@code shop.tn} resolved:
     * each protocol entry's value is held as a tree.
     */
    static Tson tsonWithoutLayer(Map<String, Class<?>> application) {
        return resolved(ProcessorConfig.defaults().withDataBindContext(TsonBindings.of(application)));
    }

    private static Tson resolved(ProcessorConfig config) {
        Map<String, String> library = new LinkedHashMap<>(TsonServiceSchema.publishedById());
        library.put(TYPES, source("shop-types.tn"));
        library.put(CATALOG, source("catalog.tn"));
        library.put(SHOP, source("shop.tn"));
        Tson tson = Tson.of(config.withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library))));
        // Resolves and registers shop.tn, and the schemas it depends on, reporting what the resolver refused.
        List<Diagnostic> problems = tson.validateSchema(library.get(SHOP));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("shop.tn does not resolve: " + problems);
        }
        return tson;
    }

    private static String source(String file) {
        try (InputStream in = ShopExamples.class.getResourceAsStream("shop/" + file)) {
            if (in == null) {
                throw new IOException("shop/" + file + " is not beside " + ShopExamples.class.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
