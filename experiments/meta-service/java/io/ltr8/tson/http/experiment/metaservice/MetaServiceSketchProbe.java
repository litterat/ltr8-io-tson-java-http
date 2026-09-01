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
 * {@code meta-service-1.tn} resolves, and the constructs it leans on behave as the sketch assumes.
 *
 * <p>Four things a reader of the sketch would otherwise have to take on trust: a {@code ~data} constructor with
 * a record mixin and no trailing body ({@code method => ~data & signature}); a tightening in the constructor's
 * own body ({@code request_stream: = false}) that a governed schema cannot override; a {@code [type_ref]} slot
 * that resolves per element; and an annotation type declared in the meta layer ({@code @interface}) read back
 * from the entry it was written on. Plus the rule the whole design bends around: a {@code ~data} instance is not
 * a type, and naming one where a type is expected is refused at load.
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
              plan        => { steps: [text] }
              plan_result => { outcomes: [text] }
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

    /** The "both" shape: operations where a method is bound to HTTP, methods where it is not, one document. */
    static final String BOTH = """
              @interface:orders
              @doc:"Accept an order and confirm it with the quantity doubled."
              place_order => !operation {
                verb: POST  path: "/orders"  status: 201
                request: order  response: order  errors: [sku_not_found]
              }

              @interface:orders
              @doc:"Cancel an order. No route of its own yet."
              cancel_order => !method { request: order_ref  errors: [order_not_found]  idempotent: true }

              @interface:agent
              invoke => !operation { verb: POST  path: "/invoke"  request: plan  response: plan_result }

              get_schema => !operation {
                verb: GET  path: "/{schemaPath}"  safe: true  idempotent: true
                parameters: [ !parameter { name: "schemaPath"  in: PATH  type: text  required: true } ]
                errors: [order_not_found]
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

        Operation place = assertInstanceOf(Operation.class, entries.get("place_order").body());
        assertEquals(201, place.status());
        assertFalse(place.safe());
        assertFalse(place.request_stream());
        assertEquals("sku_not_found", place.errors().getFirst().name());
        assertEquals("orders", entries.getAnnotations("place_order").value("interface", String.class).orElseThrow());
        assertTrue(entries.getAnnotations("place_order").value("doc", String.class).isPresent());

        Method cancel = assertInstanceOf(Method.class, entries.get("cancel_order").body());
        assertTrue(cancel.idempotent());
        assertTrue(cancel.response().isEmpty());

        Operation schema = assertInstanceOf(Operation.class, entries.get("get_schema").body());
        assertEquals(200, schema.status());
        assertEquals(HttpVerb.GET, schema.verb());
        assertEquals(1, schema.parameters().size());

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
        String doc = doc("  x => !operation { verb: POST  path: \"/x\"  request_stream: true }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'request_stream' is fixed on 'operation'"),
                problems.getFirst().message());
    }

    /** §4.1: a {@code kind: DATA} entry is declared and applied, never named where a type is expected. */
    @Test
    void aMethodIsNotAType() {
        String doc = doc("  m => !method { request: order }\n  x => { s: m }");
        List<Diagnostic> problems = tson(doc).validateSchema(doc);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("is built with 'method' and describes something other "
                + "than a data value"), problems.getFirst().message());
    }
}
