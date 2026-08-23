package io.ltr8.tson.http;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gaps and constraints in the library this project builds on, each pinned so that a change upstream shows up
 * here as a <em>failing test</em> rather than as nothing happening.
 *
 * <p>Every one is written up in {@code UPSTREAM.md} with the number named below. Two rules learned the hard
 * way and worth restating:
 *
 * <ul>
 *   <li><b>Pin the gap, not the way it is delivered.</b> The #13 test below once asserted that resolution
 *       <em>throws</em>. It stopped throwing when gaps became diagnostics — indistinguishable from the
 *       feature landing, if the test is not looking at the code. It had not landed.</li>
 *   <li><b>A fixed gap flips its test, it does not delete it.</b> #14's entry asserts the constraint now
 *       holds, which is what stops it silently regressing.</li>
 * </ul>
 */
class UpstreamGapsTest {

    private static final String META_ID = "https://tson.io/2026/32/ltr8/http/meta-probe.tn";
    private static final String API_ID = "https://schemas.example.com/2026/32/app/probe-1.tn";

    /** A meta layer with a `~data &` constructor, standing in for meta-http without depending on its shape. */
    private static String meta(String declarations) {
        return """
                !!id:"%s"
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/32/m/meta.tn"
                {
                %s
                }""".formatted(META_ID, declarations);
    }

    private static String governed(String declarations) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                %s
                }""".formatted(API_ID, META_ID, declarations);
    }

    private static Tson tson(String metaSource, String doc, String... packages) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, metaSource);
        lib.put(API_ID, doc);
        var builder = Tson.builder().schemaSource(lib::get);
        if (packages.length > 0) {
            builder.metaNameBinder(new DataNameBinder.DefaultDataNameBinder(Set.of(packages), Map.of()));
        }
        return builder.build();
    }

    // ── #13: a template application cannot appear inside a choice ────────────────────────────────

    /**
     * <b>Still open; only the channel changed.</b> A gap used to abort the pass as an
     * {@code UnsupportedOperationException} and now travels as a {@code NOT_IMPLEMENTED} diagnostic beside
     * the ordinary problems, so one unimplemented construct no longer costs every other declaration its
     * verdict. Asserting the <em>code</em> is what keeps this honest.
     */
    @Test
    void anApplicationInsideAChoiceIsStillNotImplemented() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                    order   => { sku: text }
                    problem => { title: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    op      => { response: (resp<order, 201> | resp<problem, 400>) }
                }""";

        List<Diagnostic> problems = Tson.builder().schemaSource(u -> null).build().validateSchema(schema);

        assertTrue(problems.stream().anyMatch(d -> d.code() == Diagnostic.Code.NOT_IMPLEMENTED
                        && d.message().contains("must be lifted to an entry")),
                () -> "expected a NOT_IMPLEMENTED diagnostic, got " + problems);
    }

    // ── #14: a value parameter filling a FIXED field -- fixed upstream, pinned here ──────────────

    /** {@code status: status_code = S} applied as {@code <order, 201>} both carries 201 and enforces it. */
    @Test
    void aValueParameterFixedFieldConstrains() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                    order    => { sku: text }
                    resp     => <T, S> { status: int32 = S  body: T }
                    created  => resp<order, 201>
                }""";
        Tson tson = Tson.builder().schemaSource(u -> schema).build();
        tson.resolve(schema);
        String header = "!!schema:\"https://s.example.com/2026/32/p-1.tn\"\n";

        assertEquals(List.of(), tson.validate(header
                + "!created { status: 201  body: !order { sku: \"a\" } }"));
        assertTrue(tson.validate(header + "!created { status: 999  body: !order { sku: \"a\" } }").stream()
                        .anyMatch(d -> d.code() == Diagnostic.Code.FIELD_FIXED),
                "a parameter-filled FIXED field constrains, since #14 -- the whole point of `= S`");
    }

    /**
     * The same, seen through a materialised entry: the substituted field is {@code REQUIRED_FIXED} rather
     * than merely carrying its value. This is the shape that made the {@code response<T, S>} design
     * unattractive while #14 was open, so it is worth knowing it is now sound.
     */
    @Test
    void aMaterialisedApplicationCarriesAFixedField() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                    order   => { sku: text }
                    resp    => <T, S> { status: int32 = S  body: T }
                    created => resp<order, 201>
                }""";
        Tson tson = Tson.builder().schemaSource(u -> schema).build();
        tson.resolve(schema);
        var entries = tson.schemaRegistry().get("https://s.example.com/2026/32/p-1.tn").orElseThrow()
                .schema().entries();

        String materialised = entries.get("created").source().orElseThrow().name();
        RecordBody body = (RecordBody) entries.get(materialised).body();
        RecordField status = body.fields().stream().filter(f -> f.name().equals("status"))
                .findFirst().orElseThrow();

        assertTrue(status.value().orElseThrow().toString().contains("201"), status.value().toString());
        assertEquals(FieldState.REQUIRED_FIXED, status.state());
    }

    // ── #18: applying a meta-layer template misreports its arguments ─────────────────────────────

    /**
     * <b>Two lookup paths disagree about scope.</b> A meta-layer declaration is not in the governed schema's
     * type namespace, and the reference path says so plainly — for an atom, a record, or an unapplied
     * template alike. Only the <em>application</em> path reaches into the meta layer, finds the template, and
     * then fails for an unrelated reason it states inaccurately.
     */
    @Test
    void applyingAMetaLayerTemplateMisreportsItsArguments() {
        String metaSource = meta("  status_code => !integer ^ { min: 100  max: 599 }\n  tmpl => <T> { v: T }");

        String plain = governed("  x => { s: status_code }");
        assertTrue(assertThrows(RuntimeException.class, () -> tson(metaSource, plain).resolve(plain))
                .getMessage().contains("unresolved reference 'status_code'"));

        String applied = governed("  x => tmpl<text>");
        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, applied).resolve(applied)).getMessage();
        assertTrue(message.contains("is a template taking 1 type argument"),
                "when #18 is fixed this should say `unresolved reference 'tmpl'`: " + message);
    }

    // ── #10's reverse case at the meta layer -- fixed, pinned ────────────────────────────────────

    /**
     * A Java component the meta declaration does not declare used to arrive {@code null} and be dereferenced
     * inside {@code Data.references()}, surfacing as an NPE out of {@code Tson.resolve} — a consumer's wiring
     * mistake reading as a library fault. Strict binding names both sides instead.
     */
    @Test
    void aJavaComponentTheMetaDoesNotDeclareIsReported() {
        String metaSource = meta("  operation => ~data & { method: text  path: text  responses: [type_ref] }");
        String doc = governed("""
                  y  => { a: text }
                  op => !operation { method: "GET"  path: "/x"  responses: [ y ] }""");

        // io.ltr8.tson.http.api.Operation has `summary`, `parameters` and more; this meta declares none.
        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.api").resolve(doc));

        assertTrue(thrown.getMessage().contains("parameters") || thrown.getMessage().contains("summary"),
                thrown.getMessage());
    }

    // ── §12.1: a `~data &` constructor cannot itself be templated ────────────────────────────────

    /**
     * <b>The CRUD-family payoff a templated operation would give is not available.</b>
     * {@code list => <T> !operation { … }} — one declaration standing for every paged-list endpoint — is a
     * parse error: §12.1 permits a type name, an application or a literal in an instance template binding,
     * and an {@code !operation { … }} payload is a container form.
     */
    @Test
    void aDataConstructorCannotItselfBeTemplated() {
        String metaSource = meta("  operation => ~data & { method: text  path: text  responses: [type_ref] }");
        String doc = governed("""
                  list => <T> !operation { method: "GET"  path: "/x"  responses: [] }""");

        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.api").resolve(doc)).getMessage();

        assertTrue(message.contains("not permitted in an instance template binding"), message);
    }

    // ── #20: @doc on a schema entry is dropped from resolved output ──────────────────────────────

    /**
     * <b>Not specific to the {@code data} kind, nor to a custom meta layer.</b> An ordinary record in an
     * ordinary schema loses its {@code @doc} too — as does this project's own {@code problem-1.tn}, which
     * documents its entries and cannot read them back. A <em>locally declared</em> annotation does survive,
     * which is what makes this a gap rather than a rule about annotations.
     */
    @Test
    void docOnASchemaEntryIsDroppedFromResolvedOutput() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  @doc:"a documented record"
                  thing => { a: text }
                }""";
        Tson tson = Tson.builder().schemaSource(u -> schema).build();
        tson.resolve(schema);
        TypeDefinition thing = tson.schemaRegistry().get("https://s.example.com/2026/32/p-1.tn")
                .orElseThrow().schema().entries().get("thing");

        assertEquals(List.of(), thing.annotations().values(),
                "when #20 is answered, either this carries the doc or the rule is written down");

        // And the shipping schema, which documents its own entries for readers that cannot see it.
        assertEquals(List.of(), TsonProblemSchema.compiled().schema().entries().get("problem")
                .annotations().values());
    }

    // ── the `data` kind's own guarantee, which nothing else asserts here ─────────────────────────

    /** An operation is not a type, so every position a type-ref can occupy refuses it at link time. */
    @Test
    void aDataEntryCannotBeUsedWhereATypeBelongs() {
        String metaSource = meta("  operation => ~data & { method: text  path: text  responses: [type_ref] }");
        String doc = governed("""
                  y      => { a: text }
                  op     => !operation { method: "GET"  path: "/x"  responses: [ y ] }
                  holder => { o: op }""");

        String message = assertThrows(RuntimeException.class,
                () -> tson(metaSource, doc, "io.ltr8.tson.http.probe").resolve(doc)).getMessage();

        assertTrue(message.contains("describes something other than a data value"), message);
        assertInstanceOf(TypeRef.class, TypeRef.of("op"));
    }
}
