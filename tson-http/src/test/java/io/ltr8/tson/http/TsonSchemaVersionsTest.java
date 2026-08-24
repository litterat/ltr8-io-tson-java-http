package io.ltr8.tson.http;

import io.ltr8.annotation.Profile;
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
import java.util.List;
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

    private static final String V1_PROFILE = "orders-1";
    private static final String V2_PROFILE = "orders-2";

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
     * One class for both versions, holding the union of their fields with a constructor per version.
     *
     * <p>The v1 constructor supplies the default for {@code currency}, which v1 does not carry, so the class
     * is complete whichever document built it. <b>{@code fields} is required here</b>: a secondary
     * constructor's parameter names are {@code arg0}/{@code arg1} in the class file unless compiled with
     * {@code -parameters}, and this is how to supply them without that flag.
     *
     * <p>v2 needs no annotation at all — its shape <em>is</em> this class's canonical constructor, and a
     * profile with no constructor of its own falls back to that one.
     */
    @Typename(name = "order")
    public record AnyOrder(String sku, int quantity, String currency) {

        @Profile(value = V1_PROFILE, fields = {"sku", "quantity"})
        public AnyOrder(String sku, int quantity) {
            this(sku, quantity, "AUD");
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
     * <b>The reason routing exists, and it is no longer silent.</b> A v1 codec handed a v2 document used to
     * bind it to a class with no {@code currency} component and return {@code OrderV1[sku=A, quantity=1]}
     * with the field gone — no exception, no diagnostic ({@code UPSTREAM.md} #10). For an order that is the
     * wrong currency rather than a rejected request.
     *
     * <p>Strict binding refuses it instead. Routing is still the guard — this codec should never have seen
     * this document — but the library no longer quietly does the wrong thing when the guard is bypassed.
     */
    @Test
    void aCodecFromTheWrongVersionIsRefusedRatherThanDroppingFieldsSilently() {
        TsonHttpCodec v1Only = versions.codecFor(V1_ID);

        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> v1Only.readObject(body(order(V2_ID, "{ sku: \"A\" quantity: 1 currency: \"AUD\" }")),
                        "application/tson", OrderV1.class));

        // 500, not 400: the document is valid v2 and this codec should never have seen it, so the fault is
        // this server's. The adapter boundary drops the detail and the diagnostics from any 5xx body, which
        // is what keeps the class name off the wire -- it is here for the log.
        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, refused.status());
        assertTrue(refused.diagnostics().stream()
                        .anyMatch(d -> d.code() == io.ltr8.tson.compiler.Diagnostic.Code.BIND_MISMATCH),
                () -> "expected a BIND_MISMATCH diagnostic, got " + refused.diagnostics());
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

    /**
     * <b>One class serving every version, which binding profiles unblocked.</b> {@link AnyOrder} holds the
     * union of both versions' fields; the profile on each version's {@code DataBindContext} picks the
     * constructor that matches that version's schema, and strict binding then checks the one it picked. The
     * two mechanisms have to hold together: selection without checking binds whatever it chose, and checking
     * without selection has only one shape to check.
     */
    @Test
    void oneClassServesEveryVersionThroughABindingProfile() {
        TsonSchemaVersions shared = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", AnyOrder.class), V1_PROFILE)
                .version(V2_ID, V2, SOURCE, Map.of("order", AnyOrder.class), V2_PROFILE)
                .build();

        var v1 = shared.route(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")));
        assertEquals(new AnyOrder("A", 1, "AUD"),
                v1.codec().readObject(v1.body(), "application/tson", AnyOrder.class),
                "v1's constructor ran and supplied the default for the field its version lacks");

        var v2 = shared.route(body(order(V2_ID, "{ sku: \"B\" quantity: 2 currency: \"NZD\" }")));
        assertEquals(new AnyOrder("B", 2, "NZD"),
                v2.codec().readObject(v2.body(), "application/tson", AnyOrder.class),
                "v2's shape is the canonical constructor, which serves a profile that names none");

        assertEquals(new AnyOrder("A", 1, "AUD"),
                v1.codec().readObject(body(order(V1_ID, "{ sku: \"A\" quantity: 1 }")),
                        "application/tson", AnyOrder.class),
                "and the older reader is unchanged by the newer one having run");
    }

    /**
     * <b>Without a profile the same class is refused, which is the safety half.</b> The union is a shape no
     * version's schema declares, so strict binding rejects it at startup rather than half-filling it. That
     * refusal is what makes the profile a decision rather than a default.
     */
    @Test
    void theSameClassWithoutAProfileIsRefusedAtStartup() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> TsonSchemaVersions.builder()
                        .version(V1_ID, V1, SOURCE, Map.of("order", AnyOrder.class))
                        .build());

        assertTrue(refused.getMessage().contains("currency"), refused.getMessage());
        assertTrue(refused.getMessage().contains(V1_ID), refused.getMessage());
    }

    /**
     * <b>And a profile pointed at the wrong version fails rather than binding the other one's constructor.</b>
     * Were another profile's constructor eligible, this would have found v1's, matched it against v2's schema
     * and bound a v1 shape under a v2 profile with nothing to say about it.
     */
    @Test
    void aProfilePointedAtTheWrongVersionFails() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> TsonSchemaVersions.builder()
                        .version(V2_ID, V2, SOURCE, Map.of("order", AnyOrder.class), V1_PROFILE)
                        .build());

        assertTrue(refused.getMessage().contains("currency"), refused.getMessage());
    }

    /**
     * <b>Multiple constructors still do not select a version</b>, measured against the version whose shape
     * {@link AnyOrder} does match. Binding always uses the canonical constructor — the sole public one, or
     * the {@code @Record}-annotated one — so the marker the two-argument constructor stamps never appears.
     * A second constructor is for your own code and is invisible to binding.
     */
    @Test
    void bindingNeverSelectsAVersionSpecificConstructor() {
        TsonSchemaVersions shared = TsonSchemaVersions.builder()
                .version(V2_ID, V2, SOURCE, Map.of("order", AnyOrder.class))
                .build();

        var routed = shared.route(body(order(V2_ID, "{ sku: \"A\" quantity: 1 currency: \"AUD\" }")));
        AnyOrder read = routed.codec().readObject(routed.body(), "application/tson", AnyOrder.class);

        assertEquals("AUD", read.currency(), "the canonical constructor ran, not the two-argument one");
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
        // A misconfiguration of this server, so 5xx -- the client's v2 document is entirely valid.
        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, failed.status());
        // The clash is reported, not silently resolved -- which is the saving grace of this configuration.
        // The clash is reported, not silently resolved. Strict binding names both sides and what to do:
        // "'order' and OrderV1 do not agree: no component for field 'currency'."
        assertTrue(failed.diagnostics().stream().anyMatch(d -> d.message().contains("do not agree")
                        && d.message().contains("currency")),
                "expected a bind-disagreement diagnostic, got: " + failed.diagnostics());
    }
    // ── choosing the version to answer in ──

    /**
     * <b>Saying nothing is the ordinary case</b>, and gets the newest registered. That is what keeps this
     * additive: a client that has never heard of the field keeps working exactly as before.
     */
    @Test
    void aRequestExpressingNoPreferenceGetsThePreferredVersion() {
        assertEquals(V2_ID, versions.chooseResponseVersion(null));
        assertEquals(V2_ID, versions.chooseResponseVersion(""));
        assertEquals(V2_ID, versions.preferredResponseVersion(), "the last version registered");
    }

    /** Declaring the preference explicitly overrides registration order. */
    @Test
    void thePreferredVersionCanBeDeclared() {
        TsonSchemaVersions oldestPreferred = TsonSchemaVersions.builder()
                .version(V1_ID, V1, SOURCE, Map.of("order", OrderV1.class))
                .version(V2_ID, V2, SOURCE, Map.of("order", OrderV2.class))
                .preferredResponseVersion(V1_ID)
                .build();

        assertEquals(V1_ID, oldestPreferred.chooseResponseVersion(null));
    }

    /** A client that names one version gets it, matched by canonical identity rather than by spelling. */
    @Test
    void aNamedVersionIsChosen() {
        assertEquals(V1_ID, versions.chooseResponseVersion(TsonAcceptSchemaHeader.format(List.of(V1_ID))));

        // §2.2.1: the scheme is a transport hint and a ?sha256= pin is not part of the name.
        assertEquals(V1_ID, versions.chooseResponseVersion(
                TsonAcceptSchemaHeader.format(List.of(V1_ID.replace("https://", "http://") + "?sha256=abc"))));
    }

    /** Quality values order the choice, and the client's own order breaks a tie. */
    @Test
    void qualityValuesOrderTheChoice() {
        assertEquals(V1_ID, versions.chooseResponseVersion(
                "\"" + V2_ID + "\";q=0.2, \"" + V1_ID + "\";q=0.9"));
        assertEquals(V2_ID, versions.chooseResponseVersion(
                "\"" + V2_ID + "\", \"" + V1_ID + "\""), "equal quality keeps the order written");
    }

    /** {@code q=0} refuses a version rather than merely ranking it low. */
    @Test
    void zeroQualityRefusesAVersion() {
        assertEquals(V1_ID, versions.chooseResponseVersion(
                "\"" + V2_ID + "\";q=0, \"" + V1_ID + "\""));
    }

    /** A version this endpoint does not serve is ignored, so the client's other choices still count. */
    @Test
    void anUnknownVersionDoesNotSpoilTheRest() {
        assertEquals(V1_ID, versions.chooseResponseVersion(
                "\"https://schemas.example.com/2026/32/app/order-9.tn\", \"" + V1_ID + "\""));
    }

    /**
     * <b>Nothing acceptable is a 406, not a fallback.</b> Answering in a version the client said it cannot
     * read would be worse than refusing: the client gets a body it will fail to parse, at a status that says
     * everything went well.
     */
    @Test
    void nothingAcceptableIs406() {
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> versions.chooseResponseVersion(
                "\"https://schemas.example.com/2026/32/app/order-9.tn\""));

        assertEquals(TsonHttpException.NOT_ACCEPTABLE, refused.status());
        assertTrue(refused.getMessage().contains("order-1"), refused.getMessage());
    }

    /** A malformed field is the client's error, and is not silently treated as "any". */
    @Test
    void aMalformedAcceptSchemaFieldIsRefused() {
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> versions.chooseResponseVersion("https://unquoted.example/order-1.tn"));

        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        assertTrue(refused.getMessage().contains("sf-list"), refused.getMessage());
    }

}
