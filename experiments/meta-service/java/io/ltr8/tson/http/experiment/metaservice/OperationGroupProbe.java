package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Could {@code operation}'s one-or-the-other rule be the resolver's rather than the reader's? §5.11's field group
 * says "exactly one of these fields": {@code ( method: method_ref | signature: signature )}. A group member is one
 * field, so the inline signature must be NESTED as a field rather than composed in -- one level deeper for an
 * inline operation, and the stream pins move to a refined {@code unary_signature}.
 *
 * <p><b>Measured: yes.</b> The group is enforced inside a {@code ~data} payload with the resolver's own messages
 * -- <em>"exactly one of (method | signature) must be present"</em>, <em>"at most one … found 2"</em> -- and the
 * fixed stream flags travel through the refinement. What was {@code Routes}' first check becomes the schema's.
 */
class OperationGroupProbe {

    static final String META_ID = "https://tson.io/2026/34/ltr8/http/meta-probe-g.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/34/app/probe-g-1.tn";

    static final String META = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta-kernel.tn"
        !!import:"https://tson.io/2026/34/m/meta.tn"
        {
          signature => { request: type_ref?  response: type_ref?  errors: [type_ref]?
                         safe: boolean ~ false  idempotent: boolean ~ false
                         request_stream: boolean ~ false  response_stream: boolean ~ false }
          unary_signature => signature ^ { request_stream: = false  response_stream: = false }
          method_ref  => { name: type_name  interface: type_name? }
          status_code => !integer ^ { min: 100  max: 599 }
          grouped_operation => ~data & {
            ( method: method_ref | signature: unary_signature )
            query:    [field_name]?
            headers:  {field_name => text}?
            body:     field_name?
            status:   status_code ~ 200
            summary:  text?
          }
        }""".formatted(META_ID);

    static List<Diagnostic> problems(String entries) {
        String doc = """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            %s
            }""".formatted(DOC_ID, META_ID, entries);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, META);
        lib.put(DOC_ID, doc);
        Tson tson = Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
        List<Diagnostic> meta = tson.validateSchema(META);
        assertEquals(List.of(), meta, () -> "the probe meta itself: " + meta);
        return tson.validateSchema(doc);
    }

    @Test
    void byReferenceAlone() {
        assertEquals(List.of(), problems("  a => !grouped_operation { method: { name: place_order }  status: 201 }"));
    }

    @Test
    void inlineAlone() {
        assertEquals(List.of(), problems("  a => !grouped_operation { signature: { request: order  response: order } }"));
    }

    @Test
    void bothIsRefusedByTheResolver() {
        List<Diagnostic> problems = problems(
                "  a => !grouped_operation { method: { name: place_order }  signature: { request: order } }");
        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("at most one of (method | signature) may be present"),
                problems.getFirst().message());
    }

    @Test
    void neitherIsRefusedByTheResolver() {
        List<Diagnostic> problems = problems("  a => !grouped_operation { status: 201 }");
        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("exactly one of (method | signature) must be present"),
                problems.getFirst().message());
    }

    @Test
    void theStreamPinTravelsThroughTheRefinedSignature() {
        List<Diagnostic> problems = problems(
                "  a => !grouped_operation { signature: { request: order  request_stream: true } }");
        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("'request_stream' is fixed on 'unary_signature'"),
                problems.getFirst().message());
    }
}
