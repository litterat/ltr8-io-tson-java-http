package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class SketchProbeTest {

    private static final String ORDER = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: text  quantity: int32 } }""";

    private static final String ERRORS = """
            !!id:"https://schemas.example.com/2026/32/app/orders-errors-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/ltr8/http/problem-2.tn"
            { sku_not_found => problem & { sku: text } }""";

    private static String sketch(String name) throws Exception {
        return Files.readString(Path.of("..", "sketch", name));
    }

    private static void probe(String label, String schema, Map<String, String> lib) {
        Tson tson = Tson.builder().schemaSource(lib::get).build();
        try {
            var d = tson.validateSchema(schema);
            System.out.println("PROBE " + label + " -> "
                    + (d.isEmpty() ? "RESOLVES" : "REJECTED: " + d.getFirst().message().replace("\n", " ")));
        } catch (RuntimeException e) {
            System.out.println("PROBE " + label + " -> THREW " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).replace("\n", " "));
        }
    }

    @Test
    void theSketch() throws Exception {
        Map<String, String> lib = new HashMap<>();
        lib.put("https://tson.io/2026/32/ltr8/http/meta-http-1.tn", sketch("meta-http-1.tn"));
        lib.put("https://tson.io/2026/32/ltr8/http/problem-2.tn", TsonProblemSchema.source());
        lib.put("https://schemas.example.com/2026/32/app/order-1.tn", ORDER);
        lib.put("https://schemas.example.com/2026/32/app/orders-errors-1.tn", ERRORS);

        probe("meta-http-1.tn", sketch("meta-http-1.tn"), lib);
        probe("orders-api-1.tn", sketch("orders-api-1.tn"), lib);

        // Narrow the API schema down: which part is the wall?
        String head = """
                !!id:"https://schemas.example.com/2026/32/app/probe-1.tn"
                !!meta:"https://tson.io/2026/32/ltr8/http/meta-http-1.tn"
                """;
        probe("  one import, minimal operation", head
                + "!!import:\"https://schemas.example.com/2026/32/app/order-1.tn\"\n"
                + "{ create => !operation { method: POST  path: \"/orders\"  parameters: []  "
                + "request: order  responses: [] } }", lib);
        probe("  one import, a response body", head
                + "!!import:\"https://schemas.example.com/2026/32/app/order-1.tn\"\n"
                + "{ create => !operation { method: POST  path: \"/orders\"  parameters: []  "
                + "responses: [ !response { status: 201  body: order } ] } }", lib);
        probe("  two imports (needs UPSTREAM #11)", head
                + "!!import:\"https://schemas.example.com/2026/32/app/order-1.tn\"\n"
                + "!!import:\"https://schemas.example.com/2026/32/app/orders-errors-1.tn\"\n"
                + "{ create => !operation { method: POST  path: \"/orders\"  parameters: []  "
                + "request: order  responses: [] } }", lib);
    }
}
