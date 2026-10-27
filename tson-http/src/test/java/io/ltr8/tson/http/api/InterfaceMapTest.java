package io.ltr8.tson.http.api;

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
 * An interface as a map of methods, measured on the layer's own {@code interface}: an entry applying it is the map
 * itself, read back as a {@code Namespace} of {@link Method}s; a value under the typed slot needs no {@code !method}
 * tag; an unresolved reference inside a value is caught at load, because the slot is typed by a kind; and the
 * annotations written before a method's key survive into {@code Namespace.getAnnotations}, which is where a reader
 * finds a method's documentation and facts.
 */
class InterfaceMapTest {

    static final String PROBE_META_ID = "https://tson.io/2026/38/io/ltr8/http/meta-probe.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/38/app/probe-1.tn";

    /** The layer, served under a probe identity. */
    static final String META = TsonApiSchema.source()
            .replace("https://tson.io/2026/38/io/ltr8/http/meta-service.tn", PROBE_META_ID);

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
        return Tson.of(OrdersExamples.bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))));
    }

    /** The entry {@code orders}, which applies {@code interface}: the map of methods itself. */
    static Namespace<?> resolvedInterface(String entries) {
        String doc = doc(entries);
        Tson tson = tson(doc);
        List<Diagnostic> problems = tson.validateSchema(doc);
        assertEquals(List.of(), problems, () -> "" + problems);
        Top entry = tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("orders");
        return assertInstanceOf(Namespace.class, entry);
    }

    /** The entry is the map of methods, with nothing beside it: its keys are the methods, in written order. */
    @Test
    void anInterfaceIsTheMapOfMethodsItself() {
        Namespace<?> orders = resolvedInterface("""
                  orders => !interface { place_order  => !method { request: order  response: order }
                                         cancel_order => !method { request: order_ref } }""");

        assertEquals("interface", orders.type());
        assertEquals(List.of("place_order", "cancel_order"), List.copyOf(orders.keySet()));
        assertEquals("order", assertInstanceOf(Method.class, orders.get("place_order")).request().orElseThrow().name());
    }

    /** The slot's type supplies the record, so the tag is optional -- as at any typed position. */
    @Test
    void aValueUnderAMethodTypedSlotNeedsNoTag() {
        Namespace<?> orders = resolvedInterface("  orders => !interface { place_order => { request: order } }");

        assertInstanceOf(Method.class, orders.get("place_order"));
    }

    /** A name inside a map value is judged at load: the slot is typed by a kind, so the read records it. */
    @Test
    void anUnresolvedReferenceInsideAMapValueIsCaughtAtLoad() {
        String doc = doc("  orders => !interface { place_order => !method { request: no_such } }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'no_such' is no type declared in this schema or its imports"),
                problems.getFirst().message());
    }

    /**
     * A method can be documented where it is declared: a {@code @doc} and a bare marker before the key survive the
     * read, on the namespace's own key annotations.
     */
    @Test
    void anAnnotationOnAMethodKeySurvivesIntoTheNamespace() {
        Namespace<?> orders = resolvedInterface(
                "  orders => !interface { @safe @doc:\"Fetch it.\" get_order => { request: order_ref } }");

        assertEquals("Fetch it.", orders.getAnnotations("get_order").value("doc", String.class).orElseThrow());
        assertTrue(orders.getAnnotations("get_order").has("safe"));
    }
}
