package io.ltr8.tson.http;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two versions of one schema, served side by side. §10 makes a published schema immutable, so a shape change is
 * a new document under a new name -- which means versions coexist rather than replace each other, and a server
 * that outlives one of its clients has to serve both.
 */
class TsonSchemaVersionsTest {

    private static final String V1_ID = "https://schemas.example.com/2026/32/app/order-1.tn";
    private static final String V2_ID = "https://schemas.example.com/2026/32/app/order-2.tn";

    private static final String V1 = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: text  quantity: int32 } }""";

    private static final String V2 = """
            !!id:"https://schemas.example.com/2026/32/app/order-2.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: text  quantity: int32  currency: text } }""";

    private static final Map<String, String> SCHEMAS = Map.of(V1_ID, V1, V2_ID, V2);
    private static final TsonSchemaSource SOURCE = SCHEMAS::get;

    /** v1's shape exactly. */
    @Typename(name = "order")
    public record OrderV1(String sku, int quantity) {
    }

    /** v2's shape exactly. */
    @Typename(name = "order")
    public record OrderV2(String sku, int quantity, String currency) {
    }

    /**
     * One class for both versions: a field for everything any version has, nullable for what is not in all of
     * them. The second constructor is here to demonstrate that binding ignores it -- see
     * {@link #bindingNeverSelectsAVersionSpecificConstructor}.
     */
    @Typename(name = "order")
    public record AnyOrder(String sku, int quantity, String currency) {
        @Record
        public AnyOrder {
        }

        /** The v1 shape. Stamps a marker so a test can see whether binding ever picks it. */
        public AnyOrder(String sku, int quantity) {
            this(sku, quantity, "PICKED-BY-CONSTRUCTOR");
        }
    }

    private TsonSchemaVersions versions;

    @BeforeEach
    void setUp() {
        versions = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", OrderV1.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", OrderV2.class))
                .build();
    }

    private static InputStream body(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    private static String order(String schemaId, String fields) {
        return "!!schema:\"" + schemaId + "\"\n!order " + fields;
    }

    @Test
    void routesEachDocumentToTheCodecForTheVersionItDeclares() {
        var v1 = versions.route(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")));
        assertEquals(V1_ID, v1.schemaId());
        assertEquals(new OrderV1("A", 1),
                v1.codec().readObject(v1.body(), "application/tson", OrderV1.class));

        var v2 = versions.route(body(order(V2_ID, "{ sku: \"B\" quantity: 2 currency: \"AUD\" }")));
        assertEquals(V2_ID, v2.schemaId());
        assertEquals(new OrderV2("B", 2, "AUD"),
                v2.codec().readObject(v2.body(), "application/tson", OrderV2.class));
    }

    /** Each version validates against its own schema: v2's currency is required, v1 has no such field. */
    @Test
    void eachVersionEnforcesItsOwnSchema() {
        var missing = versions.route(body(order(V2_ID, "{ sku: \"B\" quantity: 2 }")));
        TsonHttpException rejected = assertThrows(TsonHttpException.class,
                () -> missing.codec().readObject(missing.body(), "application/tson", OrderV2.class));
        assertEquals(TsonHttpException.BAD_REQUEST, rejected.status());
        assertTrue(rejected.getMessage().contains("problem"), rejected.getMessage());

        var extra = versions.route(body(order(V1_ID, "{ sku: \"A\" quantity: 1 currency: \"AUD\" }")));
        assertThrows(TsonHttpException.class,
                () -> extra.codec().readObject(extra.body(), "application/tson", OrderV1.class));
    }

    /**
     * <b>The reason routing exists.</b> A v1 codec handed a v2 document reads it against v2's schema and binds
     * it to a class with no {@code currency} component -- returning {@code OrderV1[sku=A, quantity=1]} with the
     * field gone, no exception and no diagnostic ({@code UPSTREAM.md} #10). For an order that is the wrong
     * currency rather than a rejected request.
     *
     * <p>Pinned as the hazard it is, so that if upstream starts reporting it this test fails and says so.
     */
    @Test
    void aCodecFromTheWrongVersionSilentlyDropsFieldsItsClassLacks() {
        TsonHttpCodec v1Only = versions.codecFor(V1_ID);

        OrderV1 read = v1Only.readObject(body(order(V2_ID, "{ sku: \"A\" quantity: 1 currency: \"AUD\" }")),
                "application/tson", OrderV1.class);

        assertEquals(new OrderV1("A", 1), read, "the currency is gone, and nothing said so");
    }

    /** And the guard: routing refuses before that can happen. */
    @Test
    void routingRefusesAVersionThisEndpointDoesNotServe() {
        String unknown = "https://schemas.example.com/2026/32/app/order-3.tn";
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> versions.route(body(order(unknown, "{ sku: \"A\" quantity: 1 }"))));
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        assertTrue(refused.getMessage().contains("order-3"), refused.getMessage());
        assertTrue(refused.getMessage().contains("order-1"), "and says what it does serve");
    }

    /** A document naming no version is refused rather than assigned one -- see defaultVersion's own note. */
    @Test
    void routingRefusesADocumentThatNamesNoVersion() {
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> versions.route(body("!order { sku: \"A\" quantity: 1 }")));
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        assertTrue(refused.getMessage().contains("!!schema"), refused.getMessage());
    }

    @Test
    void anExplicitDefaultVersionIsUsedWhenADocumentNamesNone() {
        TsonSchemaVersions withDefault = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", OrderV1.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", OrderV2.class))
                .defaultVersion(V1_ID)
                .build();

        var routed = withDefault.route(body("!order { sku: \"A\" quantity: 1 }"));
        assertEquals(V1_ID, routed.schemaId());
        assertEquals(new OrderV1("A", 1),
                routed.codec().readObject(routed.body(), "application/tson", OrderV1.class));
    }

    /** §2.2.1: scheme and a ?sha256= pin are not identity, so a reference carrying either still routes. */
    @Test
    void routesByCanonicalIdentityNotByReferenceSpelling() {
        var pinned = versions.route(body(order(V1_ID + "?sha256=abc123", "{ sku: \"A\" quantity: 1 }")));
        assertEquals(V1_ID, pinned.schemaId(),
                "the registered id, not the pinned reference the client wrote -- switching on it must be stable");
        assertEquals(new OrderV1("A", 1),
                pinned.codec().readObject(pinned.body(), "application/tson", OrderV1.class));

        var httpScheme = versions.route(body(order(V1_ID.replace("https://", "http://"),
                "{ sku: \"A\" quantity: 1 }")));
        assertEquals(versions.codecFor(V1_ID), httpScheme.codec());
    }

    /** One class across versions: a field the governing schema does not declare arrives null. */
    @Test
    void oneClassCanServeEveryVersionWithNullForWhatAVersionLacks() {
        TsonSchemaVersions shared = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", AnyOrder.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", AnyOrder.class))
                .build();

        var v1 = shared.route(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")));
        AnyOrder fromV1 = v1.codec().readObject(v1.body(), "application/tson", AnyOrder.class);
        assertEquals("A", fromV1.sku());
        assertNull(fromV1.currency(), "v1 declares no currency, so it binds absent");

        var v2 = shared.route(body(order(V2_ID, "{ sku: \"B\" quantity: 2 currency: \"AUD\" }")));
        assertEquals("AUD", v2.codec().readObject(v2.body(), "application/tson", AnyOrder.class).currency());
    }

    /**
     * The tempting model that does not work: a constructor per version, expecting the binder to choose.
     * Binding always uses the canonical one -- the sole public constructor, or the {@code @Record} one -- and
     * passes null for a field the schema does not declare. The marker the two-argument constructor stamps never
     * appears, which is the proof it is never called.
     */
    @Test
    void bindingNeverSelectsAVersionSpecificConstructor() {
        TsonSchemaVersions shared = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", AnyOrder.class))
                .build();

        var routed = shared.route(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")));
        AnyOrder read = routed.codec().readObject(routed.body(), "application/tson", AnyOrder.class);

        assertNull(read.currency(), "the canonical constructor ran with null, not the two-argument one");
    }

    /**
     * Tree mode has none of this difficulty: no classes, so one Tson holds every version and each document is
     * validated against the one it names. Worth stating, because reaching for this class in tree mode is
     * solving a problem you do not have.
     */
    @Test
    void treeModeNeedsNoneOfThis() {
        Tson tson = Tson.builder().schemaSource(SOURCE).build();
        tson.resolve(V1);
        tson.resolve(V2);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        TsonValue v1 = codec.readTree(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")), "application/tson");
        assertEquals("A", v1.get("sku").asString().orElseThrow());

        TsonValue v2 = codec.readTree(body(order(V2_ID, "{ sku: \"B\" quantity: 2 currency: \"AUD\" }")),
                "application/tson");
        assertEquals("AUD", v2.get("currency").asString().orElseThrow());

        assertThrows(TsonHttpException.class,
                () -> codec.readTree(body(order(V2_ID, "{ sku: \"B\" quantity: 2 }")), "application/tson"));
    }

    /** The failure a single shared binder gives, so the reason for a context per version stays visible. */
    @Test
    void oneBinderCannotServeTwoVersionsOfOneTypeName() {
        DataNameBinder binder = name -> "order".equals(name) ? OrderV1.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        Tson tson = Tson.builder().schemaSource(SOURCE).dataBindContext(context).build();
        tson.resolve(V1);
        tson.resolve(V2);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        TsonHttpException failed = assertThrows(TsonHttpException.class, () -> codec.readObject(
                body(order(V2_ID, "{ sku: \"B\" quantity: 2 currency: \"AUD\" }")), "application/tson",
                OrderV2.class));
        assertEquals(TsonHttpException.BAD_REQUEST, failed.status());
        // The clash is reported, not silently resolved -- which is the saving grace of this configuration.
        assertTrue(failed.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("not assignable")),
                "expected a not-assignable diagnostic, got: " + failed.diagnostics());
    }
}
