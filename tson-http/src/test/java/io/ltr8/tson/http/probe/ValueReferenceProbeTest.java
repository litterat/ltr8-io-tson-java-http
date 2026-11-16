package io.ltr8.tson.http.probe;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.probe.valuereference.Guard;
import io.ltr8.tson.http.probe.valuereference.Scheme;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Completion;
import io.ltr8.tson.schema.meta.IncompleteEntry;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a construction can stand for a reference to data instead of its repetition: a value entry declared once and
 * reached by name, and a value written as another value with fields added. Measured against a minimal layer written
 * here, mirroring {@code service.endpoint}'s pattern with a {@code security} list of references; {@code
 * proposal/value-references.md} is the proposal it measures, for upstream's Revision 41. Every test pins Revision 40's
 * outcome, and one failing after a library change means the spec has moved.
 *
 * <p>The answers measured: the alias half exists, a value entry reached by {@code meta.ref} being delivered resolved.
 * The extension half exists for a constructor of types only: {@code !meta.array ^ { min_items: 1 }} declares an
 * incomplete entry and {@code nonempty<text>} completes it into the plain construction. Over a record, {@code !operation
 * ^ { … }} is a resolver error, and over a complete value entry it is a resolver error naming the entry finished; at a
 * value slot {@code ^} after a tag is a parse error, and {@code !paged { … }} naming a value entry is not admitted.
 */
class ValueReferenceProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/valueref/layer-1.tn";
    static final String ID = "https://schemas.example.com/2026/40/valueref/s-1.tn";

    /**
     * The layer: {@code service.tn}'s endpoint pattern at the root, trimmed to what the measurements touch. A scheme
     * record, an {@code abstract} base {@code endpoint} with a {@code security} list of references to schemes, an
     * {@code operation} composing it with a signature, a resource holding {@code {http_verb => endpoint}}, and a
     * projection whose resources are a map keyed by path.
     */
    static final String LAYER = "!!id:\"" + LAYER_ID + "\"\n!!import:\"" + META + "\"\n!!import:\"" + NET + "\"\n"
            + """
            !schema {
              signature => { request?: meta.type_ref  response?: meta.type_ref }
              scheme    => { name: text }
              endpoint  => abstract { status?: integer ~ 200  media_types?: [media_type]  security?: [meta.ref<scheme>] }
              operation => endpoint & signature
              http_verb => !enum [GET POST]
              resource  => { endpoints: {http_verb => endpoint} }
              http      => { resources: {text => resource} }
            }""";

    /** The base a description would declare once for every paged list, were open construction over a record a form. */
    static final String OPEN_PAGED = "  paged => !operation ^ { status: 200  request: order_query }";

    /** The nearest form that loads today: the same base as a complete value entry. */
    static final String PAGED = "  paged => !operation { status: 200  request: order_query  response: order_page }";

    /** The incomplete entry over a constructor of types that Revision 40 admits. */
    static final String NONEMPTY = "  nonempty => !meta.array ^ { min_items: 1 }";

    /** A description importing the layer and core, declaring the orders records, then {@code entries}. */
    static String schema(String... entries) {
        return "!!id:\"" + ID + "\"\n!!import:\"" + LAYER_ID + "\"\n!!import:\"" + CORE + "\"\n!schema {\n"
                + "  order       => { sku: text }\n"
                + "  order_query => { status?: text  page?: int32 ~ 1 }\n"
                + "  order_page  => { items: [order]  next_page?: int32 }\n"
                + String.join("\n", entries) + "\n}";
    }

    /** The projection {@code orders_http}, its one endpoint {@code GET /orders} written as {@code endpoint}. */
    static String http(String endpoint) {
        return "  orders_http => !http { resources: { \"/orders\" => { GET => " + endpoint + " } } }";
    }

    static Tson tson(String schema, Map<String, Class<?>> bound) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        documents.put(ID, schema);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(AtomContext.defaultContext().toBuilder()
                        .nameBinder(DataNameBinder.ofMap(bound)).build()));
    }

    /** The one diagnostic resolving {@code schema} with nothing bound yields, asserted of {@code code}. */
    static String refusal(String schema, Diagnostic.Code code) {
        List<Diagnostic> problems = tson(schema, Map.of()).validateSchema(schema);
        assertEquals(1, problems.size(), () -> "" + problems);
        assertEquals(code, problems.getFirst().code(), problems.getFirst()::message);
        return problems.getFirst().message();
    }

    // ── (i): open construction over a record ────────────────────────────────────────────────────────────────────

    /**
     * <b>Open construction over a record is refused</b>, the restriction the proposal lifts. {@code paged => !operation
     * ^ { status: 200  request: order_query }}, a base leaving {@code response} for each endpoint to write, is a
     * resolver error: [TSON-SCHEMA] §5.5 admits {@code !C ^ { values }} over an atom instance, an array or map type, or
     * a constructor of types, and {@code operation} is a record, which the message sends to {@code &}. {@code
     * OperationRefinementProbeTest} pins the same message as a refinement's refusal; here it is this proposal's
     * measurement. A change in the answer means the spec moved, and the proposal with it.
     */
    @Test
    void openConstructionOverARecordIsRefused() {
        assertEquals("'paged': '!operation ^ { ... }' needs an atom instance, an array or a map type to narrow, or a "
                        + "constructor of types to build from (§5.5), and 'operation' is a record, which takes no '^' "
                        + "-- its fields are restated in a composition, 'operation & { ... }' (§5.8)",
                refusal(schema(OPEN_PAGED), Diagnostic.Code.SCHEMA_ERROR));
    }

    // ── (ii): a value written as another value, at a value slot ─────────────────────────────────────────────────

    /**
     * <b>At a value slot, {@code ^} after a tag is no syntax, and a tag naming a value entry is not admitted.</b> With
     * {@code paged} a complete value entry, which loads, {@code GET => !paged ^ { response: order_page }} at the
     * endpoint slot is a parse error at the {@code ^}, the data grammar expecting a value after a tag; the message
     * names no construct. {@code GET => !paged { response: order_page }} parses and is refused by the resolver: a tag
     * naming a value entry is no type ([TSON-SCHEMA] §7.2), so it is not admitted at the abstract base's slot. And at
     * an entry, {@code paged2 => !paged ^ { response: order_page }} is a resolver error naming the value entry
     * finished. So no spelling writes a value as another value with fields added; a change in any answer means the
     * spec moved.
     */
    @Test
    void aValueSlotTakesNoCaretAndATagNamingAValueEntryIsNotAdmitted() {
        String base = schema(PAGED);
        assertEquals(List.of(), tson(base, Map.of()).validateSchema(base));
        assertEquals("expected a value (record, map, array, empty braces, the void sentinel '_', or a token), found '^'",
                refusal(schema(PAGED, http("!paged ^ { response: order_page }")), Diagnostic.Code.VALIDATION_ERROR));
        assertEquals("'orders_http': the body is no value of the type 'http' -- at /resources/~1orders/endpoints/GET: "
                        + "'paged' is not admissible at a 'endpoint' position -- 'endpoint' is abstract and has no direct "
                        + "instances, so a value here is a value of one of (operation)",
                refusal(schema(PAGED, http("!paged { response: order_page }")), Diagnostic.Code.SCHEMA_ERROR));
        assertEquals("'paged2': '!paged ^ { ... }' needs an atom instance, an array or a map type to narrow, or a "
                        + "constructor of types to build from (§5.5), and 'paged' is kind=VALUE, which is finished and "
                        + "takes no refinement",
                refusal(schema(PAGED, "  paged2 => !paged ^ { response: order_page }"),
                        Diagnostic.Code.SCHEMA_ERROR));
    }

    // ── (iii): the alias half ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The alias half exists: a value entry declared once and named by {@code meta.ref} is delivered.</b> {@code
     * bearer => !scheme { name: Bearer }} and {@code g => !guard { security: [bearer] }}, {@code guard} holding {@code
     * [meta.ref<scheme>]} as an endpoint's {@code security} does, load; bound, the reference arrives resolved, its
     * referent the {@code Scheme} the entry declared. This is YAML's anchor and alias, the proposal's "half exists"
     * claim; a change in the answer means the spec moved.
     */
    @Test
    void aValueEntryNamedByReferenceIsDelivered() {
        String schema = schema("  guard => { security: [meta.ref<scheme>] }", "  bearer => !scheme { name: Bearer }",
                "  g => !guard { security: [bearer] }");
        Tson tson = tson(schema, Map.of("guard", Guard.class, "scheme", Scheme.class));
        assertEquals(List.of(), tson.validateSchema(schema));
        Guard guard = assertInstanceOf(Guard.class, assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(ID).orElseThrow().schema().entries().get("g")).value());
        ValueRef<Scheme> bearer = guard.security().getFirst();
        assertEquals("bearer", bearer.name());
        assertTrue(bearer.isResolved());
        assertEquals(new Scheme("Bearer"), bearer.get());
    }

    // ── (iv): the extension half, over a constructor of types ───────────────────────────────────────────────────

    /**
     * <b>Over a constructor of types, open construction and completion work today</b>: the mechanism the proposal
     * extends to records. {@code nonempty => !meta.array ^ { min_items: 1 }} is an incomplete entry, {@code
     * element_type} absent. {@code tags => nonempty<text>} and {@code !nonempty { element_type: text }} complete it,
     * each into the plain construction {@code !meta.array { element_type: text  min_items: 1 }} with no chain and
     * equal to it written directly, its key carrying {@code @meta.completion:{ of: [nonempty] }}. A data document
     * reads {@code !tags [a b]} and refuses {@code !tags []} on {@code min_items}. Named where a type is, the
     * incomplete entry is refused, the message naming the in-place completion. A change in any answer means the spec
     * moved.
     */
    @Test
    void openConstructionOverAConstructorOfTypesCompletes() {
        String written = "  written => !meta.array { element_type: text  min_items: 1 }";
        for (String tags : List.of("  tags => nonempty<text>", "  tags => !nonempty { element_type: text }")) {
            String schema = schema(NONEMPTY, tags, written);
            Tson tson = tson(schema, Map.of());
            assertEquals(List.of(), tson.validateSchema(schema), tags);
            var resolved = tson.schemaRegistry().get(ID).orElseThrow().schema();
            IncompleteEntry nonempty = assertInstanceOf(IncompleteEntry.class, resolved.entries().get("nonempty"));
            assertEquals(List.of("element_type"), nonempty.absent());
            ArrayBody array = assertInstanceOf(ArrayBody.class, resolved.entries().get("tags"));
            assertEquals("text", assertInstanceOf(TypeRef.class, array.elementType()).name());
            assertEquals(Optional.of(1), array.minItems().map(Number::intValue));
            assertEquals(resolved.entries().get("written"), TypeChain.withoutChain(array), tags);
            assertEquals(Optional.of(List.of("nonempty")), resolved.completionOf("tags").map(Completion::of), tags);

            var read = DiagnosticsReceiver.collecting();
            TsonValue value = tson.treeReader().withDiagnostics(read).read("!!import:\"" + ID + "\"\n!tags [a b]");
            assertEquals(List.of(), read.diagnostics());
            assertEquals(Optional.of("tags"), value.typeRef());
            var refused = DiagnosticsReceiver.collecting();
            assertNull(tson.treeReader().withDiagnostics(refused).read("!!import:\"" + ID + "\"\n!tags []"));
            assertEquals(1, refused.diagnostics().size(), () -> "" + refused.diagnostics());
            assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.diagnostics().getFirst().code());
            assertEquals("'tags' has 0 elements, fewer than the minimum 1", refused.diagnostics().getFirst().message());
        }
        assertEquals("'holder' field 't' names 'nonempty', an incomplete 'meta.array' with [element_type] absent, which "
                        + "is no type -- complete it where it is used ('!nonempty { ... }'), or declare its completion "
                        + "and name that",
                refusal(schema(NONEMPTY, "  holder => { t: nonempty }"), Diagnostic.Code.SCHEMA_ERROR));
    }
}
