package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A protocol as a map of exchanges, measured on the layer's own {@code protocol}: a value entry of it holds the map,
 * read back as a {@code Namespace} of {@link Exchange}s; a value under the typed slot needs no {@code
 * !service.exchange} tag; an unresolved reference inside a value is caught at load, because the slot is typed by a
 * kind; and the annotations written before an exchange's key survive into {@code Namespace.getAnnotations}, which is
 * where a reader finds an exchange's documentation and facts.
 */
class ProtocolMapTest {

    static final String PROBE_META_ID = "https://tson.io/2026/40/io/ltr8/http/meta-probe.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/40/app/probe-1.tn";

    /** The layer, served under a probe identity. */
    static final String META = TsonApiSchema.source()
            .replace("https://tson.io/2026/40/io/ltr8/http/service.tn", PROBE_META_ID);

    static String doc(String entries) {
        return """
        !!id:"%s"
        !!import:"%s"
        !!import:"https://tson.io/2026/40/m/core.tn"
        !schema {
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

    /** The entry {@code orders}, which applies {@code protocol}: the map of exchanges itself. */
    static Namespace<?> resolvedProtocol(String entries) {
        String doc = doc(entries);
        Tson tson = tson(doc);
        List<Diagnostic> problems = tson.validateSchema(doc);
        assertEquals(List.of(), problems, () -> "" + problems);
        Top entry = tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("orders");
        ValueEntry value = assertInstanceOf(ValueEntry.class, entry);
        assertEquals("service.protocol", value.type());
        return assertInstanceOf(Namespace.class, value.value());
    }

    /** The entry is the map of exchanges, with nothing beside it: its keys are the exchanges, in written order. */
    @Test
    void aProtocolIsTheMapOfExchangesItself() {
        Namespace<?> orders = resolvedProtocol("""
                  orders => !service.protocol { place_order  => !service.exchange { request: order  response: order }
                                        cancel_order => !service.exchange { request: order_ref } }""");

        assertEquals("service.protocol", orders.type());
        assertEquals(List.of("place_order", "cancel_order"), List.copyOf(orders.keySet()));
        assertEquals("order", assertInstanceOf(Exchange.class, orders.get("place_order")).request().orElseThrow().name());
    }

    /** The slot's type supplies the record, so the tag is optional -- as at any typed position. */
    @Test
    void aValueUnderAnExchangeTypedSlotNeedsNoTag() {
        Namespace<?> orders = resolvedProtocol("  orders => !service.protocol { place_order => { request: order } }");

        assertInstanceOf(Exchange.class, orders.get("place_order"));
    }

    /** A name inside a map value is judged at load: the slot is typed by a kind, so the read records it. */
    @Test
    void anUnresolvedReferenceInsideAMapValueIsCaughtAtLoad() {
        String doc = doc("  orders => !service.protocol { place_order => !service.exchange { request: no_such } }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'no_such' is no type declared in this schema or its imports"),
                problems.getFirst().message());
    }

    /**
     * An exchange can be documented where it is declared: a {@code @doc} and a bare marker before the key survive the
     * read, on the namespace's own key annotations.
     */
    @Test
    void anAnnotationOnAnExchangeKeySurvivesIntoTheNamespace() {
        Namespace<?> orders = resolvedProtocol(
                "  orders => !service.protocol { @service.safe @doc:\"Fetch it.\" get_order => { request: order_ref } }");

        assertEquals("Fetch it.", orders.getAnnotations("get_order").value("doc", String.class).orElseThrow());
        assertTrue(orders.getAnnotations("get_order").has("service.safe"));
    }
}
