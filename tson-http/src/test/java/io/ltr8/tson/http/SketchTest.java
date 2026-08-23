package io.ltr8.tson.http;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sketch/} schemas, held to what {@code sketch/README.md} claims about them.
 *
 * <p>Two designs for an API description made of types rather than data about types. One works today and one
 * is blocked; this asserts which is which, so a fix upstream shows up as a <em>failing</em> test rather than as
 * nothing happening.
 */
class SketchTest {

    private static String sketch(String name) throws Exception {
        return Files.readString(Path.of("..", "sketch", name));
    }

    private static Optional<String> value(Annotations annotations, String name) {
        return annotations.values().stream().filter(a -> a.name().equals(name)).findFirst()
                .flatMap(Annotation::value).map(Object::toString);
    }

    // ── the design that works: annotations in a meta layer, operations as `top &` entries ──

    private static Tson resolved() throws Exception {
        Map<String, String> lib = Map.of("https://tson.io/2026/32/ltr8/http/meta-http-2.tn",
                sketch("meta-http-2.tn"));
        Tson tson = Tson.builder().schemaSource(lib::get).build();
        tson.resolve(sketch("orders-api-2.tn"));
        return tson;
    }

    /**
     * The whole claim: an API model can be read back out of an ordinary resolved schema, with every payload a
     * real type reference rather than a string.
     */
    @Test
    void theApiModelIsReadableFromTheResolvedSchema() throws Exception {
        var entries = resolved().schemaRegistry()
                .get("https://schemas.example.com/2026/32/app/orders-api-2.tn").orElseThrow()
                .schema().entries();

        TypeDefinition create = entries.get("create_order");
        assertEquals(Optional.of("POST"), value(create.annotations(), "method"));
        assertEquals(Optional.of("/orders"), value(create.annotations(), "path"));
        assertEquals(List.of("top"), create.supertypes(), "no data value can be an operation");

        List<String> responses = new ArrayList<>();
        RecordBody body = (RecordBody) create.body();
        body.fields().forEach(f -> value(f.annotations(), "status")
                .ifPresent(status -> responses.add(status + " -> " + f.type().name())));

        assertEquals(List.of("201 -> order", "400 -> problem", "404 -> sku_not_found"), responses);

        // The request is a resolved reference, not a name that happens to look like one.
        assertEquals("order", body.fields().stream().filter(f -> f.name().equals("request"))
                .findFirst().orElseThrow().type().name());
    }

    /** And the property a data-shaped description cannot have: a payload type that does not exist is refused. */
    @Test
    void aResponseNamingATypeThatDoesNotExistFailsToLoad() throws Exception {
        String broken = sketch("orders-api-2.tn")
                .replace("@status:404 no_such_sku: sku_not_found", "@status:404 no_such_sku: sku_not_fund");
        Map<String, String> lib = Map.of("https://tson.io/2026/32/ltr8/http/meta-http-2.tn",
                sketch("meta-http-2.tn"));

        var problems = Tson.builder().schemaSource(lib::get).build().validateSchema(broken);

        assertTrue(problems.stream().anyMatch(d -> d.message().contains("sku_not_fund")),
                () -> "expected an unresolved-reference error, got " + problems);
    }

    // ── the plainest design: an ordinary schema, no annotations, no top, no meta layer ──

    /**
     * {@code orders-api-3.tn} needs nothing but the ordinary header — {@code meta.tn} governing,
     * {@code core.tn} imported. Metadata is carried by FIXED fields, which survive into resolver output with
     * their values, where a locally declared annotation's value would have been dropped (#12).
     */
    /**
     * The schemas {@code orders-api-3.tn} imports — a library, which is what #11's fix bought. Every one is a
     * real file in {@code sketch/}, so the whole set is readable without going through this test.
     */
    private static Map<String, String> ordersLibrary() throws Exception {
        return Map.of(
                "https://tson.io/2026/32/ltr8/http/http-api-1.tn", sketch("http-api-1.tn"),
                "https://tson.io/2026/32/ltr8/http/problem-2.tn", TsonProblemSchema.source(),
                "https://schemas.example.com/2026/32/app/order-1.tn", sketch("order-1.tn"),
                "https://schemas.example.com/2026/32/app/orders-errors-1.tn", sketch("orders-errors-1.tn"));
    }

    @Test
    void fixedFieldsCarryTheMetadataInAnOrdinarySchema() throws Exception {
        Tson tson = Tson.builder().schemaSource(ordersLibrary()::get).build();
        var entries = tson.resolve(sketch("orders-api-3.tn")).schema().entries();

        TypeDefinition create = entries.get("create_order");
        assertTrue(create.supertypes().contains("operation"),
                "an operation is found by its supertype, not by a naming convention");

        Map<String, RecordField> fields = new LinkedHashMap<>();
        ((RecordBody) create.body()).fields().forEach(f -> fields.put(f.name(), f));

        assertEquals(FieldState.REQUIRED_FIXED, fields.get("method").state());
        assertEquals("POST", fields.get("method").value().orElseThrow().text());
        assertEquals("/orders", fields.get("path").value().orElseThrow().text());

        // The payloads are resolved references, which is the whole point.
        assertEquals("order", fields.get("request").type().name());
        assertTrue(fields.get("response").type().name().startsWith("choice_"),
                fields.get("response").type().name());
    }

    /**
     * The response shape is a template applied per response, so it is declared once and cannot drift.
     * {@code S} is a <em>value</em> parameter filling a FIXED field, which is what makes it possible at all —
     * without it a status would have to be fixed per declaration and every response would be a record again.
     */
    @Test
    void aTemplateCarriesTheResponseShape() throws Exception {
        var entries = Tson.builder().schemaSource(ordersLibrary()::get).build()
                .resolve(sketch("orders-api-3.tn")).schema().entries();

        // Each response is a one-line application, so its entry is a reference to the materialised
        // instantiation -- which is the shape the template declared, with S substituted.
        Map<String, RecordField> created = fieldsOf(entries, "order_created");
        assertEquals("201", created.get("status").value().orElseThrow().text());
        assertEquals("order", created.get("body").type().name());

        // And it nests: response<page<order>, 200>.
        Map<String, RecordField> paged = fieldsOf(entries, "order_page");
        assertEquals("200", paged.get("status").value().orElseThrow().text());
        assertTrue(paged.get("body").type().name().contains("page"), paged.get("body").type().name());
    }

    /**
     * An entry's fields, following one hop through the reference a template application resolves to.
     */
    private static Map<String, RecordField> fieldsOf(Map<String, TypeDefinition> entries, String name) {
        TypeDefinition entry = entries.get(name);
        // An application resolves to a Reference at the author's name, pointing at the materialised
        // instantiation entry, which is auto-named after the template and its arguments.
        if (entry.body() instanceof io.ltr8.tson.schema.meta.Reference reference) {
            entry = entries.get(reference.target().name());
        }
        Map<String, RecordField> fields = new LinkedHashMap<>();
        ((RecordBody) entry.body()).fields().forEach(f -> fields.put(f.name(), f));
        return fields;
    }

    /**
     * <b>The template documents the status; it does not yet enforce it</b> ({@code UPSTREAM.md} #14). A value
     * parameter filling a FIXED field materialises with the value but not the FIXED state, so a document may
     * send any status. The literal form does enforce it, which is what makes this a gap rather than a rule.
     *
     * <p>Asserted as it currently behaves, so a fix upstream makes this fail and the sketch's caveat can go.
     */
    @Test
    void aValueParameterFixedFieldDoesNotYetConstrain() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                    order => { sku: text }
                    by_param   => <T, S> { status: int32 = S  body: T }
                    by_literal => { status: int32 = 201  body: order }
                    created    => by_param<order, 201>
                }""";
        Tson tson = Tson.builder().schemaSource(u -> schema).build();
        tson.resolve(schema);
        String header = "!!schema:\"https://s.example.com/2026/32/p-1.tn\"\n";

        assertTrue(tson.validate(header + "!by_literal { status: 999  body: !order { sku: \"a\" } }").stream()
                        .anyMatch(d -> d.code() == io.ltr8.tson.compiler.Diagnostic.Code.FIELD_FIXED),
                "a literal FIXED field rejects another value");

        assertEquals(List.of(),
                tson.validate(header + "!created { status: 999  body: !order { sku: \"a\" } }"),
                "and a parameter-filled one does not -- which is UPSTREAM #14, not intended behaviour");
    }

    /**
     * The gap that shapes the sketch: a template application cannot appear directly inside a choice, so each
     * response is named as an entry first. {@code UnsupportedOperationException} is the not-implemented
     * classification, so when this lands the sketch can inline them and this test should fail.
     */
    @Test
    void anApplicationInsideAChoiceIsNotImplemented() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                    order => { sku: text }
                    problem => { title: text }
                    resp => <T, S> { status: int32 = S  body: T }
                    op => { response: (resp<order, 201> | resp<problem, 400>) }
                }""";

        UnsupportedOperationException gap = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> Tson.builder().schemaSource(u -> null).build().validateSchema(schema));
        assertTrue(gap.getMessage().contains("must be lifted to an entry"), gap.getMessage());
    }

    /** And the same property: a payload type that does not exist is refused. */
    @Test
    void anOrdinarySchemaStillChecksItsPayloadTypes() throws Exception {
        String broken = sketch("orders-api-3.tn")
                .replace("response<sku_not_found, 404>", "response<sku_not_fund, 404>");
        var problems = Tson.builder().schemaSource(ordersLibrary()::get).build().validateSchema(broken);
        assertTrue(problems.stream().anyMatch(d -> d.message().contains("sku_not_fund")),
                () -> "expected an unresolved-reference error, got " + problems);
    }

    // ── the fourth design: the API as a data document, with resolution done here ──

    /**
     * {@code orders-api-4.tn} is the only one of the four that ships — it is a <em>data</em> document, governed
     * by {@code api-2.tn}, so it needs nothing of the type system the others need. This asserts both halves:
     * that it reads (the schema accepts its shape) and that its bare type names resolve against its own
     * {@code imports} (the check {@link TsonApi#validate} does, because the resolver will not).
     */
    @Test
    void theDataDescriptionReadsAndItsNamesResolve() throws Exception {
        TsonApi api = TsonApi.read(sketch("orders-api-4.tn"));

        assertEquals("Orders", api.api().title());
        assertEquals(2, api.operations().size());
        assertEquals(3, api.referencedSchemas().size());

        Tson tson = Tson.builder().schemaSource(ordersLibrary()::get).build();
        assertEquals(List.of(), api.validate(tson),
                "every type name must resolve against the description's own imports");
    }

    /** And the check has teeth: a name no import declares is reported, with where it was written. */
    @Test
    void aTypoInTheDataDescriptionIsReported() throws Exception {
        TsonApi api = TsonApi.read(sketch("orders-api-4.tn").replace("body: \"sku_not_found\"",
                "body: \"sku_not_fund\""));

        var problems = api.validate(Tson.builder().schemaSource(ordersLibrary()::get).build());

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("sku_not_fund"), problems.getFirst().message());
        assertTrue(problems.getFirst().message().contains("POST /orders response 404"),
                () -> "and says where: " + problems.getFirst().message());
    }

    /**
     * <b>Who checks the description's own shape.</b> The answer inverts the obvious reading: the <em>data</em>
     * design gets far more help from the compiler than the schema design does.
     *
     * <p>A data description is validated against {@code api-2.tn}, which fully describes it — so a misspelled
     * field, an out-of-range status and a bogus method are all caught, with diagnostics. A schema description
     * <em>is</em> a schema, and nothing describes what an operation must look like: the meta-schema says what a
     * schema is in general. Composition with an {@code operation} base requires {@code method} and {@code path}
     * to exist, and stops there.
     *
     * <p>This is the sharpest argument for {@code UPSTREAM.md} #15: a {@code ~operation} constructor is what
     * would let the schema design have both.
     */
    @Test
    void theDataDesignIsStructurallyCheckedAndTheSchemaDesignIsNot() throws Exception {
        // Schema design: a typo in a field the operation base declares, and a missing response. Neither
        // is caught -- any record composing `operation` is an operation.
        String typo = sketch("orders-api-3.tn")
                .replace("    method:   http_method = POST", "    methd:    http_method = POST");
        assertEquals(List.of(), Tson.builder().schemaSource(ordersLibrary()::get).build().validateSchema(typo),
                "nothing describes an operation's shape, so nothing catches this");

        String noResponse = sketch("orders-api-3.tn")
                .replace("    response: (order_created | order_invalid | order_sku_gone)\n", "");
        assertEquals(List.of(),
                Tson.builder().schemaSource(ordersLibrary()::get).build().validateSchema(noResponse),
                "and an operation with no response at all is equally fine");

        // Data design: the same class of mistake, caught by the reader with a real diagnostic.
        assertRejected("a misspelled field", sketch("orders-api-4.tn")
                .replace("method:     POST", "methd:      POST"), Diagnostic.Code.UNRECOGNIZED_FIELD);
        assertRejected("a status outside 100..599", sketch("orders-api-4.tn")
                .replace("status: 201", "status: 42"), Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION);
        assertRejected("a method that is not one", sketch("orders-api-4.tn")
                .replace("method:     POST", "method:     POZT"), Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION);
    }

    private static void assertRejected(String what, String description, Diagnostic.Code expected) {
        TsonHttpException rejected = org.junit.jupiter.api.Assertions.assertThrows(TsonHttpException.class,
                () -> TsonApi.read(description), what);
        assertEquals(expected, rejected.diagnostics().getFirst().code(),
                () -> what + " -> " + rejected.diagnostics());
    }

    // ── the design that is blocked: an `operation` type constructor in a meta layer ──

    /**
     * Why {@code meta-http-1.tn} declares {@code operation} with {@code ~} rather than as a plain record.
     *
     * <p><b>Governance does not put names into the type-name namespace.</b> A plain record in a meta layer
     * cannot be composed by a schema that layer governs — the name is simply not in scope. It becomes usable
     * only by <em>importing</em> the meta layer as well, at which point governance has contributed nothing and
     * the same record in an ordinary imported schema would have done: which is what {@code http-api-1.tn} is.
     *
     * <p>So a meta layer earns its place only through what governance actually supplies — the structure
     * namespace ({@code !C} application, gated on {@code constructor: true}) and annotation types whose values
     * bind. A plain record uses neither.
     */
    @Test
    void aPlainRecordInAMetaLayerIsNotReachableByGovernanceAlone() throws Exception {
        String meta = """
                !!id:"https://s.example.com/2026/32/m-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/32/m/meta-kernel.tn"
                { plain_operation => { path: text } }""";
        Map<String, String> lib = Map.of("https://s.example.com/2026/32/m-1.tn", meta);
        String governed = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://s.example.com/2026/32/m-1.tn"
                !!import:"https://tson.io/2026/32/m/meta-kernel.tn"
                %s{ op => plain_operation & { a: text } }""";

        var withoutImport = Tson.builder().schemaSource(lib::get).build()
                .validateSchema(governed.formatted(""));
        assertTrue(withoutImport.stream().anyMatch(d -> d.message().contains("names no type")),
                () -> "governance alone must not bring the name into scope, got " + withoutImport);

        var withImport = Tson.builder().schemaSource(lib::get).build().validateSchema(
                governed.formatted("!!import:\"https://s.example.com/2026/32/m-1.tn\"\n"));
        assertEquals(List.of(), withImport, "importing it works -- which is the point: that is just an import");
    }

    /** meta-http-1.tn resolves: the constructor itself is expressible today. */
    @Test
    void theConstructorMetaLayerResolves() throws Exception {
        assertEquals(List.of(), Tson.builder().build().validateSchema(sketch("meta-http-1.tn")));
    }

    /**
     * But a schema governed by it cannot apply the constructor. {@code UnsupportedOperationException} is this
     * project's classification for <em>not implemented</em>, which is what makes this a gap rather than a
     * defect in the sketch. When it lands, this test fails and the README needs updating.
     *
     * <p>This drives the real {@code orders-api-1.tn}, which it could not until {@code UPSTREAM.md} #11 was
     * fixed — before that it stopped at the two-import collision, one wall earlier.
     */
    @Test
    void applyingAUserDefinedConstructorIsNotImplemented() throws Exception {
        Map<String, String> lib = Map.of(
                "https://tson.io/2026/32/ltr8/http/meta-http-1.tn", sketch("meta-http-1.tn"),
                "https://tson.io/2026/32/ltr8/http/problem-2.tn", TsonProblemSchema.source(),
                "https://schemas.example.com/2026/32/app/order-1.tn", sketch("order-1.tn"),
                "https://schemas.example.com/2026/32/app/orders-errors-1.tn", sketch("orders-errors-1.tn"));

        UnsupportedOperationException gap = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> Tson.builder().schemaSource(lib::get).build().validateSchema(sketch("orders-api-1.tn")));

        assertTrue(gap.getMessage().contains("is not a constructor"), gap.getMessage());
    }
}
