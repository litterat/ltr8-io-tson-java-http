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
     * <b>{@code UPSTREAM.md} #18 — the refusal is right, the message is not.</b> A meta layer is the schema
     * <em>for</em> the schema: its declarations are the vocabulary a schema is written in, not types that
     * schema may reference. So every form below is correctly refused, including the application. What is
     * wrong is that one of them asks for arguments that are present, sending an author to fix what is not
     * broken, where the others say plainly that the name does not resolve.
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
                "when #18 is fixed the refusal stays and the message changes -- it should stop claiming the "
                        + "arguments are missing: " + message);
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

    // ── not a gap: an entry's two annotation positions ──────────────────────────────────────────

    /**
     * <b>Filed as {@code UPSTREAM.md} #20 and withdrawn — the report was wrong.</b> A schema entry has two
     * annotation positions and they land in different places: before the name annotates the <em>entry</em>
     * and is read from the entries map, after the arrow annotates the <em>definition</em> and is read from
     * the {@code TypeDefinition}. Checking only the second for an annotation written in the first position
     * looks exactly like the annotation being dropped.
     *
     * <p>Kept as a test because the shape of that mistake is worth having pinned: it cost a wrongly-filed
     * upstream item and a redundant {@code description} field on {@code operation}, both since undone.
     */
    @Test
    void anEntrysTwoAnnotationPositionsLandInDifferentPlaces() {
        String schema = """
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  @doc:"on the entry"
                  before => { a: text }
                  after  => @doc:"on the definition" { a: text }
                }""";
        Tson tson = Tson.builder().schemaSource(u -> schema).build();
        tson.resolve(schema);
        var entries = tson.schemaRegistry().get("https://s.example.com/2026/32/p-1.tn")
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
                !!id:"https://s.example.com/2026/32/p-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  @nosuchtype:"x"
                  thing => { a: text }
                }""";

        assertTrue(assertThrows(RuntimeException.class,
                () -> Tson.builder().schemaSource(u -> schema).build().resolve(schema))
                .getMessage().contains("does not name a type"));
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
