package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finding the map design rests on: a meta layer may type a map's values with a {@code ~data} constructor --
 * {@code interface => ~data & { methods: {type_name => method} }} -- and the implementation builds the values.
 *
 * <p>Measured here on the sketch's own {@code interface} and on a probe-only variant whose value type is the plain
 * {@code signature} record: the values are built; a value under the typed slot needs no {@code !method} tag; an
 * unresolved reference inside a value is caught at load, provided the owner's {@code references()} hands it on;
 * and a {@code @doc} written before a map key survives into {@code AnnotatedMap.getAnnotations}.
 *
 * <p><b>Read [TSON-SCHEMA] §4.1 before relying on it.</b> The text refuses "naming a {@code kind: DATA} entry"
 * as an element type, and a map's value type is one; the implementation admits a DATA <em>constructor</em>
 * there and refuses only an <em>instance</em> ({@code MetaServiceSketchProbe.aMethodIsNotAType}). The kernel's
 * own {@code top}-typed slots hold DATA instances, so the implementation is consistent with the kernel; whether
 * §4.1 means "entry" to include a constructor is a question for the spec, recorded in the experiment's README.
 */
class InterfaceMapProbe {

    static final String PROBE_META_ID = "https://tson.io/2026/34/ltr8/http/meta-probe.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/34/app/probe-1.tn";

    /** The sketch, plus one probe-only constructor. */
    static final String META = Experiment.metaServiceSource()
            .replace("https://tson.io/2026/34/ltr8/http/meta-service-1.tn", PROBE_META_ID)
            .replace("\n  api => ~data & {",
                    "\n  interface_of_signatures => ~data & { methods: {type_name => signature} }\n\n  api => ~data & {");

    static String doc(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/34/m/core.tn"
        {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
        %s
        }""".formatted(DOC_ID, PROBE_META_ID, entries);
    }

    static Tson tson(String doc) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(PROBE_META_ID, META);
        lib.put(DOC_ID, doc);
        return Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
    }

    static Object resolvedBody(String entries) {
        String doc = doc(entries);
        Tson tson = tson(doc);
        List<Diagnostic> problems = tson.validateSchema(doc);
        assertEquals(List.of(), problems, () -> "" + problems);
        return tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("orders").body();
    }

    /** The literal question: a map whose value type is the {@code ~data} constructor, its values tagged. */
    @Test
    void aMapOfDataMethodsResolvesInTheImplementation() {
        var orders = assertInstanceOf(Interface.class, resolvedBody(
                "  orders => !interface { place_order => !method { request: order  response: order } }"));

        Method place = orders.methods().get("place_order");
        assertEquals("order", place.request().orElseThrow().name());
    }

    /** The slot's type supplies the constructor, so the tag is optional -- as at any typed position. */
    @Test
    void aValueUnderAMethodTypedSlotNeedsNoTag() {
        var orders = assertInstanceOf(Interface.class, resolvedBody(
                "  orders => !interface { place_order => { request: order } }"));

        assertInstanceOf(Method.class, orders.methods().get("place_order"));
    }

    /** A reference inside a map value reaches the linker through the owner's {@code references()}. */
    @Test
    void anUnresolvedReferenceInsideAMapValueIsCaughtAtLoad() {
        String doc = doc("  orders => !interface { place_order => !method { request: no_such } }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("unresolved reference 'no_such'"),
                problems.getFirst().message());
    }

    /** The plain-record variant: the same shape with no {@code ~data} in the value type at all. */
    @Test
    void aMapOfSignatureRecordsResolvesPositionally() {
        var orders = assertInstanceOf(InterfaceOfSignatures.class, resolvedBody("""
                  orders => !interface_of_signatures { place_order  => { request: order  response: order }
                                                       cancel_order => { request: order_ref  idempotent: true } }"""));

        assertEquals(List.of("place_order", "cancel_order"), List.copyOf(orders.methods().keySet()));
        assertTrue(orders.methods().get("cancel_order").idempotent());
    }

    /** A method can be documented where it is declared: a {@code @doc} before the key survives the read. */
    @Test
    void aDocOnAMapKeySurvivesIntoTheBoundValue() {
        var orders = assertInstanceOf(Interface.class, resolvedBody(
                "  orders => !interface { methods: { @doc:\"Place it.\" place_order => { request: order } } }"));

        assertEquals("Place it.",
                orders.methods().getAnnotations("place_order").value("doc", String.class).orElseThrow());
    }
}
