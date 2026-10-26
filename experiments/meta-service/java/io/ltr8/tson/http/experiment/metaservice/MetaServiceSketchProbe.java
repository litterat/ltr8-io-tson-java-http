package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code meta-service.tn} resolves, and the constructs it leans on behave as the sketch assumes: a {@code data}
 * constructor with a record mixin and no trailing body ({@code method => data & signature}); a
 * {@code [type_ref]} slot that resolves per element; an error type's fixed {@code status} readable from the
 * resolved schema. Plus the rule the whole design bends around: a {@code data} instance is not a type, and
 * naming one where a type is expected is refused at load.
 */
class MetaServiceSketchProbe {

    static final String ERR_ID = "https://schemas.example.com/2026/38/app/orders-errors-1.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/38/app/orders-1.tn";

    /** An error type pins the status it inherits from {@code problem}, which is how an operation's errors get one. */
    static final String ERRORS = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"%s"
            {
              sku_not_found   => problem & { status: = 404  sku: text }
              order_not_found => problem & { status: = 404 }
            }""".formatted(ERR_ID, TsonProblemSchema.ID);

    static String doc(String entries) {
        return """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"%s"
            {
              order       => { sku: text  quantity: int32 }
              order_ref   => { id: text }
              schema_ref  => { schemaPath: text }
            %s
            }""".formatted(DOC_ID, Experiment.META_ID, ERR_ID, entries);
    }

    static Tson tson(String doc) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        lib.put(ERR_ID, ERRORS);
        lib.put(DOC_ID, doc);
        return Tson.of(Experiment.bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))));
    }

    /** The "both" shape: an interface, and an api implementing it with bindings, plus one inline operation. */
    static final String BOTH = """
              orders => !interfaces { orders => {
                @doc:"Accept an order and confirm it with the quantity doubled."
                place_order  => { request: order  response: order  errors: [sku_not_found] }
                @idempotent
                @doc:"Cancel an order."
                cancel_order => { request: order_ref  errors: [order_not_found] }
              } }

              orders_api => !apis { orders_api => {
                implements: [orders.orders]
                resources: {
                  "/orders"        => !resource { POST   => !binding { method: orders.orders.methods.place_order
                                                                         status: 201 } }
                  "/orders/{id}"   => !resource { DELETE => !binding { method: orders.orders.methods.cancel_order
                                                                         status: 204 } }
                  "/{schemaPath}"  => !resource {
                    @safe GET => !operation { request: schema_ref  response: order } }
                }
              } }
            """;

    @Test
    void theSketchResolvesAndReadsBack() {
        String doc = doc(BOTH);
        Tson tson = tson(doc);
        // validateSchema registers a sound schema, so it is not followed by resolve -- that would be a
        // second registration of one identity.
        List<Diagnostic> problems = tson.validateSchema(doc);
        assertEquals(List.of(), problems, () -> "expected a clean resolution, got " + problems);
        var entries = tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries();

        Interface orders = Experiment.held(entries, "interfaces", Interface.class).get("orders");
        Method place = orders.methods().get("place_order");
        assertEquals("sku_not_found", place.errors().getFirst().name());
        assertEquals("Cancel an order.",
                orders.methods().getAnnotations("cancel_order").value("doc", String.class).orElseThrow());

        Api api = Experiment.held(entries, "apis", Api.class).get("orders_api");
        assertEquals(List.of("orders.orders"), api.implemented().stream().map(TypeRef::name).toList());
        Binding post = assertInstanceOf(Binding.class, api.resources().get("/orders").endpoints().get("POST"));
        assertEquals(201, post.status());
        assertEquals("orders.orders.methods.place_order", post.method().name());
        Operation get = assertInstanceOf(Operation.class, api.resources().get("/{schemaPath}").endpoints().get("GET"));

        // The status an error carries is readable from its type: a FIXED 404.
        var errEntries = tson.schemaRegistry().get(ERR_ID).orElseThrow().schema().entries();
        var status = ((RecordBody) errEntries.get("sku_not_found")).fields().get("status");
        assertEquals(FieldRole.FIXED, status.role());
        assertEquals("404", status.value().orElseThrow().text());
    }

    /**
     * An entry holding a namespace value is a value, never a type: naming an {@code interfaces} or {@code apis} entry
     * as a field type is refused.
     */
    @Test
    void anInterfaceIsNotAType() {
        String doc = doc("  m => !interfaces { m => { place_order => { request: order } } }\n  x => { s: m }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("a value of the namespace type 'interfaces'"),
                problems.getFirst().message());
    }

    /**
     * And the inner types are records, not constructors: none may head a schema entry. The refusal now states
     * the test it applies rather than the marker it used to look for -- Revision 35 dropped the {@code ~}
     * marker, so what makes an entry applicable is that it IS-A {@code top} ([TSON-SCHEMA] §4.1), which its
     * supertype chain already records. Same rule, same subjects; only the message moved.
     */
    @Test
    void anInnerRecordCannotHeadAnEntry() {
        for (String ctor : List.of("method", "resource", "operation", "binding")) {
            String doc = doc("  x => !" + ctor + " { }");
            List<Diagnostic> problems = tson(doc).validateSchema(doc);
            assertEquals(1, problems.size(), () -> ctor + ": " + problems);
            assertTrue(problems.getFirst().message().contains("'!" + ctor + "' is not applicable")
                            && problems.getFirst().message().contains("not IS-A 'top'"),
                    problems.getFirst().message());
        }
    }
}
