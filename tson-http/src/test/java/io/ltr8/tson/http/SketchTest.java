package io.ltr8.tson.http;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.http.apimeta.HttpMethod;
import io.ltr8.tson.http.apimeta.Operation;
import io.ltr8.tson.http.apimeta.Response;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    //
    // orders-api-3.tn needs nothing but the ordinary header -- meta.tn governing, core.tn imported.
    // Metadata is carried by FIXED fields, which survive into resolver output with their values, where a
    // locally declared annotation's value would have been dropped (#12).

    /**
     * The schemas {@code orders-api-3.tn} imports — a library, which is what #11's fix bought. Every one is a
     * real file in {@code sketch/}, so the whole set is readable without going through this test.
     */
    private static Map<String, String> ordersLibrary() throws Exception {
        return Map.of(
                "https://tson.io/2026/32/ltr8/http/http-api-1.tn", sketch("http-api-1.tn"),
                TsonProblemSchema.ID, TsonProblemSchema.source(),
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
    void aValueParameterFixedFieldConstrains() {
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

        assertTrue(tson.validate(header + "!created { status: 999  body: !order { sku: \"a\" } }").stream()
                        .anyMatch(d -> d.code() == io.ltr8.tson.compiler.Diagnostic.Code.FIELD_FIXED),
                "and a parameter-filled one does too, since UPSTREAM #14 -- the whole point of `= S`");
    }

    /**
     * <b>{@code UPSTREAM.md} #13 is still open, and now reports differently.</b> A template application
     * inside a choice is still not implemented; what changed is the channel. A gap used to abort the pass as
     * an {@code UnsupportedOperationException} and now travels as a {@link
     * io.ltr8.tson.compiler.Diagnostic.Code#NOT_IMPLEMENTED} diagnostic beside the ordinary problems, so one
     * unimplemented construct no longer costs every other declaration its verdict.
     *
     * <p><b>Worth writing down because it nearly read as a fix.</b> The old test asserted that resolving
     * <em>throws</em>; it stopped throwing, which looks exactly like the feature landing. It had not. A test
     * that pins a gap must pin the gap, not the way it was delivered — which is why this asserts the code.
     */
    @Test
    void anApplicationInsideAChoiceIsStillNotImplemented() {
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

        List<Diagnostic> problems = Tson.builder().schemaSource(u -> null).build().validateSchema(schema);

        assertTrue(problems.stream().anyMatch(d -> d.code() == Diagnostic.Code.NOT_IMPLEMENTED
                        && d.message().contains("must be lifted to an entry")),
                () -> "expected a NOT_IMPLEMENTED diagnostic, got " + problems);
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

    // ── the design that was blocked, and now works: a meta-layer `operation` constructor ──

    private static final String API_ID = "https://schemas.example.com/2026/32/app/orders-api-1.tn";

    /**
     * <b>An operation declared by a meta layer, bound to this project's own Java record.</b> This was the
     * first sketch and it was blocked twice over: {@code ~top &} resolved but could not be registered,
     * because registering a constructor demanded a value-reader factory and an operation is never the type
     * of a value ({@code UPSTREAM.md} #15); and once the {@code data} base kind fixed that,
     * {@code Tson.builder()} still handed the compiler a fixed binder, so none of it was reachable through
     * the front door ({@code UPSTREAM.md} #17). Both have landed.
     *
     * <p><b>The wiring is three things and there is nothing else</b>: {@code meta-http-1.tn} declaring
     * {@code operation => ~data & { … }}, {@link Operation} carrying {@code @Typename} and implementing
     * {@code Data}, and the {@code metaNameBinder} below. Note it is a <em>separate</em> seam from
     * {@code dataBindContext}: that one binds the data a schema describes, this one binds a governing
     * meta's own vocabulary, and a name can mean different things on the two sides.
     */
    private static Tson api(Map<String, String> lib) {
        return Tson.builder()
                .schemaSource(lib::get)
                .metaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                        Set.of("io.ltr8.tson.http.apimeta"), Map.of()))
                .build();
    }

    /** The sketch library, with {@code orders-api-1.tn} itself overridable so a test can corrupt one line. */
    private static Map<String, String> apiLib(String api) throws Exception {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put("https://tson.io/2026/32/ltr8/http/meta-http-1.tn", sketch("meta-http-1.tn"));
        lib.putAll(TsonProblemSchema.publishedById());
        lib.put("https://schemas.example.com/2026/32/app/order-1.tn", sketch("order-1.tn"));
        lib.put("https://schemas.example.com/2026/32/app/orders-errors-1.tn", sketch("orders-errors-1.tn"));
        lib.put(API_ID, api);
        return lib;
    }

    /** What the compiler says when {@code orders-api-1.tn} is broken one way. */
    private static String refused(String find, String replace) throws Exception {
        Map<String, String> lib = apiLib(sketch("orders-api-1.tn").replace(find, replace));
        return org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api(lib).resolve(lib.get(API_ID))).getMessage();
    }

    /**
     * The claim the whole sketch was for: the description is addressable as Java objects, and every payload
     * type in it is a reference the linker has already resolved -- not a string this code has to look up.
     */
    @Test
    void anOperationIsReadBackAsThisProjectsOwnJavaRecord() throws Exception {
        Tson tson = api(apiLib(sketch("orders-api-1.tn")));
        tson.resolve(sketch("orders-api-1.tn"));
        TypeDefinition entry = tson.schemaRegistry().get(API_ID).orElseThrow()
                .schema().entries().get("create_order");

        assertEquals(TypeKind.DATA, entry.kind(), "an operation is not a type");
        assertEquals(Optional.of(TypeRef.of("operation")), entry.source(), "§8.2 records what built it");

        Operation create = assertInstanceOf(Operation.class, entry.body());
        assertEquals(HttpMethod.POST, create.method());
        assertEquals("/orders", create.path());
        assertEquals(Optional.of(TypeRef.of("order")), create.request());
        assertEquals(List.of(201, 400, 404), create.responses().stream().map(Response::status).toList());
        assertEquals(Optional.of(TypeRef.of("sku_not_found")),
                create.responseFor(404).orElseThrow().body());
    }

    /**
     * <b>The reference is checked, and that is the difference from every data-shaped design here.</b>
     * {@code orders-api-4.tn} spells the same body as the string {@code "sku_not_found"} and nothing
     * resolves it -- {@link TsonApi#validate} had to be written to do that by hand, and reimplemented an
     * upstream bug doing it. Here {@code Data.references()} reaches the linker, so the compiler answers,
     * and it answers during resolution rather than in a method a consumer has to remember to call.
     */
    @Test
    void aPayloadTypeThatDoesNotExistIsRefusedByTheCompiler() throws Exception {
        assertTrue(refused("body: sku_not_found", "body: sku_not_fnud").contains("sku_not_fnud"));
    }

    /** And the payload's own shape is checked against the constructor's declaration, field by field. */
    @Test
    void aMisspeltOperationFieldIsRefusedByTheCompiler() throws Exception {
        assertTrue(refused("method:     POST", "methd:     POST").contains("methd"));
    }

    // ── templates, in the schema model ──

    /**
     * <b>Templates work, and an operation references an application by name.</b> `page<order>` is written
     * once and applied, where OpenAPI hand-rolls the envelope per endpoint or bolts it on with `allOf`.
     * The application is a real entry with structural identity (§8.2), so two endpoints returning a page of
     * orders share one.
     */
    @Test
    void anOperationReferencesATemplateApplication() throws Exception {
        Tson tson = api(apiLib(sketch("orders-api-1.tn")));
        tson.resolve(sketch("orders-api-1.tn"));
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();

        Operation list = assertInstanceOf(Operation.class, entries.get("list_orders").body());
        assertEquals(Optional.of(TypeRef.of("order_page")), list.responseFor(200).orElseThrow().body());
        // `order_page` is a REFERENCE to the entry the application materialised; that entry carries the
        // applied form in its `source`, which is what §8.2 makes identity out of.
        TypeDefinition alias = entries.get("order_page");
        assertEquals(TypeKind.REFERENCE, alias.kind());
        String materialised = alias.source().orElseThrow().name();
        TypeRef applied = entries.get(materialised).source().orElseThrow();
        assertEquals("page", applied.name());
        assertEquals(1, applied.arguments().size(), "page<order> -- one argument, recorded structurally");
    }

    /**
     * <b>Applying one inline needs the braced form, and that is by design rather than a gap.</b>
     * {@code page<order>} is <em>schema</em> syntax and an {@code !operation} payload is <em>data</em>, where
     * a {@code type_ref} slot takes §5.6's positional form: a bare token when there are no arguments, a
     * braced record when there are. The kernel says so in as many words -- <em>"a braced record is the
     * explicit form, canonical only when `arguments` is present"</em>. Writing the sugar in a payload is a
     * parse error, not a resolution one.
     */
    @Test
    void aTemplateAppliedInlineNeedsTheBracedFormAndIsStillChecked() throws Exception {
        String inline = sketch("orders-api-1.tn").replace("body: order_page",
                "body: { name: page  arguments: [ { name: order } ] }");
        Map<String, String> lib = apiLib(inline);
        Tson tson = api(lib);
        tson.resolve(inline);

        Operation list = assertInstanceOf(Operation.class, tson.schemaRegistry().get(API_ID).orElseThrow()
                .schema().entries().get("list_orders").body());
        TypeRef body = list.responseFor(200).orElseThrow().body().orElseThrow();
        assertEquals("page", body.name());
        assertEquals(1, body.arguments().size(), "the argument survives into the bound record");

        // And the sugar in the same position is refused by the parser, before resolution is reached.
        assertTrue(refused("body: order_page", "body: page<order>")
                .contains("adjacent values must be separated"));
    }

    /**
     * A bad template argument is caught either way, but the two spellings report it differently -- the
     * inline one names the operation, the named application names the entry the template materialised.
     */
    @Test
    void aBadTemplateArgumentIsCaughtInBothSpellings() throws Exception {
        assertTrue(refused("page<order>", "page<no_such>").contains("unresolved reference 'no_such'"));

        String inline = sketch("orders-api-1.tn").replace("body: order_page",
                "body: { name: page  arguments: [ { name: no_such } ] }");
        Map<String, String> lib = apiLib(inline);
        String message = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api(lib).resolve(inline)).getMessage();
        assertTrue(message.contains("unresolved reference 'no_such'"), message);
        assertTrue(message.contains("list_orders"), "the inline form names the operation: " + message);
    }

    // ── two gaps found while weighing that design, pinned so a fix shows up here ──

    private static Tson metaLayerTson(String metaBody, String docBody, String id, Map<String, String> lib) {
        lib.put("https://tson.io/2026/32/ltr8/http/meta-probe.tn", """
                !!id:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/32/m/meta.tn"
                {
                %s
                }""".formatted(metaBody));
        lib.put(id, docBody);
        return altTson(lib);
    }

    /**
     * <b>{@code UPSTREAM.md} #18.</b> A meta-layer declaration is not in the governed schema's type namespace,
     * and every reference form says so plainly — except an <em>application</em>, which claims the arguments
     * are missing when they are right there. Two lookup paths disagreeing about scope, with an inaccurate
     * message as the symptom.
     */
    @Test
    void applyingAMetaLayerTemplateMisreportsItsArguments() {
        String meta = "  status_code => !integer ^ { min: 100  max: 599 }\n  tmpl => <T> { v: T }";
        String id = "https://schemas.example.com/2026/32/app/x-1.tn";
        String header = """
                !!id:"%s"
                !!meta:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                %s
                }""";

        // A plain reference to a meta-layer name is refused correctly.
        String plain = header.formatted(id, "  x => { s: status_code }");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> metaLayerTson(meta, plain, id, new LinkedHashMap<>()).resolve(plain))
                .getMessage().contains("unresolved reference 'status_code'"));

        // Applying one is refused for the wrong stated reason -- the arguments ARE written.
        String applied = header.formatted(id, "  x => tmpl<text>");
        String message = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> metaLayerTson(meta, applied, id, new LinkedHashMap<>()).resolve(applied)).getMessage();
        assertTrue(message.contains("is a template taking 1 type argument"),
                "when #18 is fixed this should say `unresolved reference 'tmpl'`: " + message);
    }

    /**
     * <b>{@code UPSTREAM.md} #10's reverse case at the meta layer — reported, since strict binding.</b> It
     * used to be an NPE out of {@code resolve}: the component arrived {@code null} and
     * {@code Data.references()} dereferenced it inside resolution, so a consumer's own wiring mistake read as
     * a library fault. It is now a {@code TsonBindMismatchException} naming both sides.
     */
    @Test
    void aJavaComponentTheMetaDoesNotDeclareIsReported() {
        String id = "https://schemas.example.com/2026/32/app/x-1.tn";
        String doc = """
                !!id:"%s"
                !!meta:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  y  => { a: text }
                  op => !operation { method: "GET"  path: "/x"  responses: [ y ] }
                }""".formatted(id);
        Map<String, String> lib = new LinkedHashMap<>();

        // apimeta.Operation has `parameters` and `request`; this meta declares neither.
        TsonBindMismatchException npe = org.junit.jupiter.api.Assertions.assertThrows(
                TsonBindMismatchException.class,
                () -> Tson.builder().schemaSource(lib::get)
                        .metaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                                Set.of("io.ltr8.tson.http.apimeta"), Map.of()))
                        .build()
                        .resolve(metaLayerDoc(lib, id, doc)));

        assertTrue(npe.getMessage().contains("parameters"), npe.getMessage());
    }

    private static String metaLayerDoc(Map<String, String> lib, String id, String doc) {
        lib.put("https://tson.io/2026/32/ltr8/http/meta-probe.tn", ALT_META);
        lib.put(id, doc);
        return doc;
    }

    // ── the `response<T, S>` alternative, weighed and not adopted ──

    private static final String ALT_META = """
            !!id:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
            !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/32/m/meta.tn"
            {
              operation => ~data & { method: text  path: text  responses: [type_ref] }
            }""";

    private static final String ALT_RESPONSES = """
            !!id:"https://tson.io/2026/32/ltr8/http/resp-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              status_code => !integer ^ { min: 100  max: 599 }
              response => <T, S> { status: status_code = S  body: T }
            }""";

    /**
     * <b>A templated operation is refused, so the CRUD-family payoff is not available.</b> Writing
     * {@code list => <T> !operation { … }} — one declaration standing for every paged list endpoint — is a
     * parse error: §12.1 permits a type name, an application or a literal in an instance template binding,
     * and an {@code !operation { … }} payload is a container form. This is the shape that would have made
     * templating the meta layer worth doing.
     */
    @Test
    void aDataConstructorCannotItselfBeTemplated() throws Exception {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put("https://tson.io/2026/32/ltr8/http/meta-probe.tn", ALT_META);
        String doc = """
                !!id:"%s"
                !!meta:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  list => <T> !operation { method: "GET"  path: "/x"  responses: [] }
                }""".formatted(API_ID);
        lib.put(API_ID, doc);

        String message = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> altTson(lib).resolve(doc)).getMessage();

        assertTrue(message.contains("not permitted in an instance template binding"), message);
    }

    /**
     * <b>The `response<T, S>` form does work, and buys less than it costs.</b> Responses become
     * {@code [type_ref]} naming applications of an <em>ordinary</em> imported template — the template cannot
     * live in the meta layer, which is neither in the governed schema's type namespace nor importable
     * alongside {@code core.tn}. What it gains is a real materialised type per (body, status) with §8.2
     * identity, deduped schema-wide.
     *
     * <p><b>What it loses is the reason to want it.</b> The one thing {@code status: status_code = S} says
     * that {@code status: 201} as data does not is that the status is <em>fixed</em> — and the materialised
     * field comes back {@code REQUIRED} carrying 201 rather than {@code REQUIRED_FIXED}
     * ({@code UPSTREAM.md} #14). Meanwhile the data spelling is checked today: a status of 42 violates
     * {@code status_code}'s refinement. So the template form is currently the <em>less</em> checked of the
     * two.
     */
    @Test
    void theTemplatedResponseFormResolvesAndItsStatusIsFixed() throws Exception {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put("https://tson.io/2026/32/ltr8/http/meta-probe.tn", ALT_META);
        lib.put("https://tson.io/2026/32/ltr8/http/resp-1.tn", ALT_RESPONSES);
        lib.put("https://schemas.example.com/2026/32/app/order-1.tn", sketch("order-1.tn"));
        String doc = """
                !!id:"%s"
                !!meta:"https://tson.io/2026/32/ltr8/http/meta-probe.tn"
                !!import:"https://schemas.example.com/2026/32/app/order-1.tn"
                !!import:"https://tson.io/2026/32/ltr8/http/resp-1.tn"
                {
                  ok_order => response<order, 201>
                  create => !operation { method: "POST"  path: "/o"  responses: [ ok_order ] }
                }""".formatted(API_ID);
        lib.put(API_ID, doc);

        Tson tson = altTson(lib);
        tson.resolve(doc);
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();

        // The application materialised a real type, and §8.2 recorded what built it -- a reference argument
        // and a value argument, structurally distinguished.
        String materialised = entries.get("ok_order").source().orElseThrow().name();
        TypeRef applied = entries.get(materialised).source().orElseThrow();
        assertEquals("response", applied.name());
        assertEquals(2, applied.arguments().size(), "one type argument and one value argument");

        // And since UPSTREAM.md #14 the substituted status is genuinely fixed, not merely carried -- which
        // removes the disqualifier this design was rejected on. See sketch/README.md for what still stands.
        RecordBody body = (RecordBody) entries.get(materialised).body();
        RecordField status = body.fields().stream().filter(f -> f.name().equals("status"))
                .findFirst().orElseThrow();
        assertTrue(status.value().orElseThrow().toString().contains("201"),
                "the substituted value is right: " + status.value());
        assertEquals(FieldState.REQUIRED_FIXED, status.state(),
                "the substituted status is fixed, so a document sending another value is rejected");
    }

    private static Tson altTson(Map<String, String> lib) {
        return Tson.builder().schemaSource(lib::get)
                .metaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                        Set.of("io.ltr8.tson.http.probe"), Map.of()))
                .build();
    }

    /**
     * <b>And an operation cannot be used where a type belongs.</b> That is what the {@code data} kind buys
     * beyond registration: against a kernel without it the misuse links, and fails only when some document
     * is read against the schema.
     */
    @Test
    void namingAnOperationWhereATypeBelongsIsRefused() throws Exception {
        assertTrue(refused("\n}", "\n  holder => { op: create_order }\n}")
                .contains("describes something other than a data value"));
    }

}
