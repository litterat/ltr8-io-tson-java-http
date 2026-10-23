package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interface as a map of methods: the values are built; a value under the typed slot needs no {@code !method}
 * tag; an unresolved reference inside a value is caught at load, because the slot is typed by a kind; and a {@code @doc}
 * written before a map key survives into {@code AnnotatedMap.getAnnotations}.
 *
 * <p>Measured on the sketch's own {@code interface}, a record held under a key of an {@code interfaces} entry, and on
 * a probe-only variant that is the namespace itself: {@code interface_of_signatures => namespace & { … }}, whose
 * entry is the map of methods with nothing beside it. The variant is the shape tson-java's own corpus fixture uses;
 * the sketch keeps the record because an interface carries {@code extends} beside its methods, and a value of a
 * namespace type is the map alone.
 */
class InterfaceMapProbe {

    static final String PROBE_META_ID = "https://tson.io/2026/38/io/ltr8/http/meta-probe.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/38/app/probe-1.tn";

    /** The sketch, plus one probe-only namespace type. */
    static final String META = Experiment.metaServiceSource()
            .replace("https://tson.io/2026/38/io/ltr8/http/meta-service.tn", PROBE_META_ID)
            .replace("\n  api => {",
                    "\n  interface_of_signatures => namespace & { key_type: = method_name  value_type: = signature }\n"
                    + "\n  api => {");

    static String doc(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/38/m/core.tn"
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
        return Tson.of(Experiment.bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))));
    }

    static Top resolvedEntry(String entries) {
        String doc = doc(entries);
        Tson tson = tson(doc);
        List<Diagnostic> problems = tson.validateSchema(doc);
        assertEquals(List.of(), problems, () -> "" + problems);
        return tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("orders");
    }

    /** The interface the {@code interfaces} entry {@code orders} holds under its key {@code orders}. */
    static Interface resolvedInterface(String entries) {
        return assertInstanceOf(Interface.class, assertInstanceOf(Namespace.class, resolvedEntry(entries)).get("orders"));
    }

    /** The sketch's own interface: a map of {@code method} records, tagged or not. */
    @Test
    void aMapOfMethodRecordsResolves() {
        var orders = resolvedInterface(
                "  orders => !interfaces { orders => { place_order => !method { request: order  response: order } } }");

        Method place = orders.methods().get("place_order");
        assertEquals("order", place.request().orElseThrow().name());
    }

    /** The slot's type supplies the record, so the tag is optional -- as at any typed position. */
    @Test
    void aValueUnderAMethodTypedSlotNeedsNoTag() {
        var orders = resolvedInterface("  orders => !interfaces { orders => { place_order => { request: order } } }");

        assertInstanceOf(Method.class, orders.methods().get("place_order"));
    }

    /** A name inside a map value is judged at load: the slot is typed by a kind, so the read records it. */
    @Test
    void anUnresolvedReferenceInsideAMapValueIsCaughtAtLoad() {
        String doc = doc("  orders => !interfaces { orders => { place_order => !method { request: no_such } } }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'no_such' is no type declared in this schema or its imports"),
                problems.getFirst().message());
    }

    /** The namespace variant: the entry IS the map of methods, read as a {@code Namespace} of signatures. */
    @Test
    void aNamespaceOfSignaturesIsTheMapItself() {
        var orders = assertInstanceOf(Namespace.class, resolvedEntry("""
                  orders => !interface_of_signatures { place_order  => { request: order  response: order }
                                                       cancel_order => { request: order_ref } }"""));

        assertEquals("interface_of_signatures", orders.type());
        assertEquals(List.of("place_order", "cancel_order"), List.copyOf(orders.keySet()));
        assertTrue(assertInstanceOf(Signature.class, orders.get("cancel_order")).request().isPresent());
    }

    /** A method can be documented where it is declared: a {@code @doc} before the key survives the read. */
    @Test
    void aDocOnAMapKeySurvivesIntoTheBoundValue() {
        var orders = resolvedInterface("  orders => !interfaces { orders => {"
                + " methods: { @doc:\"Place it.\" place_order => { request: order } } } }");

        assertEquals("Place it.",
                orders.methods().getAnnotations("place_order").value("doc", String.class).orElseThrow());
    }
}
