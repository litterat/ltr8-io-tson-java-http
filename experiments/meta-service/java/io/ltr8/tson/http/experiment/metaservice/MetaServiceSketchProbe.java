package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code meta-service-1.tn} resolves, and the constructs it leans on behave as the sketch assumes: a {@code ~data}
 * constructor with a record mixin and no trailing body ({@code method => ~data & signature}); a tightening in a
 * constructor's own body ({@code request_stream: = false}) that a governed schema cannot override; a
 * {@code [type_ref]} slot that resolves per element; an error type's fixed {@code status} readable from the
 * resolved schema. Plus the rule the whole design bends around: a {@code ~data} instance is not a type, and
 * naming one where a type is expected is refused at load.
 */
class MetaServiceSketchProbe {

    static final String ERR_ID = "https://schemas.example.com/2026/34/app/orders-errors-1.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/34/app/orders-1.tn";

    /** An error type pins the status it inherits from {@code problem}, which is how an operation's errors get one. */
    static final String ERRORS = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            !!import:"%s"
            {
              sku_not_found   => problem & { status: = 404  sku: text }
              order_not_found => problem & { status: = 404 }
            }""".formatted(ERR_ID, TsonProblemSchema.ID);

    static String doc(String entries) {
        return """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/34/m/core.tn"
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
        return Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
    }

    /** The "both" shape: an interface, and an api implementing it with bindings, plus one inline operation. */
    static final String BOTH = """
              orders => !interface {
                @doc:"Accept an order and confirm it with the quantity doubled."
                place_order  => { request: order  response: order  errors: [sku_not_found] }
                @doc:"Cancel an order."
                cancel_order => { request: order_ref  errors: [order_not_found]  idempotent: true }
              }

              orders_api => !api {
                implements: [orders]
                resources: {
                  "/orders"        => !resource { POST   => !binding { method: place_order  status: 201 } }
                  "/orders/{id}"   => !resource { DELETE => !binding { method: cancel_order  status: 204 } }
                  "/{schemaPath}"  => !resource { GET    => !operation { request: schema_ref  safe: true } }
                }
              }
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

        Interface orders = assertInstanceOf(Interface.class, entries.get("orders").body());
        Method place = orders.methods().get("place_order");
        assertEquals("sku_not_found", place.errors().getFirst().name());
        assertFalse(place.request_stream());
        assertTrue(orders.methods().get("cancel_order").idempotent());
        assertEquals("Cancel an order.",
                orders.methods().getAnnotations("cancel_order").value("doc", String.class).orElseThrow());

        Api api = assertInstanceOf(Api.class, entries.get("orders_api").body());
        assertEquals(List.of("orders"), api.implemented());
        Binding post = assertInstanceOf(Binding.class, api.resources().get("/orders").endpoints().get("POST"));
        assertEquals(201, post.status());
        assertEquals("place_order", post.method());
        Operation get = assertInstanceOf(Operation.class, api.resources().get("/{schemaPath}").endpoints().get("GET"));
        assertTrue(get.safe());

        // The status an error carries is readable from its type: REQUIRED_FIXED 404.
        var errEntries = tson.schemaRegistry().get(ERR_ID).orElseThrow().schema().entries();
        var status = ((RecordBody) errEntries.get("sku_not_found").body()).fields().stream()
                .filter(f -> f.name().equals("status")).findFirst().orElseThrow();
        assertEquals(FieldState.REQUIRED_FIXED, status.state());
        assertEquals("404", status.value().orElseThrow().text());
    }

    /** {@code request_stream: = false} in the constructor body is a fixed value a governed schema cannot lift. */
    @Test
    void anOperationCannotLiftThePinnedStream() {
        String doc = doc(
                "  x => !api { \"/x\" => !resource { POST => !operation { request: order  request_stream: true } } }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'request_stream' is fixed on 'operation'"),
                problems.getFirst().message());
    }

    /** §4.1: a {@code kind: DATA} instance is declared and applied, never named where a type is expected. */
    @Test
    void aMethodIsNotAType() {
        String doc = doc("  m => !method { request: order }\n  x => { s: m }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("is built with 'method' and describes something other "
                + "than a data value"), problems.getFirst().message());
    }
}
