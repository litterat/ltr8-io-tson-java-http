package io.ltr8.tson.http;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
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

    private static final String META_ID = "https://tson.io/2026/36/ltr8/http/meta-probe.tn";
    private static final String API_ID = "https://schemas.example.com/2026/36/app/probe-1.tn";

    /** A meta layer with a `data &` constructor, standing in for meta-http without depending on its shape. */
    private static String meta(String declarations) {
        return """
                !!id:"%s"
                !!meta:"https://tson.io/2026/36/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/36/m/meta.tn"
                {
                %s
                }""".formatted(META_ID, declarations);
    }

    private static String governed(String declarations) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }""".formatted(API_ID, META_ID, declarations);
    }

    private static Tson tson(String metaSource, String doc, String... packages) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, metaSource);
        lib.put(API_ID, doc);
        ProcessorConfig config = ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(lib::get));
        if (packages.length > 0) {
            config = config.withMetaNameBinder(
                    new DataNameBinder.DefaultDataNameBinder(Set.of(packages), Map.of()));
        }
        return Tson.of(config);
    }

    // ── fixed upstream, pinned here: a template application inside a choice ─────────────────────

    /**
     * <b>Fixed: {@code (resp<order, 201> | resp<problem, 400>)} resolves.</b> This is the shape an operation's
     * responses want, and it used to be the gap this project's API description worked around by naming each
     * application as its own entry first. The lift now happens for a choice as it already did for a field and
     * an array, so the workaround is a choice of style rather than a necessity.
     *
     * <p>Asserting resolution alone would pass on silence, which is how the old version of this test nearly
     * read as a fix when only the reporting channel had changed. So it asserts the resolved shape: each
     * application becomes its own synthetic entry, the choice names both, and each carries the value argument
     * as a {@link FieldRole#FIXED} field -- which is the part that would be quietly wrong if the substitution
     * were dropped.
     */
    @Test
    void anApplicationInsideAChoiceResolves() {
        String schema = """
                !!id:"https://s.example.com/2026/36/p-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                    order   => { sku: text }
                    problem => { title: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    op      => { response: (resp<order, 201> | resp<problem, 400>) }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null)));

        List<Diagnostic> problems = tson.validateSchema(schema);
        assertEquals(List.of(), problems, () -> "expected a clean resolution, got " + problems);

        var entries = tson.schemaRegistry().get("https://s.example.com/2026/36/p-1.tn").orElseThrow()
                .schema().entries();
        TypeRef response = ((RecordBody) entries.get("op").body()).fields().getFirst().type();
        var variants = assertInstanceOf(ChoiceBody.class, entries.get(response.name()).body()).variants();
        assertEquals(2, variants.size(), () -> "both applications lift: " + variants);

        // 201 with an order, 400 with a problem -- the substitution the synthetic names encode, checked.
        assertEquals(List.of("201", "order", "400", "problem"), variants.stream()
                .map(v -> (RecordBody) entries.get(v.name()).body())
                .flatMap(b -> b.fields().stream())
                .map(f -> f.role() == FieldRole.FIXED
                        ? f.value().orElseThrow().text() : f.type().name())
                .toList());
    }

    // ── fixed upstream, pinned here: a value parameter filling a FIXED field ─────────────────────

    /** {@code status: status_code = S} applied as {@code <order, 201>} both carries 201 and enforces it. */
    @Test
    void aValueParameterFixedFieldConstrains() {
        String schema = """
                !!id:"https://s.example.com/2026/36/p-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                    order    => { sku: text }
                    resp     => <T, S> { status: int32 = S  body: T }
                    created  => resp<order, 201>
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        String header = "!!schema:\"https://s.example.com/2026/36/p-1.tn\"\n";

        assertEquals(List.of(), tson.validate(header
                + "!created { status: 201  body: !order { sku: \"a\" } }"));
        assertTrue(tson.validate(header + "!created { status: 999  body: !order { sku: \"a\" } }").stream()
                        .anyMatch(d -> d.code() == Diagnostic.Code.FIELD_FIXED),
                "a parameter-filled FIXED field constrains -- the whole point of `= S`");
    }

    /**
     * The same, seen through the applied entry: the substituted field is {@link FieldRole#FIXED} rather than
     * merely carrying its value. This is the shape that made the {@code response<T, S>} design unattractive
     * while the constraint was being lost, so it is worth knowing it is now sound. A declaration naming an
     * application is that application's entry ([TSON-SCHEMA] §8.2), so the field is read from {@code created}
     * itself, with no hop onto a minted name.
     */
    @Test
    void aMaterialisedApplicationCarriesAFixedField() {
        String schema = """
                !!id:"https://s.example.com/2026/36/p-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                    order   => { sku: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    created => resp<order, 201>
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        var entries = tson.schemaRegistry().get("https://s.example.com/2026/36/p-1.tn").orElseThrow()
                .schema().entries();

        assertEquals("resp", entries.get("created").source().orElseThrow().name());
        RecordBody body = (RecordBody) entries.get("created").body();
        RecordField status = body.fields().stream().filter(f -> f.name().equals("status"))
                .findFirst().orElseThrow();

        assertEquals("201", status.value().orElseThrow().text());
        assertEquals(FieldRole.FIXED, status.role());
        // Unmarked name, so a marker the document must write (§5.2's `a: T = v`), and never injected.
        assertFalse(status.optional(), "the name is unmarked, so the key must be written");
    }

    // ── fixed upstream: every meta-layer name in a governed schema refuses the same way ──────────

    /**
     * <b>A meta layer is the schema <em>for</em> the schema.</b> Its declarations are the vocabulary a schema
     * is written in, not types that schema may reference — a governed schema applies its constructors as
     * {@code !C { … }} and nothing else. So every form here is refused, and they are all refused in the
     * same words.
     *
     * <p>The one that used to differ was the <em>application</em>, which claimed the arguments were missing
     * when they were written. Not a lookup bug: the desugar pass collapsed the application to its bare head,
     * so the {@code source} lookup found a template through the meta-structure fallback and faulted it for
     * supplying no arguments. Keeping the application whole is what let the linker judge what was actually
     * written.
     */
    @Test
    void everyMetaLayerNameInAGovernedSchemaIsUnresolved() {
        String metaSource = meta("""
                  scalar => !integer ^ { min: 100  max: 599 }
                  plain  => { a: text }
                  tmpl   => <T> { v: T }
                  ctor   => data & { a: text }""");

        record Case(String label, String declaration, String name) {
        }
        List<Case> cases = List.of(
                new Case("an atom", "  x => { s: scalar }", "scalar"),
                new Case("a record", "  x => { s: plain }", "plain"),
                new Case("a constructor as a type", "  x => { s: ctor }", "ctor"),
                new Case("a template, unapplied", "  x => { s: tmpl }", "tmpl"),
                new Case("a template, applied", "  x => tmpl<text>", "tmpl"));

        for (Case one : cases) {
            String doc = governed(one.declaration());
            String message = assertThrows(RuntimeException.class,
                    () -> tson(metaSource, doc).resolve(doc), () -> "expected " + one.label() + " to be refused")
                    .getMessage();
            assertTrue(message.contains("unresolved reference '" + one.name() + "'"),
                    () -> one.label() + ": " + message);
        }
    }

    /** And the control: the same template declared locally applies fine. */
    @Test
    void aLocallyDeclaredTemplateApplies() {
        String metaSource = meta("  ctor => data & { a: text }");
        String doc = governed("  local => <T> { v: T }\n  x => local<text>");

        tson(metaSource, doc).resolve(doc);
    }

    // ── fixed upstream: a Java component the meta layer does not declare ─────────────────────────

    /**
     * A Java component the meta declaration does not declare used to arrive {@code null} and be dereferenced
     * inside {@code Data.references()}, surfacing as an NPE out of {@code Tson.resolve} — a consumer's wiring
     * mistake reading as a library fault. Strict binding names both sides instead.
     */
    @Test
    void aJavaComponentTheMetaDoesNotDeclareIsReported() {
        String metaSource = meta("  operation => data & { method: text  path: text  responses: [type_ref] }");
        String doc = governed("""
                  y  => { a: text }
                  op => !operation { method: "GET"  path: "/x"  responses: [ y ] }""");

        // io.ltr8.tson.http.api.Operation has `summary`, `parameters` and more; this meta declares none.
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.api").resolve(doc));

        assertTrue(thrown.getMessage().contains("parameters") || thrown.getMessage().contains("summary"),
                thrown.getMessage());
    }

    // ── fixed upstream: a templated `data &` constructor declares, and its application is named ─────

    /**
     * <b>The CRUD-family payoff a templated operation gives is available.</b> {@code fetch => <T> !operation
     * { … }}, one declaration standing for every fetch-by-id endpoint, declares, and {@code getOrder =>
     * fetch<order>} names its application. [TSON-SCHEMA] §8.2 makes a declaration naming an application that
     * application's entry, so {@code getOrder} <em>is</em> the materialised {@code kind: DATA} entry, under
     * the author's name and with the {@link io.ltr8.tson.http.api.Operation} body in place -- not an alias
     * onto a minted one, which is what used to be refused for naming something other than a type.
     *
     * <p>So {@link io.ltr8.tson.http.api.TsonApiDescription} finds it the way it finds a written-out one, by
     * what its body is, and follows no hop. Asserted through the description rather than the entries map,
     * because that is the consumer the payoff is for; the written-out form beside it keeps the comparison
     * honest.
     */
    @Test
    void aTemplatedDataConstructorsApplicationIsAnOperation() {
        String template = """
                  order => { sku: text }
                  fetch => <T> !operation {
                    method: GET  path: "/orders/{id}"
                    responses: [ { status: 200  body: T  description: "found" } ]
                  }
                  getOrder => fetch<order>
                  putOrder => !operation {
                    method: PUT  path: "/orders/{id}"
                    request: order
                    responses: [ { status: 200  body: order  description: "replaced" } ]
                  }""";

        var operations = TsonApiSchema.describedBy(resolveAgainstApiMeta(template), API_ID).operations();

        assertEquals(Set.of("getOrder", "putOrder"), operations.keySet(), "the template itself is no operation");
        var getOrder = operations.get("getOrder");
        assertEquals("/orders/{id}", getOrder.path());
        assertEquals("order", getOrder.responseFor(200).orElseThrow().body().orElseThrow().name(),
                "the argument is substituted into the response body");
    }

    /**
     * <b>An operation's path is content, not a name</b> -- kept because an HTTP path is the case that found it
     * and would be the first to regress. A templated operation with {@code path: "/x"} once minted
     * {@code operation_/x_GET_…}, which §8.2 makes a MUST violation ("an internal name is a valid identifier")
     * and which surfaced as a name-hygiene refusal on {@code U+002F} -- a refusal on a name nobody wrote and
     * nobody could edit. A declaration naming the application now mints no name at all, and §8.2's hygiene
     * walks authored names only, so every path resolves -- including one in Cyrillic, which the default
     * identifier policy would refuse for its script were it judged as a name.
     */
    @Test
    void anOperationsPathIsNotJudgedByNameHygiene() {
        String body = """
                  order    => { sku: text }
                  fetch    => <T> !operation {
                    method: GET  path: "%s"
                    responses: [ { status: 200  body: T  description: "found" } ]
                  }
                  getOrder => fetch<order>""";

        for (String path : List.of("x", "/x", "/orders/{id}", "/путь")) {
            var operations = assertDoesNotThrow(() -> TsonApiSchema.describedBy(
                    resolveAgainstApiMeta(body.formatted(path)), API_ID).operations(), path);
            assertEquals(path, operations.get("getOrder").path());
        }
    }

    /**
     * Resolves {@code declarations} as a schema governed by this project's own {@code meta-http-1.tn}. The
     * probe meta the other tests use would do for the parse, but not past it: it declares a deliberately
     * minimal {@code operation}, and once these declarations resolve far enough to bind, that shape disagrees
     * with {@link io.ltr8.tson.http.api.Operation} and the mismatch arrives instead of the answer being asked
     * for. The real meta is also the one whose payoff this is.
     */
    private static Tson resolveAgainstApiMeta(String declarations) {
        String doc = """
                !!id:"%s"
                !!meta:"%s"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }""".formatted(API_ID, TsonApiSchema.ID, declarations);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(u -> TsonApiSchema.ID.equals(u) ? TsonApiSchema.source() : null))
                .withMetaNameBinder(TsonApiSchema.metaNameBinder()));
        tson.resolve(doc);
        return tson;
    }

    // ── not a gap: an entry's two annotation positions ───────────────────────────────────────────

    /**
     * <b>Not a gap, and pinned because it reads like one.</b> A schema entry has two annotation positions and
     * they land in different places: before the name annotates the <em>entry</em> and is read from the entries
     * map, after the arrow annotates the <em>definition</em> and is read from the {@code TypeDefinition}.
     * Checking only the second for an annotation written in the first position looks exactly like the
     * annotation being dropped — which is what makes both retention rules worth asserting.
     */
    @Test
    void anEntrysTwoAnnotationPositionsLandInDifferentPlaces() {
        String schema = """
                !!id:"https://s.example.com/2026/36/p-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                  @doc:"on the entry"
                  before => { a: text }
                  after  => @doc:"on the definition" { a: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        var entries = tson.schemaRegistry().get("https://s.example.com/2026/36/p-1.tn")
                .orElseThrow().schema().entries();

        assertEquals(java.util.Optional.of("on the entry"),
                entries.getAnnotations("before").value("doc", String.class));
        assertEquals(List.of(), entries.get("before").annotations().values(),
                "not here -- looking only here is the mistake");

        assertEquals(java.util.Optional.of("on the definition"),
                entries.get("after").annotations().value("doc", String.class));
    }

    /** And an annotation naming no type is refused, wherever it is written. */
    @Test
    void anUnknownAnnotationOnAnEntryIsRefused() {
        String schema = """
                !!id:"https://s.example.com/2026/36/p-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                  @nosuchtype:"x"
                  thing => { a: text }
                }""";

        assertTrue(assertThrows(RuntimeException.class,
                () -> Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema))).resolve(schema))
                .getMessage().contains("does not name a type"));
    }

    // ── the `data` kind's own guarantee, which nothing else asserts here ─────────────────────────

    /** An operation is not a type, so every position a type-ref can occupy refuses it at link time. */
    @Test
    void aDataEntryCannotBeUsedWhereATypeBelongs() {
        String metaSource = meta("  operation => data & { method: text  path: text  responses: [type_ref] }");
        String doc = governed("""
                  y      => { a: text }
                  op     => !operation { method: "GET"  path: "/x"  responses: [ y ] }
                  holder => { o: op }""");

        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.probe").resolve(doc)).getMessage();

        assertTrue(message.contains("describes something other than a data value"), message);
        assertInstanceOf(TypeRef.class, TypeRef.of("op"));
    }

    // ── filed spec feedback: no template-application sugar at a `type_ref` slot in data ──────────

    /**
     * <b>Not a gap — the spec's design, pinned because what it costs is filed as spec feedback (tson-java's
     * {@code SPEC-FEEDBACK.md}, the entry on a template application at a {@code type_ref} slot in data).</b> [TSON-SCHEMA] §8.1 gives {@code type_ref} a positional form at every {@code type_ref}-typed
     * position: a bare token fills {@code name}, and the braced record is the explicit form, canonical only
     * where {@code arguments} is present. A <em>schema</em> may therefore write {@code page<order>}; a
     * <em>data</em> payload at such a slot — an {@code !operation { … }} governed by a consumer's meta layer
     * — cannot, because {@code <} is not data syntax. So an API description applying one template at four
     * endpoints writes the braced record four times, or names four aliases. Both spellings are asserted
     * here: the braced one resolves, the sugar is a parse error.
     */
    @Test
    void aTemplateApplicationAtATypeRefSlotInDataNeedsTheBracedForm() {
        String metaSource = meta("  operation => data & { method: text  path: text  responses: [type_ref] }");
        String body = """
                  order => { sku: text }
                  page  => <T> { items: [T] }
                  op    => !operation { method: "GET"  path: "/x"  responses: [ %s ] }""";

        String braced = governed(body.formatted("{ name: page  arguments: [ { name: order } ] }"));
        tson(metaSource, braced, "io.ltr8.tson.http.probe").resolve(braced);

        String sugar = governed(body.formatted("page<order>"));
        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, sugar, "io.ltr8.tson.http.probe").resolve(sugar)).getMessage();

        assertTrue(message.contains("adjacent values must be separated"), message);
    }

    /**
     * <b>A Class 1 field name meets all three of [TSON-DATA] §8.2's rules, not only the look-alike one.</b> A
     * Revision 35 change, and the kind that reads exactly like a test being wrong when it bites: a schemaless
     * record's field names used to be judged as lexical rather than as names, so only the skeleton rule over
     * the field <em>set</em> reached them. Now the two per-name rules do as well.
     *
     * <p>Both halves are pinned because the change moved a code, not a verdict. A within-word homograph now
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
     * <b>[TSON-DATA] §9.1's nesting bound is enforced, defaults to 64, and is not a verdict.</b> Revision 35
     * turned a {@code StackOverflowError} that escaped every {@code catch (RuntimeException)} into a
     * {@code LIMIT_EXCEEDED} diagnostic, which is what lets this project answer it at all --
     * {@code TsonHttpException} maps it to a 413.
     *
     * <p>All three facts are pinned together because each protects a different decision here. The default is
     * what {@code deployment-1.tn}'s profile publishes when a descriptor states no {@code max_depth}. That it
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
                !!id:"https://example.com/2026/36/app/hygiene-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
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
     * this", but the natural first implementation is a map and a map spells absence as {@code null}. That used
     * to reach {@code TsonCompiledMetaRegistry.recordAndVerify} and throw a bare {@code NullPointerException}
     * four frames from the cause — which, since the identity comes from the request body, was a 500 any client
     * could produce at will. It now says what is wrong, and {@code ofMap} exists so the mistake need not be
     * made.
     */
    @Test
    void aSourceReturningNullIsRefusedRatherThanThrowingAnNpe() {
        // The import is what reaches the source at all: the meta layer and core are pre-loaded, so a schema
        // naming only those never consults it and the null is never returned.
        String schema = """
                !!id:"https://example.com/2026/36/app/null-source-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                !!import:"https://example.com/2026/36/app/absent-1.tn"
                { thing => { a: text } }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> null)));

        RuntimeException refused = assertThrows(RuntimeException.class, () -> tson.resolve(schema));
        assertFalse(refused instanceof NullPointerException,
                () -> "a null return should be named, not dereferenced: " + refused);
        assertTrue(refused.getMessage() != null && refused.getMessage().contains("null"), refused.getMessage());
    }

    /**
     * And {@code ofMap} is the form that removes the trap: it refuses a miss with {@code NOT_FOUND}, and
     * compares by canonical identity, so a reference differing only in scheme or {@code ?sha256=} pin still
     * resolves (§2.2.1). Three demos here carried a private helper doing the first half and not the second.
     */
    @Test
    void ofMapRefusesAMissAndComparesByCanonicalIdentity() {
        String schema = """
                !!id:"https://example.com/2026/36/app/ofmap-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                { thing => { a: text } }""";
        SchemaSource source =
                SchemaSource.ofMap(Map.of("https://example.com/2026/36/app/ofmap-1.tn", schema));

        // The scheme is a transport hint, not part of the name.
        assertDoesNotThrow(() -> source.fetch("http://example.com/2026/36/app/ofmap-1.tn"));

        SchemaFetchException refused = assertThrows(SchemaFetchException.class,
                () -> source.fetch("https://example.com/2026/36/app/absent-1.tn"));
        assertEquals(SchemaFetchException.Reason.NOT_FOUND, refused.reason());
    }

    // ── open: a JSON document cannot name its own binding ───────────────────────────────────────

    /**
     * <b>[TSON-JSON] §3.4's in-band route is not built</b>: a root annotation object carrying {@code $schema} and
     * {@code $type} is how a JSON document names its own binding, and §3.5 requires it to agree with a {@code
     * TSON-Schema} header by canonical identity. tson-java's JSON reader builds only the out-of-band route, and
     * refuses a {@code $schema} at a position that is not scoped -- so a spec-valid body that names its binding
     * twice, in agreement, is a 400 today. {@code TsonHttpCodec.acceptingJson} documents it; this is what fails
     * the day it lands, so the codec can then check the agreement rather than leave it to the reader.
     *
     * <p>Asserted through {@code Json} directly, over the same schema the header route would name, so the refusal
     * is the reader's and not this project's media-type gate.
     */
    @Test
    void aJsonDocumentsInBandBindingIsRefused() {
        String schemaId = "https://s.example.com/2026/36/j-1.tn";
        String schema = """
                !!id:"https://s.example.com/2026/36/j-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                    note => { title: text }
                }""";
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(u -> schema)));
        tson.resolve(schema);
        Json json = Json.standard().withSchemas(tson.schemaRegistry());

        // Out of band: the binding from outside, the body a bare value. Built.
        assertEquals(List.of(), json.validate("{\"title\": \"t\"}", schemaId, "note"));

        // In band, agreeing with it exactly: refused, where §3.4 makes it valid.
        List<Diagnostic> inBand = json.validate(
                "{\"$schema\": \"" + schemaId + "\", \"$type\": \"note\", \"title\": \"t\"}", schemaId, "note");
        assertFalse(inBand.isEmpty(), "the in-band route has landed -- check the agreement in TsonHttpCodec");
        assertTrue(inBand.getFirst().message().contains("$schema"), () -> "" + inBand);
    }
}
