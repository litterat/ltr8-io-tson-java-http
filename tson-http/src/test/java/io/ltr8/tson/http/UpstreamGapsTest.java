package io.ltr8.tson.http;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.source.HttpSchemaSource;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gaps and constraints in the library this project builds on, each pinned so that a change upstream shows up
 * here as a <em>failing test</em> rather than as nothing happening.
 *
 * <p><b>This class outlives {@code UPSTREAM.md}'s entries.</b> That file holds only what is still open and
 * deletes an item once it closes, so most of what is pinned here has no entry any more — which is the point:
 * a fixed gap is exactly the thing that regresses unnoticed. Only a still-open gap names its number, because
 * only a number that can go stale is worth checking. Two rules learned the hard way and worth restating:
 *
 * <ul>
 *   <li><b>Pin the gap, not the way it is delivered.</b> The choice test below once asserted that resolution
 *       <em>throws</em>. It stopped throwing when gaps became diagnostics — indistinguishable from the
 *       feature landing, if the test is not looking at the code. It had not landed.</li>
 *   <li><b>A fixed gap flips its test, it does not delete it.</b> The fixed-field tests assert the constraint
 *       now holds, which is what stops it silently regressing.</li>
 * </ul>
 */
class UpstreamGapsTest {

    private static final String META_ID = "https://tson.io/2026/39/io/ltr8/http/meta-probe.tn";
    private static final String API_ID = "https://schemas.example.com/2026/39/app/probe-1.tn";

    /** A meta layer declaring namespace types of its own, standing in for meta-service without depending on its shape. */
    private static String meta(String declarations) {
        return """
                !!id:"%s"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                %s
                }""".formatted(META_ID, declarations);
    }

    private static String governed(String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }""".formatted(API_ID, META_ID, declarations);
    }

    private static Tson tson(String metaSource, String doc, String... packages) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, metaSource);
        lib.put(API_ID, doc);
        ProcessorConfig config = ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(lib::get));
        if (packages.length > 0) {
            config = config.withDataBindContext(
                    AtomContext.defaultContext().toBuilder()
                            .nameBinder(new DataNameBinder.DefaultDataNameBinder(Set.of(packages), Map.of())).build());
        }
        return Tson.of(config);
    }

    // ── fixed upstream, pinned here: a template application inside a choice ─────────────────────

    /**
     * <b>Fixed: {@code (resp<order, 201> | resp<problem, 400>)} resolves.</b> This is the shape an operation's
     * responses want. An application inside a choice lifts to an entry as one in a field or an array does, so
     * naming each application as its own entry first is a choice of style rather than a necessity.
     *
     * <p>Asserting resolution alone would pass on silence: a gap reported through another channel reads as a fix.
     * So it asserts the resolved shape: each application becomes its own synthetic entry, the choice names both,
     * and each carries the value argument as a {@link FieldRole#FIXED} field -- which is the part that would be
     * quietly wrong if the substitution were dropped.
     */
    @Test
    void anApplicationInsideAChoiceResolves() {
        String schema = """
                !!id:"https://s.example.com/2026/39/p-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    order   => { sku: text }
                    problem => { title: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    op      => { response: (resp<order, 201> | resp<problem, 400>) }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null)));

        List<Diagnostic> problems = tson.validateSchema(schema);
        assertEquals(List.of(), problems, () -> "expected a clean resolution, got " + problems);

        var entries = tson.schemaRegistry().get("https://s.example.com/2026/39/p-1.tn").orElseThrow()
                .schema().entries();
        TypeRef response = ((RecordBody) entries.get("op")).fields().values().iterator().next().typeRef();
        var variants = assertInstanceOf(ChoiceBody.class, entries.get(response.name())).variants();
        assertEquals(2, variants.size(), () -> "both applications lift: " + variants);

        // 201 with an order, 400 with a problem -- the substitution the synthetic names encode, checked.
        assertEquals(List.of("201", "order", "400", "problem"), variants.stream()
                .map(v -> (RecordBody) entries.get(v.name()))
                .flatMap(b -> b.fields().values().stream())
                .map(f -> f.role() == FieldRole.FIXED
                        ? f.value().orElseThrow().text() : f.typeRef().name())
                .toList());
    }

    // ── fixed upstream, pinned here: a value parameter filling a FIXED field ─────────────────────

    /** {@code status: status_code = S} applied as {@code <order, 201>} both carries 201 and enforces it. */
    @Test
    void aValueParameterFixedFieldConstrains() {
        String schema = """
                !!id:"https://s.example.com/2026/39/p-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    order    => { sku: text }
                    resp     => <T, S> { status: int32 = S  body: T }
                    created  => resp<order, 201>
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        String header = "!!import:\"https://s.example.com/2026/39/p-1.tn\"\n";

        assertEquals(List.of(), tson.validate(header
                + "!created { status: 201  body: !order { sku: \"a\" } }"));
        assertTrue(tson.validate(header + "!created { status: 999  body: !order { sku: \"a\" } }").stream()
                        .anyMatch(d -> d.code() == Diagnostic.Code.FIELD_FIXED),
                "a parameter-filled FIXED field constrains -- the whole point of `= S`");
    }

    /**
     * The same, seen through the applied entry: the substituted field is {@link FieldRole#FIXED} rather than
     * merely carrying its value; without the constraint the {@code response<T, S>} design would be unsound. A
     * declaration naming an application is that application's entry ([TSON-SCHEMA] §8.2), so the field is read
     * from {@code created} itself, with no hop onto a minted name.
     */
    @Test
    void aMaterialisedApplicationCarriesAFixedField() {
        String schema = """
                !!id:"https://s.example.com/2026/39/p-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    order   => { sku: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    created => resp<order, 201>
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        var resolved = tson.schemaRegistry().get("https://s.example.com/2026/39/p-1.tn").orElseThrow().schema();
        var entries = resolved.entries();

        assertEquals("resp", resolved.applicationOf("created").orElseThrow().name());
        RecordBody body = (RecordBody) entries.get("created");
        RecordField status = body.fields().get("status");

        assertEquals("201", status.value().orElseThrow().text());
        assertEquals(FieldRole.FIXED, status.role());
        // Unmarked name, so a marker the document must write (§5.2's `a: T = v`), and never injected.
        assertFalse(status.optional(), "the name is unmarked, so the key must be written");
    }

    // ── one namespace: every name an imported layer declares resolves in the importer ─────────────

    /**
     * <b>A layer's declarations are names its importer may use, every kind of them.</b> A schema's namespace is
     * its own entries and every entry its {@code !!import}s bring in, one namespace with no second store for a
     * layer's vocabulary ([TSON-SCHEMA] §2.2.3, §3.4), so an atom, a record, a namespace type, a template unapplied
     * and a template applied, each declared by the imported layer, all resolve where the importer names them. That
     * the unapplied template resolves at all is its own gap, {@link #aTemplatesBareNameAsAFieldTypeResolvesSilently}.
     */
    @Test
    void everyNameAnImportedLayerDeclaresResolvesInTheImporter() {
        String metaSource = meta("""
                  scalar => !integer ^ { min: 100  max: 599 }
                  plain  => { a: text }
                  tmpl   => <T> { v: T }
                  item   => { a: text }
                  ctor   => !meta.namespace { key_type: meta.identifier  value_type: item }""");

        record Case(String label, String declaration) {
        }
        List<Case> cases = List.of(
                new Case("an atom", "  x => { s: scalar }"),
                new Case("a record", "  x => { s: plain }"),
                new Case("a namespace type as a type", "  x => { s: ctor }"),
                new Case("a template, unapplied", "  x => { s: tmpl }"),
                new Case("a template, applied", "  x => tmpl<text>"));

        for (Case one : cases) {
            String doc = governed(one.declaration());
            assertEquals(List.of(), tson(metaSource, doc).validateSchema(doc), one.label());
        }
    }

    /** And the control: the same template declared locally applies fine. */
    @Test
    void aLocallyDeclaredTemplateApplies() {
        String metaSource = meta("""
                  item => { a: text }
                  ctor => !meta.namespace { key_type: meta.identifier  value_type: item }""");
        String doc = governed("  local => <T> { v: T }\n  x => local<text>");

        tson(metaSource, doc).resolve(doc);
    }

    // ── fixed upstream: a Java component the meta layer does not declare ─────────────────────────

    /**
     * A Java component the meta declaration does not declare is a consumer's wiring mistake, and strict binding
     * names both sides rather than letting it surface as a library fault.
     */
    @Test
    void aJavaComponentTheMetaDoesNotDeclareIsReported() {
        String metaSource = meta("""
                  operation => { method: text  path: text  responses: [meta.type_ref] }
                  ops       => !meta.namespace { key_type: meta.identifier  value_type: operation }""");
        String doc = governed("""
                  y   => { a: text }
                  api => !ops { op => { method: "GET"  path: "/x"  responses: [ y ] } }""");

        // io.ltr8.tson.http.api.Operation has `request`, `response`, `errors` and more; this meta declares none.
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.api").resolve(doc));

        assertTrue(thrown.getMessage().contains("component 'request'") && thrown.getMessage().contains("'method'"),
                thrown.getMessage());
    }

    // ── a templated http projection: a namespace value under a template, applied by name ─────────

    /**
     * <b>The CRUD-family payoff, restated for a namespace.</b> An endpoint is a value held two maps down in an http
     * projection, so it is no entry and cannot be applied on its own; what a template can hold is a whole {@code
     * !http} value. {@code fetching => <T> !http { … }} stands for every fetch-by-id family, and {@code orders =>
     * fetching<order>} names its application. [TSON-SCHEMA] §8.2 makes a declaration naming an application that
     * application's entry, so {@code orders} <em>is</em> the materialised namespace value, under the author's name,
     * with the argument substituted into its endpoints.
     *
     * <p>So {@link io.ltr8.tson.http.api.TsonApiDescription} finds it the way it finds a written-out one, by what
     * the entry is. Asserted through the description rather than the entries map, because that is the consumer the
     * payoff is for; the written-out projection beside it keeps the comparison honest.
     */
    @Test
    void aTemplatedHttpProjectionIsAppliedByName() {
        String template = """
                  order     => { sku: text }
                  order_ref => { id: text }
                  order_put => { id: text  sku: text }
                  fetching  => <T> !http { "/orders/{id}" => {
                    @safe GET => !operation { request: order_ref  response: T } } }
                  orders    => fetching<order>
                  writes    => !http { "/orders/{id}" => {
                    @idempotent PUT => !operation { request: order_put  response: order } } }""";

        var described = TsonApiSchema.describedBy(resolveAgainstServiceMeta(template), API_ID);

        assertEquals(List.of("GET /orders/{id}", "PUT /orders/{id}"),
                described.routes().stream().map(io.ltr8.tson.http.api.Route::key).sorted().toList(),
                "the template itself holds no endpoint");
        var get = described.route(io.ltr8.tson.http.api.HttpVerb.GET, "/orders/{id}").orElseThrow();
        assertEquals("order", get.response().orElseThrow().name(), "the argument is substituted into the response");
    }

    /**
     * <b>A templated http projection mints no name from its paths</b> -- kept because an HTTP path is the case that
     * would be the first to regress. A name minted from a path {@code "/x"} would hold {@code /}, which §8.2 makes a
     * MUST violation ("an internal name is a valid identifier") and which would surface as a name-hygiene refusal on
     * {@code U+002F} -- a refusal on a name nobody wrote and nobody could edit. A declaration naming the application
     * mints no name at all, so every path resolves under the template.
     *
     * <p>A path key is a name of its own now, in the URL namespace's grammar ({@code path_template}, an identifier
     * family), so §8.2's hygiene judges the key the author wrote: a single-script path in Cyrillic resolves, and a
     * mixed-script one is refused, naming the key written rather than a name minted from it.
     */
    @Test
    void aTemplatedHttpProjectionsPathMintsNoName() {
        String body = """
                  order    => { sku: text }
                  fetching => <T> !http { "%s" => { @safe GET => !operation { response: T } } }
                  orders   => fetching<order>""";

        for (String path : List.of("/", "/x", "/orders/42", "/путь")) {
            var described = assertDoesNotThrow(() -> TsonApiSchema.describedBy(
                    resolveAgainstServiceMeta(body.formatted(path)), API_ID), path);
            assertTrue(described.route(io.ltr8.tson.http.api.HttpVerb.GET, path).isPresent(), path);
        }
        String mixed = assertThrows(RuntimeException.class,
                () -> resolveAgainstServiceMeta(body.formatted("/pаth"))).getMessage();
        assertTrue(mixed.contains("'/pаth' mixes the scripts"), mixed);
    }

    /**
     * Resolves {@code declarations} as a schema governed by this project's own {@code meta-service.tn}. The probe
     * meta the other tests use would do for the parse, but not past it: once these declarations resolve far enough
     * to bind, its minimal records disagree with this project's bound classes. The real meta is also the one whose
     * payoff this is.
     */
    private static Tson resolveAgainstServiceMeta(String declarations) {
        String doc = """
                !!id:"%s"
                !!import:"%s"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }""".formatted(API_ID, TsonApiSchema.ID, declarations);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(TsonApiSchema.publishedById())))
                .withDataBindContext(TsonBindings.of(TsonApiSchema.bindings())));
        tson.resolve(doc);
        return tson;
    }

    // ── not a gap: an entry's two annotation positions ───────────────────────────────────────────

    /**
     * <b>Not a gap, and pinned because it reads like one.</b> A schema entry has two annotation positions and
     * they land in different places: before the name annotates the <em>entry</em> and is read from the entries
     * map, after the arrow annotates the <em>definition</em> and is read from the schema's {@code factsOf}.
     * Checking only the second for an annotation written in the first position looks exactly like the
     * annotation being dropped — which is what makes both retention rules worth asserting.
     */
    @Test
    void anEntrysTwoAnnotationPositionsLandInDifferentPlaces() {
        String schema = """
                !!id:"https://s.example.com/2026/39/p-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  @doc:"on the entry"
                  before => { a: text }
                  after  => @doc:"on the definition" { a: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        var resolved = tson.schemaRegistry().get("https://s.example.com/2026/39/p-1.tn").orElseThrow().schema();
        var entries = resolved.entries();

        assertEquals(java.util.Optional.of("on the entry"),
                entries.getAnnotations("before").value("doc", String.class));
        assertEquals(List.of(), resolved.factsOf("before").annotations().values(),
                "not here -- looking only here is the mistake");

        assertEquals(java.util.Optional.of("on the definition"),
                resolved.factsOf("after").annotations().value("doc", String.class));
    }

    /** And an annotation naming no type is refused, wherever it is written. */
    @Test
    void anUnknownAnnotationOnAnEntryIsRefused() {
        String schema = """
                !!id:"https://s.example.com/2026/39/p-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  @nosuchtype:"x"
                  thing => { a: text }
                }""";

        assertTrue(assertThrows(RuntimeException.class,
                () -> Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema))).resolve(schema))
                .getMessage().contains("does not name a type"));
    }

    // ── a namespace entry's own guarantee, which nothing else asserts here ───────────────────────

    /** An entry holding a namespace value is not a type, so a position a type-ref occupies refuses it at link. */
    @Test
    void aNamespaceEntryCannotBeUsedWhereATypeBelongs() {
        String metaSource = meta("""
                  operation => { method: text  path: text  responses: [meta.type_ref] }
                  ops       => !meta.namespace { key_type: meta.identifier  value_type: operation }""");
        String doc = governed("""
                  y      => { a: text }
                  api    => !ops { op => { method: "GET"  path: "/x"  responses: [ y ] } }
                  holder => { o: api }""");

        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.probe").resolve(doc)).getMessage();

        assertTrue(message.contains("a value and not a type"), message);
    }

    /**
     * A namespace type declares its facets and nothing else: a value of it is the map alone, so a field beyond the
     * facets {@code meta.namespace} has as a refinement of {@code meta.map} -- {@code key_type} and {@code
     * value_type}, {@code ordered}, {@code min_items} and {@code max_items} among them -- has nowhere to be written,
     * and {@code meta.namespace}'s own record reader refuses it as an unknown field, listing the facets. That is
     * why what {@code meta-service.tn}'s {@code http} says about the protocols behind it -- the claims {@code
     * binds} and {@code not_bound} -- is annotation on the projection's entry rather than fields beside its paths.
     */
    @Test
    void aNamespaceTypeDeclaresOnlyItsFacets() {
        String metaSource = meta("""
                  operation => { method: text }
                  ops       => !meta.namespace {
                    key_type: meta.identifier  value_type: operation  extends: [operation]
                  }""");

        List<Diagnostic> problems = tson(metaSource, governed("  y => { a: text }")).validateSchema(metaSource);

        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("unknown field 'extends' on 'meta.namespace'"),
                problems.getFirst().message());
        assertTrue(problems.getFirst().message().endsWith("whose fields are (chain | access_pattern | size_type | "
                + "key_type | value_type | voidable | ordered | min_items | max_items)"), problems.getFirst().message());
    }

    // ── open: a reference inside an annotation's value is not judged ─────────────────────────────

    /**
     * <b>The resolver does not judge a reference inside an annotation's value.</b> A layer may declare an annotation
     * whose value holds references -- {@code binds => @meta.annotation [meta.ref<protocol>]}, or a record carrying a
     * {@code meta.ref<exchange>} -- and the value's <em>shape</em> is checked ({@code @binds:42} is refused), but no
     * name in it is: an unknown protocol and an exchange key the protocol does not hold both load clean. The {@code
     * [meta.ref<protocol>]} elements and the record's {@code meta.ref<exchange>} each arrive as a {@code TypeRef}
     * nobody resolved.
     *
     * <p>{@code meta-service.tn} writes an http projection's {@code @binds} and {@code @not_bound} exactly this way,
     * so {@code TsonApiDescription} checks both itself: each name is an entry applying {@code protocol}, each
     * exemption a key of a protocol the projection binds. The layer keeps the {@code ref<…>} typing so the declaration says
     * what it means; when this is fixed, the clean load below flips and the reader's checks become a second net.
     */
    @Test
    void aReferenceInsideAnAnnotationValueIsNotJudged() {
        String metaSource = meta("""
                  exchange      => { request?: meta.type_ref }
                  exchange_name => meta.identifier
                  protocol      => !meta.namespace { key_type: exchange_name  value_type: exchange }
                  exemption     => { exchange: meta.ref<exchange>  reason: text }
                  binds         => @meta.annotation [meta.ref<protocol>]
                  not_bound     => @meta.annotation [exemption]""");
        String unsound = governed("""
                  order  => { sku: text }
                  orders => !protocol { place => { request: order } }
                  @binds:[no_such]
                  @not_bound:[ { exchange: orders.no_such  reason: "later" } ]
                  holder => { a: text }""");
        Tson tson = tson(metaSource, unsound, "io.ltr8.tson.http.probe.annotation");

        assertEquals(List.of(), tson.validateSchema(unsound), "neither name is judged");

        var annotations = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().getAnnotations("holder");
        List<?> bound = annotations.value("binds", List.class).orElseThrow();
        assertEquals(List.of("no_such"), bound.stream().map(e -> assertInstanceOf(TypeRef.class, e).name())
                .toList(), "a [meta.ref<protocol>] element arrives as a TypeRef nobody resolved");
        var exemption = assertInstanceOf(io.ltr8.tson.http.probe.annotation.Exemption.class,
                annotations.value("not_bound", List.class).orElseThrow().getFirst());
        assertEquals("orders.no_such", exemption.exchange().name(),
                "a meta.ref<exchange> arrives as an unresolved TypeRef");

        String misshapen = governed("  @binds:42\n  holder => { a: text }");
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR),
                tson(metaSource, misshapen, "io.ltr8.tson.http.probe.annotation").validateSchema(misshapen).stream()
                        .map(Diagnostic::code).toList(),
                "the value's shape is checked");
    }

    // ── a constraint: a namespace is keyed by an identifier family ───────────────────────────────

    /**
     * <b>Revision 39 keys a namespace by names: its {@code key_type} must be an identifier family.</b> A {@code
     * text_type} with a {@code pattern} is refused as a key type where the meta layer declares it, since {@code
     * key_type} is a {@code meta.identifier_ref}. It is required: a namespace states its key family, there being
     * no default one. An {@code identifier_type} widened with {@code start_add} and {@code continue_add} is
     * admitted, and still carries a {@code pattern} that is enforced at the key.
     *
     * <p>{@code meta-service.tn}'s {@code http} is keyed this way: {@code path_template} is an {@code identifier_type}
     * starting with {@code /}, its {@code pattern} holding a URI template's structure, and §8.2's name hygiene
     * reaches a path key as it reaches any name.
     */
    @Test
    void aNamespaceKeyTypeMustBeAnIdentifierFamily() {
        String textKeyed = meta("""
                  exchange => { request?: meta.type_ref }
                  path     => !unicode.text_type { pattern: "/[a-z]*" }
                  paths    => !meta.namespace { key_type: path  value_type: exchange }""");
        List<Diagnostic> refused = tson(textKeyed, governed("  y => { a: text }")).validateSchema(textKeyed);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), refused.stream().map(Diagnostic::code).toList(),
                () -> "" + refused);
        assertTrue(refused.getFirst().message().contains("'paths' key_type names 'path', which is no identifier "
                + "family"), refused.getFirst().message());

        String unkeyed = meta("""
                  exchange => { request?: meta.type_ref }
                  paths    => !meta.namespace { value_type: exchange }""");
        List<Diagnostic> keyless = tson(unkeyed, governed("  y => { a: text }")).validateSchema(unkeyed);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), keyless.stream().map(Diagnostic::code).toList(),
                () -> "" + keyless);
        assertTrue(keyless.getFirst().message().contains("missing required field 'key_type' for 'meta.namespace'"),
                keyless.getFirst().message());

        String identifierKeyed = meta("""
                  exchange => { request?: meta.type_ref }
                  path     => !unicode.identifier_type {
                    start: NONE  start_add: "/"  continue_add: "/"  pattern: "/[a-z/]*" }
                  paths    => !meta.namespace { key_type: path  value_type: exchange }""");
        String sound = governed("  order => { sku: text }\n  p => !paths { \"/orders\" => { request: order } }");
        assertEquals(List.of(), tson(identifierKeyed, sound, "io.ltr8.tson.http.probe.annotation")
                .validateSchema(sound));
        String unmatched = governed("  order => { sku: text }\n  p => !paths { \"/Orders\" => { request: order } }");
        List<Diagnostic> problems = tson(identifierKeyed, unmatched, "io.ltr8.tson.http.probe.annotation")
                .validateSchema(unmatched);
        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("does not match the required pattern"),
                problems.getFirst().message());
    }

    // ── fixed upstream: a kind-typed slot on a tagged subtype is judged at any depth ─────────────────

    /**
     * <b>A name's slot type comes with the name from the read, so where the value sits does not matter.</b> {@code op}
     * declares the kind-typed {@code request} its base {@code endpoint} lacks. Held under a key of a namespace, or in a
     * record's map field one level further down ({@code resource.endpoints}), {@code !op { request: order }} resolves
     * and {@code !op { request: no_such }} is refused naming the full path. The reader built from the slot's
     * declaration records its type beside the name, so the linker never walks declared types back to it.
     *
     * <p>{@code meta-service.tn} holds every {@code !operation} this way, in a {@code resource}'s map field under a
     * path key of an {@code http}. Pinned because a gap here stops a sound schema resolving.
     */
    @Test
    void aKindSlotOnASubtypeBehindARecordFieldIsJudged() {
        String metaSource = meta("""
                  endpoint  => abstract {}
                  op        => endpoint & { request?: meta.type_ref }
                  ops       => !meta.namespace { key_type: meta.identifier  value_type: endpoint }
                  resource  => { endpoints: {meta.identifier => endpoint} }
                  resources => !meta.namespace { key_type: meta.identifier  value_type: resource }""");

        String inHand = governed("  order => { sku: text }\n  r => !ops { get => !op { request: order } }");
        assertEquals(List.of(), tson(metaSource, inHand, "io.ltr8.tson.http.probe.subtype").validateSchema(inHand));

        String behindAField = governed(
                "  order => { sku: text }\n  r => !resources { o => { endpoints: { get => !op { request: order } } } }");
        assertEquals(List.of(), tson(metaSource, behindAField, "io.ltr8.tson.http.probe.subtype")
                .validateSchema(behindAField));

        String unsound = governed(
                "  order => { sku: text }\n  r => !resources { o => { endpoints: { get => !op { request: no_such } } } }");
        List<Diagnostic> problems = tson(metaSource, unsound, "io.ltr8.tson.http.probe.subtype").validateSchema(unsound);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'r' at /o/endpoints/get/request"),
                problems.getFirst().message());
    }

    // ── fixed upstream: a reference walks through a record value's field ─────────────────────────────

    /**
     * <b>A {@code ref<T>} name past a record value's field is judged and delivered</b>, as a key-only walk is. An
     * interface held under a key that keeps its methods in a namespace-typed field is reached as {@code
     * i.orders.methods.place}, walking an entry, a key, a field and a key; the walk reads the field through the
     * descriptor that bound the host. A key missing past the field is refused. {@code meta-service.tn}'s own
     * {@code ref<exchange>} walks an entry and a key alone; this pins the longer walk so a regression to a gap fails
     * here.
     */
    @Test
    void aReferenceThroughARecordValuesFieldIsJudged() {
        String metaSource = meta("""
                  method   => { request?: meta.type_ref }
                  methods  => !meta.namespace { key_type: meta.identifier  value_type: method }
                  iface    => { methods: methods }
                  ifaces   => !meta.namespace { key_type: meta.identifier  value_type: iface }
                  binding  => { method: meta.ref<method> }
                  bindings => !meta.namespace { key_type: meta.identifier  value_type: binding }""");
        String interfaces =
                "  order => { sku: text }\n  i => !ifaces { orders => { methods: { place => { request: order } } } }";

        String sound = governed(interfaces + "\n  b => !bindings { p => { method: i.orders.methods.place } }");
        assertEquals(List.of(), tson(metaSource, sound, "io.ltr8.tson.http.probe.field").validateSchema(sound));

        String unsound = governed(interfaces + "\n  b => !bindings { p => { method: i.orders.methods.cancel } }");
        List<Diagnostic> problems = tson(metaSource, unsound, "io.ltr8.tson.http.probe.field").validateSchema(unsound);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("cancel"), problems.getFirst().message());
    }

    // ── decided upstream: an application at a kind slot in a value is named through an alias ──────

    /**
     * <b>Not a gap -- the spec's design, pinned because it decides how an API description applies a template.</b> A
     * slot typed by a kind holds the name of a type, one bare token, and a value entry's payload is a value body,
     * where nothing lifts or mints. So neither the braced record nor the sugar {@code page<order>} is admitted at
     * such a slot inside an {@code !ops { … }} value: both are refused, naming the alias spelling. The alias, {@code
     * order_page => page<order>}, gives the application its entry, and its name is what the slot holds.
     *
     * <p>Revision 38 admitted the braced record there and refused the sugar as a parse error; Revision 39 refuses
     * both and leaves the alias as the one spelling.
     */
    @Test
    void aTemplateApplicationAtAKindSlotInAValueIsNamedThroughAnAlias() {
        String metaSource = meta("""
                  operation => { method: text  path: text  responses: [meta.type_ref] }
                  ops       => !meta.namespace { key_type: meta.identifier  value_type: operation }""");
        String body = """
                  order      => { sku: text }
                  page       => <T> { items: [T] }
                  order_page => page<order>
                  api        => !ops { op => { method: "GET"  path: "/x"  responses: [ %s ] } }""";

        for (String inline : List.of("{ name: page  arguments: [ { name: order } ] }", "page<order>")) {
            String written = governed(body.formatted(inline));
            String message = assertThrows(RuntimeException.class,
                    () -> tson(metaSource, written, "io.ltr8.tson.http.probe").resolve(written)).getMessage();
            assertTrue(message.contains("a position typed by a kind holds the name of a type")
                    && message.contains("declare an alias"), message);
        }

        String aliased = governed(body.formatted("order_page"));
        assertEquals(List.of(), tson(metaSource, aliased, "io.ltr8.tson.http.probe").validateSchema(aliased));
    }

    /**
     * <b>A Class 1 field name meets all three of [TSON-DATA] §8.2's rules, not only the look-alike one.</b> The
     * kind of rule that reads exactly like a test being wrong when it bites: a schemaless record's field names are
     * judged as names, so the two per-name rules reach them as well as the skeleton rule over the field
     * <em>set</em>.
     *
     * <p>Both halves are pinned because the rule decides a code, not a verdict. A within-word homograph
     * draws {@code RESTRICTED_SCRIPT} <em>as well as</em> the look-alike refusal it always drew, and the
     * script one is reported first -- which is what decides the problem {@code type} a client is sent to,
     * {@link TsonHttpException} taking the first refusal in the list. Two names each admitted on their own
     * still reach the set rule and only that. Asserting the second alone would let the first drift back
     * silently.
     */
    @Test
    void everyFieldNameOfASchemalessRecordMeetsAllThreeNameRules() {
        String cyrillicPass = new String(new int[] {0x0440, 0x0430, 0x0455, 0x0455}, 0, 4);
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null)));

        // Mixed within one name: the per-name script rule fires, and fires first.
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT, Diagnostic.Code.CONFUSABLE_NAMES),
                codesOf(tson, "{ admin: 1  \u0430dmin: 2 }"));

        // Each name single-script and admitted on its own, so only the set has anything to say.
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                codesOf(tson, "{ pass: 1  " + cyrillicPass + ": 2 }"));
    }

    /**
     * <b>A field name is a value of its record's {@code name_type}, so only the schema a document names may judge
     * it.</b> A schemaless record's names are identifiers, and one that is not is {@code ATOM_FORM_INVALID} at its
     * own pointer -- a verdict, so a 400 -- rather than the parse error it was. A document naming a schema this
     * deployment will not supply is not judged by the schemaless rule in its place: its root is skipped unjudged,
     * so the problem a client gets carries the unusable reference alone, not name refusals a schema that admits
     * {@code "first name"} would never have made.
     */
    @Test
    void aFieldNameIsJudgedOnlyByTheSchemaTheDocumentNames() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of()))));

        assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), codesOf(tson, "{ \"first name\": 1 }"));

        var problems = DiagnosticsReceiver.collecting();
        tson.treeReader().withDiagnostics(problems)
                .read("!!import:\"https://s.example.com/2026/39/p-1.tn\"\n{ \"first name\": 1 }");
        assertEquals(List.of(Diagnostic.Code.SCHEMA_NOT_PERMITTED),
                problems.diagnostics().stream().map(Diagnostic::code).distinct().toList());
    }

    /**
     * <b>[TSON-DATA] §9.1's nesting bound is enforced, defaults to 64, and is not a verdict.</b> A body past it
     * is a {@code LIMIT_EXCEEDED} diagnostic, not a {@code StackOverflowError} escaping every {@code catch
     * (RuntimeException)}, which is what lets this project answer it at all -- {@code TsonHttpException} maps it
     * to a 413.
     *
     * <p>All three facts are pinned together because each protects a different decision here. The default is
     * what {@code deployment.tn}'s profile publishes when a descriptor states no {@code max_depth}. That it
     * is reported rather than thrown is why the codec meets it as a diagnostic among others. And that
     * {@link Diagnostic.Code#verdict()} calls it false is what keeps the validator demo from labelling an
     * unread document rejected, and what makes 413-not-400 a considered answer rather than an accident.
     */
    @Test
    void theNestingBoundIsEnforcedAndIsNotAVerdict() {
        assertEquals(64, LimitsPolicy.defaults().maxDepth(), "\u00a79.1's own default");
        assertFalse(Diagnostic.Code.LIMIT_EXCEEDED.verdict(), "nothing past the bound was read");

        String deep = "[".repeat(200) + "]".repeat(200);
        assertEquals(List.of(Diagnostic.Code.LIMIT_EXCEEDED),
                codesOf(Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null))), deep));
    }

    /** The codes a schemaless read of {@code document} reports, collected rather than thrown. */
    private static List<Diagnostic.Code> codesOf(Tson tson, String document) {
        var problems = DiagnosticsReceiver.collecting();
        tson.treeReader().withDiagnostics(problems).readWithoutSchema(document);
        return problems.diagnostics().stream().map(Diagnostic::code).distinct().toList();
    }

    /**
     * <b>Name hygiene defaults the two ways round, and a server inherits both.</b> [TSON-DATA] §8.2 makes
     * the restriction level a policy rather than validity, so the defaults are the library's choice and not
     * the format's -- which is exactly why they are pinned here rather than assumed. A <em>declared</em> name
     * defaults to Highly Restrictive, so a schema a request body names is refused for a homograph nobody
     * could have read correctly; a <em>value</em> defaults to unrestricted, because data may legitimately be
     * a Cyrillic display name and a script rule over payload would break ordinary documents to no end.
     *
     * <p>Both halves matter to this project and they pull opposite ways, so asserting one would leave the
     * other free to move. §8.2's "Values" paragraph names this project's own situation -- a service that
     * renders or matches untrusted values -- and says the deployment applies {@code ProcessorConfig.tokenPolicy}
     * knowingly. {@code tson-http} does not build the {@code Tson}, so that decision is the application's;
     * what is fixed here is what it gets if it does not make one.
     */
    @Test
    void aDeclaredNameDefaultsToHighlyRestrictiveAndAValueToUnrestricted() {
        String schema = """
                !!id:"https://example.com/2026/39/app/hygiene-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  rec => { \u0430dmin: text }
                }""";

        String refused = assertThrows(RuntimeException.class,
                () -> Tson.of(ProcessorConfig.defaults()
                        .withSchemaAccess(SchemaAccess.of(u -> schema))).resolve(schema)).getMessage();
        assertTrue(refused.contains("HIGHLY_RESTRICTIVE"), refused);

        // The same text as a value, through a schemaless read: nothing is checked, and nothing should be.
        assertDoesNotThrow(() -> Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(u -> null))).treeReader()
                .readWithoutSchema("{ name: \"\u0430dmin\" }"));
    }

    /**
     * <b>A source returning {@code null} is refused as the contract violation it is</b> — the flipped
     * assertion of a gap that has closed, kept because this is the shape a regression would take.
     *
     * <p>{@code SchemaSource} admits {@code SchemaFetchException} and nothing else for "cannot supply
     * this", but the natural first implementation is a map and a map spells absence as {@code null}. Left
     * unchecked, that null is a bare {@code NullPointerException} frames from the cause — and since the identity
     * comes from the request body, a 500 any client could produce at will. The refusal says what is wrong, and
     * {@code ofMap} exists so the mistake need not be made.
     */
    @Test
    void aSourceReturningNullIsRefusedRatherThanThrowingAnNpe() {
        // The import is what reaches the source at all: the kernel vocabulary and core are pre-loaded, so a schema
        // naming only those never consults it and the null is never returned.
        String schema = """
                !!id:"https://example.com/2026/39/app/null-source-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"https://example.com/2026/39/app/absent-1.tn"
                !schema { thing => { a: text } }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null)));

        RuntimeException refused = assertThrows(RuntimeException.class, () -> tson.resolve(schema));
        assertFalse(refused instanceof NullPointerException,
                () -> "a null return should be named, not dereferenced: " + refused);
        assertTrue(refused.getMessage() != null && refused.getMessage().contains("null"), refused.getMessage());
    }

    /**
     * And {@code ofMap} is the form that removes the trap: it refuses a miss with {@code NOT_PERMITTED}, and
     * compares by canonical identity, so a reference differing only in scheme or {@code ?sha256=} pin still
     * resolves (§2.2.1). Three demos here carried a private helper doing the first half and not the second.
     *
     * <p>{@code NOT_PERMITTED} rather than {@code NOT_FOUND} because the map is this deployment's whole
     * configuration: a schema outside it is one this deployment will not supply, a refusal, where {@code
     * NOT_FOUND} is a source that looked beyond its configuration and found nothing. Both answer 400.
     */
    @Test
    void ofMapRefusesAMissAndComparesByCanonicalIdentity() {
        String schema = """
                !!id:"https://example.com/2026/39/app/ofmap-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema { thing => { a: text } }""";
        SchemaSource source =
                SchemaSource.ofMap(Map.of("https://example.com/2026/39/app/ofmap-1.tn", schema));

        // The scheme is a transport hint, not part of the name.
        assertDoesNotThrow(() -> source.fetch("http://example.com/2026/39/app/ofmap-1.tn"));

        SchemaFetchException refused = assertThrows(SchemaFetchException.class,
                () -> source.fetch("https://example.com/2026/39/app/absent-1.tn"));
        assertEquals(SchemaFetchException.Reason.NOT_PERMITTED, refused.reason());
    }

    // ── open: RFC 6901 over TSON's map keys ─────────────────────────────────────────────────────

    private static final String KEYS_ID = "https://s.example.com/2026/39/keys-1.tn";

    private static Tson keysTson() {
        String schema = """
                !!id:"https://s.example.com/2026/39/keys-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  point => { x: int32  y: int32 }
                  tally => { counts?: {int32 => int32}  grid?: {point => int32}  tags?: {text => int32} }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        return tson;
    }

    private static List<String> pathsOf(List<Diagnostic> problems) {
        return problems.stream().map(d -> d.path().orElse("<none>")).toList();
    }

    /**
     * <b>Open: a map key's RFC 6901 reference token is its spelling, not its value.</b> A diagnostic's {@code path}
     * is an RFC 6901 pointer by {@code Diagnostic}'s own contract, and RFC 6901 assumes string keys, where a TSON
     * key may be any value ([TSON-DATA] §2.6). {@code 0x10}, {@code 16} and {@code 1_6} are one key -- the duplicate
     * is caught -- and three pointers. Both encodings agree, a JSON member name being able to spell the same
     * forms, so this is about one key having many pointers rather than about parity. Staged in {@code UPSTREAM.md}
     * under {@code json_pointer}; fails the day a key's token is its canonical text.
     */
    @Test
    void aMapKeysPointerTokenIsItsSpellingNotItsValue() {
        Tson tson = keysTson();
        String head = "!!import:\"" + KEYS_ID + "\"\n";

        assertEquals(List.of("/counts/0x10"), pathsOf(tson.validate(head + "!tally { counts: { 0x10 => oops } }")));
        assertEquals(List.of("/counts/16"), pathsOf(tson.validate(head + "!tally { counts: { 16 => oops } }")));
        assertEquals(List.of("/counts/1_6"), pathsOf(tson.validate(head + "!tally { counts: { 1_6 => oops } }")));

        // ...and yet one key: identity compares the decoded value.
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY),
                tson.validate(head + "!tally { counts: { 0x10 => 1  16 => 2 } }").stream()
                        .map(Diagnostic::code).toList());

        Json json = Json.standard().withSchemas(tson.schemaRegistry());
        assertEquals(List.of("/counts/0x10"),
                pathsOf(json.validate("{\"counts\": {\"0x10\": \"oops\"}}", KEYS_ID, "tally")));
        assertEquals(List.of("/counts/16"),
                pathsOf(json.validate("{\"counts\": {\"16\": \"oops\"}}", KEYS_ID, "tally")));
    }

    /**
     * <b>Open: a compound key has no reference token, and the two encodings disagree about it.</b> TSON writes the
     * decoded key's Java {@code toString} -- {@code MapAbstractReader} replaces the placeholder with {@code
     * String.valueOf(key)} for any key that is not a single token -- which is not a location, and in bind mode names
     * the bound class ({@code /grid/Point[x=1, y=2]}) in a path a 400 hands the client. JSON, which carries a
     * compound-keyed map as an array of pairs, reports a real one into that array. The same entry, the same failure,
     * two pointers. Escaping is not the issue: {@code a/b~c} is {@code a~1b~0c} in both, as RFC 6901 requires.
     */
    @Test
    void aCompoundKeyHasNoPointerTokenAndTheEncodingsDisagree() {
        Tson tson = keysTson();
        String head = "!!import:\"" + KEYS_ID + "\"\n";
        Json json = Json.standard().withSchemas(tson.schemaRegistry());

        assertEquals(List.of("/grid/TsonRecord[fields={x=1, y=2}, typeRef=Optional[point], annotations=[]]"),
                pathsOf(tson.validate(head + "!tally { grid: { {x: 1 y: 2} => oops } }")));
        assertEquals(List.of("/grid/0/1"),
                pathsOf(json.validate("{\"grid\": [[{\"x\": 1, \"y\": 2}, \"oops\"]]}", KEYS_ID, "tally")));

        assertEquals(List.of("/tags/a~1b~0c"), pathsOf(tson.validate(head + "!tally { tags: { \"a/b~c\" => oops } }")));
        assertEquals(List.of("/tags/a~1b~0c"),
                pathsOf(json.validate("{\"tags\": {\"a/b~c\": \"oops\"}}", KEYS_ID, "tally")));
    }

    // ── open: a JSON document cannot name its own binding ───────────────────────────────────────

    /**
     * <b>[TSON-JSON] §3.4's in-band route is not built</b>: a root annotation object led by {@code $import} is how a
     * JSON document names its own binding, and §3.5 requires it to agree with a {@code TSON-Schema} header by
     * canonical identity. tson-java's JSON reader builds only the out-of-band route, and declines an {@code $import}
     * at the root as {@code NOT_IMPLEMENTED} -- so a spec-valid body that names its binding twice, in agreement, is
     * refused today. {@code TsonHttpCodec.acceptingJson} documents it; this is what fails
     * the day it lands, so the codec can then check the agreement rather than leave it to the reader.
     *
     * <p>Asserted through {@code Json} directly, over the same schema the header route would name, so the refusal
     * is the reader's and not this project's media-type gate.
     */
    @Test
    void aJsonDocumentsInBandBindingIsRefused() {
        String schemaId = "https://s.example.com/2026/39/j-1.tn";
        String schema = """
                !!id:"https://s.example.com/2026/39/j-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    note => { title: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        Json json = Json.standard().withSchemas(tson.schemaRegistry());

        // Out of band: the binding from outside, the body a bare value. Built.
        assertEquals(List.of(), json.validate("{\"title\": \"t\"}", schemaId, "note"));

        // In band, agreeing with it exactly: refused, where §3.4 makes it valid.
        List<Diagnostic> inBand = json.validate(
                "{\"$import\": [\"" + schemaId + "\"], \"$type\": \"note\", \"title\": \"t\"}", schemaId, "note");
        assertFalse(inBand.isEmpty(), "the in-band route has landed -- check the agreement in TsonHttpCodec");
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), inBand.stream().map(Diagnostic::code).toList(),
                () -> "" + inBand);
        assertTrue(inBand.getFirst().message().contains("'$import'"), () -> "" + inBand);
    }

    // ── open: the JSON bind registry refuses the kernel under the bind context Tson composes ─────────

    /**
     * <b>{@code Json} given {@code Tson.dataBindContext()} binds no document at all.</b> That context answers the
     * kernel's own vocabulary with the schema model's classes, and the JSON bind registry, compiling a closure,
     * checks those records against their classes and refuses the first it meets -- {@code net.iri_type}'s fixed
     * booleans -- and every closure holds the kernel. The same reader given the context the application configured
     * binds. The TSON registry binds under the composed context, so the two encodings disagree on one value.
     *
     * <p>{@code TsonHttpCodec.acceptingJson} works round it, handing the JSON reader the composed context with the
     * kernel's vocabulary left unbound. When this flips, that filter can go.
     */
    @Test
    void jsonBindingUnderTheComposedBindContextRefusesTheKernel() {
        String id = "https://s.example.com/2026/39/j-2.tn";
        String schema = """
                !!id:"https://s.example.com/2026/39/j-2.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    note => { title: text }
                }""";
        ProcessorConfig configured = ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(id, schema))))
                .withDataBindContext(TsonBindings.of(Map.of("note", Note.class)));
        Tson tson = Tson.of(configured);
        tson.resolve(schema);
        assertEquals(new Note("t"), tson.objectReader().withSchema(id).readAs("{ title: t }", "note", Note.class),
                "the TSON reader binds under the composed context");

        Json composed = Json.of(ProcessorConfig.defaults().withDataBindContext(tson.dataBindContext()))
                .withSchemas(tson.schemaRegistry());
        String refused = assertThrows(RuntimeException.class, () -> composed.objectReader().withSchema(id)
                .readAs("{\"title\": \"t\"}", "note", Note.class)).getMessage();
        assertTrue(refused.contains("'net.iri_type' and io.ltr8.tson.schema.net.IriType do not agree"), refused);

        Json asConfigured = Json.of(configured).withSchemas(tson.schemaRegistry());
        assertEquals(new Note("t"), asConfigured.objectReader().withSchema(id)
                .readAs("{\"title\": \"t\"}", "note", Note.class));
    }

    // ── open: the JSON readers state one schema ─────────────────────────────────────────────────────

    /**
     * <b>The JSON readers state one schema, where the TSON readers state several.</b> A {@code TSON-Schema} List
     * names a binding of several schemas, read as one namespace ([TSON-JSON] §3.4, §3.5); a TSON body is read under
     * all of them through {@code withImports}, and a JSON body only under the one {@code withSchema} takes. Fails the
     * day either JSON reader can state several.
     */
    @Test
    void theJsonReadersStateOneSchema() {
        assertDoesNotThrow(() -> io.ltr8.tson.compiler.TsonObjectReader.class.getMethod("withImports", List.class));
        assertDoesNotThrow(() -> io.ltr8.tson.compiler.TsonTreeReader.class.getMethod("withImports", List.class));
        for (Class<?> reader : List.of(io.ltr8.tson.json.JsonObjectReader.class,
                io.ltr8.tson.json.JsonTreeReader.class)) {
            assertTrue(java.util.Arrays.stream(reader.getMethods()).noneMatch(method ->
                            method.getName().startsWith("with") && method.getParameterCount() == 1
                                    && method.getParameterTypes()[0] == List.class),
                    reader.getSimpleName() + " states several schemas now -- read a JSON body under its whole binding "
                            + "in TsonSchemaVersions.Routed, delete UPSTREAM.md's entry and flip this");
        }
    }

    // ── open: an unresolved name a bundled library declares does not name the library ────────────

    /**
     * <b>A schema naming {@code ipv4} without importing {@code net.tn} is told only that the reference is
     * unresolved</b> -- which reads like a typo, where the fix is an import. {@code core.tn} imports {@code net.tn},
     * so a schema importing core sees it; one importing only {@code temporal.tn} meets exactly this message.
     * The bundled libraries are fixed and known, so the resolver could say which one declares the name. Fails
     * the day the message names the library.
     */
    @Test
    void anUnresolvedNameABundledLibraryDeclaresDoesNotNameTheLibrary() {
        String schema = """
                !!id:"https://example.com/2026/39/app/net-hint.tn"
                !!import:"https://tson.io/2026/39/m/temporal.tn"
                !schema { server => { addr: ipv4 } }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(uri -> schema)));

        List<Diagnostic> problems = tson.validateSchema(schema);

        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList());
        assertTrue(problems.getFirst().message().contains("unresolved reference 'ipv4'"), problems::toString);
        assertFalse(problems.getFirst().message().contains("net.tn"),
                "the message names the library now -- delete UPSTREAM.md's entry and flip this");
        // And the import is the whole fix.
        assertEquals(List.of(), tson.validateSchema(schema.replace("net-hint.tn", "net-hint-2.tn")
                .replace("!schema { server", "!!import:\"https://tson.io/2026/39/m/net.tn\"\n!schema { server")));
    }

    /**
     * <b>A schema importing nothing sees no kernel name, and is not told where {@code text} lives.</b> Valid, and
     * its namespace is its own entries alone, so {@code text} is unresolved with no word that {@code meta.tn}
     * declares it. Only the first unresolved field of the record is reported. Fails the day the message names the
     * document.
     */
    @Test
    void aSchemaImportingNothingSeesNoKernelName() {
        String nothing = """
                !!id:"https://example.com/2026/39/app/imports-nothing.tn"
                !schema { a => { b: text  c: integer } }""";
        Tson tson = Tson.of(ProcessorConfig.defaults());

        List<Diagnostic> problems = tson.validateSchema(nothing);

        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList());
        assertEquals("'a' field 'b' has an unresolved reference 'text'", problems.getFirst().message());
        assertFalse(problems.getFirst().message().contains("meta.tn"),
                "the message names the declaring document now -- update UPSTREAM.md's entry and flip this");
        // Its own entries resolve: importing nothing is valid.
        assertEquals(List.of(), tson.validateSchema("""
                !!id:"https://example.com/2026/39/app/imports-nothing-2.tn"
                !schema { a => { b: c }  c => { } }"""));
    }

    // ── open: a template's bare name as a field type resolves silently ──────────────────────────────

    /**
     * <b>{@code x => { s: tmpl }}, {@code tmpl} a template, loads with no diagnostic</b> -- declared locally or
     * brought by an import. A template is not a type until it is applied, and the read shows it: every value at the
     * field is refused as standing where an abstract type with no subtypes is, which blames the closure for a slip
     * in the schema. Fails the day the schema is refused.
     */
    @Test
    void aTemplatesBareNameAsAFieldTypeResolvesSilently() {
        String local = """
                !!id:"https://example.com/2026/39/app/bare-template.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  tmpl => <T> { v: T }
                  x    => { s: tmpl }
                }""";
        String library = """
                !!id:"https://example.com/2026/39/app/bare-template-lib.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema { tmpl => <T> { v: T } }""";
        String importer = """
                !!id:"https://example.com/2026/39/app/bare-template-importer.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"https://example.com/2026/39/app/bare-template-lib.tn"
                !schema { x => { s: tmpl } }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of("https://example.com/2026/39/app/bare-template-lib.tn", library)))));

        assertEquals(List.of(), tson.validateSchema(importer), "across an import");

        Tson reading = Tson.of(ProcessorConfig.defaults());
        reading.resolve(local);
        List<Diagnostic> problems = reading.validate("""
                !!import:"https://example.com/2026/39/app/bare-template.tn"
                !x { s: { v: 1 } }""");
        assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH), problems.stream().map(Diagnostic::code).toList(),
                "loaded locally, and refused only when a value arrives");
        assertTrue(problems.getFirst().message().startsWith("no schema in this closure declares a subtype of "
                + "'tmpl', and it is abstract"), problems::toString);
    }

    // ── open: Tson.validate on a schema document blames Class 1 ─────────────────────────────────────

    /**
     * <b>{@code Tson.validate} handed a {@code !schema}-rooted document reports a Class 1 limitation</b>, from a
     * schema-aware {@code Tson}. Refusing is right, since {@code validate} reads data; the message names the wrong
     * cause, and the validator demo shows it to whoever pasted a schema into the data box. Fails the day it names
     * {@code validateSchema}.
     */
    @Test
    void validateOnASchemaDocumentBlamesClassOne() {
        Tson tson = Tson.of(ProcessorConfig.defaults());

        List<Diagnostic> problems = tson.validate("""
                !!id:"https://example.com/2026/39/app/pasted-schema.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema { a => { b: text } }""");

        assertEquals(List.of(Diagnostic.Code.VALIDATION_ERROR), problems.stream().map(Diagnostic::code).toList());
        assertEquals("this is a TSON schema document (its root is tagged !schema); a Class 1 (data-format-only) "
                + "processor does not support schema documents", problems.getFirst().message());
        assertFalse(problems.getFirst().message().contains("validateSchema"),
                "the message points at validateSchema now -- delete UPSTREAM.md's entry and flip this");
    }

    // ── fixed upstream: an identity's host is a host value, in either label form ─────────────────────

    /**
     * <b>A non-ASCII host is one identity in either label form, and the allow-list takes it.</b> Canonical
     * identity reads an identity's host as a {@code host} value, so {@code bücher.example} and its A-label
     * spelling {@code xn--bcher-kva.example} are one identity, canonically the U-label; and {@link
     * HttpSchemaSource#builder()}'s {@code allowHost} accepts the U-label rather than refusing it as no host at
     * all. Pinned so a regression to two identities, or a refusal, fails here.
     */
    @Test
    void aNonAsciiHostIsOneIdentityInEitherLabelForm() {
        assertEquals(CanonicalIdentity.canonicalize("https://bücher.example/a.tn"),
                CanonicalIdentity.canonicalize("https://xn--bcher-kva.example/a.tn"));
        assertEquals("bücher.example/a.tn", CanonicalIdentity.canonicalize("https://xn--bcher-kva.example/a.tn"));
        assertDoesNotThrow(() -> HttpSchemaSource.builder().allowHost("bücher.example").build().close());
    }

    // ── fixed upstream: an import crosses no level ──────────────────────────────────────────────────

    /**
     * <b>An import crosses no level</b>: a schema's namespace is its own entries and its import closure's, whatever
     * either declares: one namespace per schema ([TSON-SCHEMA] §2.2.3, §3.4). An import is held to no level, in either
     * direction, and each direction is pinned so a rule holding it to one fails here:
     *
     * <ul>
     *   <li>a layer declaring a namespace type imports an ordinary library and uses its atom in a record the
     *       namespace holds -- how {@code meta-service.tn} takes {@code net.tn}'s {@code media_type};</li>
     *   <li>an ordinary schema imports a library applying a kernel constructor and uses the type it builds.</li>
     * </ul>
     *
     * A kernel name reaches a schema only by import, so each library imports {@code meta.tn} itself.
     */
    @Test
    void anImportCrossesNoLevel() {
        String library = """
                !!id:"https://example.com/2026/39/app/levels-lib.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema { stock_code => !unicode.text_type { pattern: "[A-Z]{3}" } }""";
        String layer = """
                !!id:"https://example.com/2026/39/app/levels-layer.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://example.com/2026/39/app/levels-lib.tn"
                !schema {
                  item  => { code: stock_code }
                  items => !meta.namespace { key_type: meta.identifier  value_type: item }
                }""";
        Tson layerOverLibrary = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(
                SchemaSource.ofMap(Map.of("https://example.com/2026/39/app/levels-lib.tn", library)))));
        assertEquals(List.of(), layerOverLibrary.validateSchema(layer));

        String kernelLibrary = """
                !!id:"https://example.com/2026/39/app/levels-kernel-lib.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema { status_code => !numeric.integer_type { min: 100  max: 599 } }""";
        String application = """
                !!id:"https://example.com/2026/39/app/levels-app.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"https://example.com/2026/39/app/levels-kernel-lib.tn"
                !schema { reply => { status: status_code } }""";
        Tson schemaOverLibrary = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(
                SchemaSource.ofMap(Map.of("https://example.com/2026/39/app/levels-kernel-lib.tn", kernelLibrary)))));
        assertEquals(List.of(), schemaOverLibrary.validateSchema(application));
    }

    // ── decided upstream: a stated schema overrides the document's own ──────────────────────────────

    /**
     * <b>{@code readAs} against a stated schema overrides the {@code !!import}s the document names itself</b> --
     * even one naming a different schema, which is read as the stated one with no diagnostic. Not a gap: upstream's
     * decision, stated on {@code TsonObjectReader.readAs(TsonDocumentPeek, …)} -- "any {@code !!import} the
     * document declares is overridden … a caller holding both … compares {@code peek.header()} against its own".
     *
     * <p>So the comparison is this project's to make, and {@code TsonHttpCodec} makes it: its out-of-band reads
     * peek a TSON body and answer imports that are not the stated set 400, the posture [TSON-JSON] §3.5 takes for
     * the header. Pinned because the codec's check exists only because the reader does not refuse -- if upstream ever
     * starts refusing, this fails and the codec's own check becomes a second copy of the rule.
     */
    @Test
    void aStatedSchemaSilentlyOverridesTheDocumentsOwn() {
        String id = "https://s.example.com/2026/39/j-1.tn";
        String schema = """
                !!id:"https://s.example.com/2026/39/j-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    note => { title: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(id, schema))))
                .withDataBindContext(TsonBindings.of(Map.of("note", Note.class))));
        tson.resolve(schema);
        String elsewhere = """
                !!import:"https://s.example.com/2026/39/other-1.tn"
                !note { title: t }""";

        assertEquals(new Note("t"), tson.objectReader().withSchema(id).readAs(elsewhere, "note", Note.class),
                "the object reader read a document naming other-1.tn as j-1.tn, and said nothing");
        assertEquals("t", tson.treeReader().withSchema(id).readAs(elsewhere, "note")
                .get("title").asString().orElseThrow(), "and so did the tree reader");
    }

    /** Bound by {@link #aStatedSchemaSilentlyOverridesTheDocumentsOwn}. */
    public record Note(String title) {
    }

    // ── open: a component narrower than its field's value space binds, and fails at the read ─────────────

    /**
     * <b>A component narrower than its field's value space is a bind mismatch at startup, never a read-time
     * error</b> -- the contract wanted, and the one {@code TsonHttpCodec.prepareToRead} exists for. Today strict
     * binding accepts an {@code int} component against an {@code int64} field: the bind registry prepares the
     * schema, a value in {@code int} range reads, and a valid document stating one past it fails at the read with
     * a bare {@link ArithmeticException}, no {@code ReadException} and no {@link Diagnostic.Code} to classify, so
     * a server answers a document with nothing wrong with it with a 500. This project's exposure was {@code
     * TsonDeployment.Policy.Limits}, whose components are all {@code long} for this reason. Fails the day
     * preparing the schema refuses the component.
     */
    @Test
    void aComponentNarrowerThanItsFieldBindsAndFailsAtTheRead() {
        String id = "https://s.example.com/2026/39/narrow-1.tn";
        String schema = """
                !!id:"https://s.example.com/2026/39/narrow-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    x => { n: int64 }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(id, schema))))
                .withDataBindContext(TsonBindings.of(Map.of("x", Narrow.class))));
        tson.resolve(schema);

        assertDoesNotThrow(() -> tson.bindRegistry().get(id),
                "preparing the schema refuses the int component now -- delete UPSTREAM.md's entry and flip this");
        assertEquals(new Narrow(7), tson.objectReader().withSchema(id).readAs("{ n: 7 }", "x", Narrow.class));

        String past = "{ n: 3000000000 }";
        assertEquals(List.of(), tson.validate("!!import:\"" + id + "\"\n!x " + past), "the document is valid");
        ArithmeticException thrown = assertThrows(ArithmeticException.class,
                () -> tson.objectReader().withSchema(id).readAs(past, "x", Narrow.class));
        assertEquals("BigInteger out of int range", thrown.getMessage());
    }

    /** Bound by {@link #aComponentNarrowerThanItsFieldBindsAndFailsAtTheRead}: an {@code int} for an {@code int64}. */
    public record Narrow(int n) {
    }

    // ── fixed upstream: bind mode continues a peek against a stated type ──────────────────────────────

    /**
     * <b>The object reader continues a peek against a stated type</b> -- {@code readAs(TsonDocumentPeek, String,
     * Class)}, the counterpart of the tree reader's {@code readAs(peek, typeName)}. It is what lets a body whose
     * schema arrives in a {@code TSON-Schema} header be peeked, checked against its own {@code !!import}, and bound
     * in one pass, which {@code TsonHttpCodec.readObjectAs} and {@code TsonSchemaVersions.Routed.readObjectAs}
     * both rely on. Over a stream that cannot be rewound, so a peek that consumed the body would fail here.
     */
    @Test
    void theObjectReaderContinuesAPeekAgainstAStatedType() {
        String id = "https://s.example.com/2026/39/j-1.tn";
        String schema = """
                !!id:"https://s.example.com/2026/39/j-1.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                    note => { title: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(id, schema))))
                .withDataBindContext(TsonBindings.of(Map.of("note", Note.class))));
        tson.resolve(schema);
        java.io.InputStream unmarkable = new java.io.FilterInputStream(new java.io.ByteArrayInputStream(
                "{ title: t }".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void reset() throws java.io.IOException {
                throw new java.io.IOException("this stream cannot be rewound");
            }
        };

        var peek = tson.begin(unmarkable);
        assertEquals(java.util.List.of(), peek.header().imports(), "the header imports no schema");
        assertEquals(new Note("t"), tson.objectReader().withSchema(id).readAs(peek, "note", Note.class));
    }

    // ── a dependent field: the sender chooses the type a value is read against ─────────────────────────

    private static final String NOTES_ID = "https://s.example.com/2026/39/notes-1.tn";

    /** A free sibling over the whole closure, beside a record no class binds. */
    private static final String NOTES = """
            !!id:"https://s.example.com/2026/39/notes-1.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
                point      => { x: int32  y: int32 }
                secret     => { s: text }
                typed_note => { note_id: text  kind: meta.type_ref  payload: kind! }
            }""";

    /** The bind target for {@code typed_note}: a dependent field binds to {@code Object}, what the named type reads to. */
    public record TypedNote(String note_id, TypeRef kind, Object payload) {
    }

    /** The bind target for {@code point}. */
    public record Point(int x, int y) {
    }

    private static Tson notes(io.ltr8.bind.DataBindContext context) {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(NOTES_ID, NOTES))))
                .withDataBindContext(context));
        tson.resolve(NOTES);
        return tson;
    }

    /**
     * <b>A dependent field lets the sender choose a type nothing binds, and the read throws {@code
     * MissingBindingException}</b>, which {@link TsonHttpException#from} answers 500: a server-fault status a client
     * produces at will by naming a type of the closure the application never mapped ([TSON-SCHEMA] §5.2 says nothing
     * of a sender choosing the type). {@code TsonApiDescription} closes it at startup ({@code ChosenTypes}); this pins
     * the raw read, so a library that refuses the choice as the sender's fault flips it.
     */
    @Test
    void aDependentFieldsSiblingNamingATypeNothingBindsIsAMissingBinding() {
        Tson tson = notes(TsonBindings.of(Map.of("typed_note", TypedNote.class, "point", Point.class)));
        String header = "!!import:\"" + NOTES_ID + "\"\n";
        TypedNote point = tson.objectReader().read(header
                + "!typed_note { note_id: n  kind: point  payload: { x: 1  y: 2 } }", TypedNote.class);
        assertEquals(new Point(1, 2), point.payload());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> tson.objectReader().read(header
                + "!typed_note { note_id: n  kind: secret  payload: { s: x } }", TypedNote.class));
        assertInstanceOf(io.ltr8.tson.base.MissingBindingException.class, thrown,
                "the chosen type is refused as the sender's now -- flip this and revisit ChosenTypes");
        assertTrue(thrown.getMessage().startsWith("no bound Java class for 'secret': nothing in this bind context"
                + " resolves that schema type name"), thrown.getMessage());
        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, TsonHttpException.from(thrown).status());
    }

    /**
     * <b>Over the JSON encoding a free sibling is written before its dependent field</b> ([TSON-JSON] §6.1.6), so
     * member order decides validity where RFC 8259 §4 calls an object an unordered collection: a client that sorts
     * its keys writes {@code payload} before {@code kind} and is refused, {@code VALIDATION_ERROR} at {@code
     * /payload}, a 400. The status is the spec's rule, kept; this pins it so a change of rule is seen.
     */
    @Test
    void aJsonBodyWritingADependentFieldBeforeItsSiblingIsRefused() {
        TsonHttpCodec json = new TsonHttpCodec(notes(TsonBindings.of(
                Map.of("typed_note", TypedNote.class, "point", Point.class)))).acceptingJson();
        java.util.function.Function<String, java.io.InputStream> body =
                text -> new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(new Point(1, 2), json.readObjectAs(body.apply(
                "{\"note_id\":\"n\",\"kind\":\"point\",\"payload\":{\"x\":1,\"y\":2}}"),
                "application/json", NOTES_ID, "typed_note", TypedNote.class).payload());

        TsonHttpException sorted = assertThrows(TsonHttpException.class, () -> json.readObjectAs(body.apply(
                "{\"note_id\":\"n\",\"payload\":{\"x\":1,\"y\":2},\"kind\":\"point\"}"),
                "application/json", NOTES_ID, "typed_note", TypedNote.class));
        assertEquals(TsonHttpException.BAD_REQUEST, sorted.status());
        TsonProblemDiagnostic only = sorted.problem().errors().getFirst();
        assertEquals(1, sorted.problem().errors().size(), () -> "" + sorted.problem().errors());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, only.code());
        assertEquals(java.util.Optional.of("/payload"), only.path());
        assertTrue(only.message().startsWith("field 'payload' on 'typed_note' is written before 'kind', the sibling"
                + " whose value names its type -- a sibling that is neither pinned nor a discriminator is written"
                + " before the field that depends on it"), only.message());
    }

    /**
     * <b>A mapped class the context cannot analyse is deferred as a missing binding</b>: a dependent field binds to
     * {@code Object}, which a context binds only where it registers {@code Object} as an atom. Without it the bind
     * registry prepares the schema clean and every read of the record throws {@code MissingBindingException}, a
     * 500 per request, where the class the map names is a mismatch the startup step could refuse. {@link
     * TsonBindings} registers the atom, so this project is not exposed; fails the day preparing the schema refuses
     * the class.
     */
    @Test
    void aMappedClassThatCannotBeAnalysedPreparesCleanAndFailsEachRead() {
        Map<String, Class<?>> map = Map.of("typed_note", TypedNote.class, "point", Point.class);
        Tson tson = notes(io.ltr8.bind.DataBindContext.builder().nameBinder(map::get)
                .registerAtoms(AtomContext.hostTypes()).build());

        assertDoesNotThrow(() -> tson.bindRegistry().get(NOTES_ID),
                "preparing the schema refuses the class now -- delete UPSTREAM.md's entry and flip this");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> tson.objectReader().read(
                "!!import:\"" + NOTES_ID + "\"\n!typed_note { note_id: n  kind: int32  payload: 5 }", TypedNote.class));
        assertInstanceOf(io.ltr8.tson.base.MissingBindingException.class, thrown);
        assertTrue(thrown.getMessage().contains("which cannot be analysed: Failed to resolve: Unable to find a valid"
                + " data conversion for class: class java.lang.Object"), thrown.getMessage());
    }

    // ── open: a record field's type through an accessor that throws on a dependent field ────────────────

    /**
     * <b>{@code RecordField.typeRef()} throws on a dependent field</b>, whose type is a {@link SiblingRef}: one fact
     * of a schema that loaded clean, answered through an accessor that turns it into an {@code
     * IllegalStateException} for a consumer that reads a schema it did not write. {@code type()} is the sealed
     * {@code Reference} the field holds, and is what {@code RequestPlacement} and {@code ChosenTypes} match on. The
     * contract wanted is no accessor that throws on a valid schema: the sealed reference alone, or {@code typeRef()}
     * answering with an {@code Optional}. Fails the day the call returns, or stops compiling when the accessor changes.
     */
    @Test
    void aDependentFieldsTypeRefThrowsWhereItsTypeIsASiblingRef() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(NOTES_ID, NOTES)))));
        tson.resolve(NOTES);
        RecordField payload = ((RecordBody) tson.schemaRegistry().get(NOTES_ID).orElseThrow().schema().entries()
                .get("typed_note")).fields().get("payload");

        assertEquals("kind", assertInstanceOf(SiblingRef.class, payload.type()).field());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, payload::typeRef,
                "typeRef() answers a dependent field now -- update UPSTREAM.md's entry and flip this");
        assertTrue(thrown.getMessage().startsWith("a dependent field ('kind!') names no type of its own"),
                thrown.getMessage());
    }

    // ── open: two diagnostics name internals, and one misses the fault ──────────────────────────────────

    /** A protocol layer as {@code meta-service.tn} has it: exchanges, each naming its request type, under a name. */
    private static final String EXCHANGES = meta("""
              exchange      => { request?: meta.type_ref }
              exchange_name => meta.identifier
              protocol      => !meta.namespace { key_type: exchange_name  value_type: exchange }""");

    /**
     * <b>A sibling typed by an inline {@code meta.ref<exchange>} is refused naming the entry the application lifted
     * to</b>, {@code reference_type_exchange_false_<hash>}, where the author wrote {@code meta.ref<exchange>}. The
     * refusal is right (a reference over values names no type, [TSON-SCHEMA] §5.2 as proposed); the name in it is not
     * one the author can find. {@code DependentEnvelopeProbe} measures the same over the orders protocol. The contract
     * wanted is the reference as written, as the reader already names an application in data. Fails the day the
     * message names {@code meta.ref<exchange>}.
     */
    @Test
    void aSiblingTypedByAnInlineValueReferenceIsRefusedUnderAMintedName() {
        String doc = governed("  by_exchange => { exchange: meta.ref<exchange>  body: exchange! }");

        List<Diagnostic> problems = tson(EXCHANGES, doc).validateSchema(doc);

        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        String message = problems.getFirst().message();
        assertTrue(message.startsWith("'by_exchange': field 'body' takes its type from 'exchange', which is typed"
                + " 'reference_type_exchange_false_"), message);
        assertFalse(message.contains("'meta.ref<exchange>'"),
                "the refusal names the reference as written now -- update UPSTREAM.md's entry and flip this");
        assertTrue(message.contains("-- a sibling is typed by a reference whose values name types, meta.type_ref,"
                + " a meta.ref<C> over a constructor, or a refinement of either"), message);
    }

    /**
     * <b>An exchange's qualified name at a {@code meta.type_ref} slot is refused with every member of the closure
     * and no word of the fault</b>: {@code orders.place_order} resolves, to a value of {@code exchange}, and a value
     * names no type. The message lists the members, minted entries shown by their application ({@code [text; 1..]})
     * among them, and {@code expected} repeats the list, so a diagnostic printed whole carries it twice. The contract
     * wanted is the fault named ("names a value of 'exchange', not a type") and the list dropped or capped. Fails the
     * day the message says the name is a value's, or stops repeating the list in {@code expected}.
     */
    @Test
    void anExchangesNameAtATypeRefSlotIsRefusedWithTheClosureListedTwice() {
        String doc = governed("""
                  point   => { x: int32  y: int32 }
                  orders  => !protocol { place_order => { request: point } }
                  by_type => { request: meta.type_ref  body: request! }""");
        Tson tson = tson(EXCHANGES, doc);
        tson.resolve(doc);

        List<Diagnostic> problems = tson.validate("!!import:\"" + API_ID + "\"\n"
                + "!by_type { request: orders.place_order  body: { x: 1  y: 2 } }");

        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), problems.stream().map(Diagnostic::code)
                .toList(), () -> "" + problems);
        Diagnostic refused = problems.getFirst();
        assertEquals(java.util.Optional.of("/request"), refused.path());
        String message = refused.message();
        String opening = "'meta.type_ref': 'orders.place_order' is not a member of this kind -- expected the name of"
                + " a type whose value IS-A 'meta.type', one of [";
        assertTrue(message.startsWith(opening), message);
        String listed = message.substring(opening.length() - 1);
        assertTrue(listed.contains("[text; 1..]"), "a minted entry shown by its application: " + listed);
        assertEquals("one of (" + listed.substring(1, listed.length() - 1) + ")", refused.expected(),
                "expected no longer repeats the member list -- update UPSTREAM.md's entry");
        assertFalse(message.contains("names a value") || message.contains("not a type"),
                "the message names the fault now -- update UPSTREAM.md's entry and flip this");
    }
}
