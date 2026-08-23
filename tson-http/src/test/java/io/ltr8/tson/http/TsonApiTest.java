package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An API description as a data document, with the type-name resolution the resolver will not do done here
 * instead.
 *
 * <p><b>The trade this is testing.</b> A data document cannot hold a reference to a type, so a description
 * written as data carries its own import list and its own namespace rule. Nothing in TSON enforces that rule —
 * so if these tests do not, nothing does.
 */
class TsonApiTest {

    private static final String ORDER = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: non_empty_text  quantity: int32 } }""";

    private static final String ERRORS = """
            !!id:"https://schemas.example.com/2026/32/app/orders-errors-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            !!import:"https://tson.io/2026/32/ltr8/http/problem-1.tn"
            { sku_not_found => problem & { missing_sku: non_empty_text } }""";

    /** A second schema that also declares `order`, for the ambiguity case. */
    private static final String OTHER = """
            !!id:"https://schemas.example.com/2026/32/app/other-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { reference: non_empty_text } }""";

    private static final Map<String, String> LIB = Map.of(
            "https://schemas.example.com/2026/32/app/order-1.tn", ORDER,
            "https://schemas.example.com/2026/32/app/orders-errors-1.tn", ERRORS,
            "https://schemas.example.com/2026/32/app/other-1.tn", OTHER,
            TsonProblemSchema.ID, TsonProblemSchema.source());

    private static String description(String imports, String request, String errorBody) {
        return """
                !!schema:"%s"
                !api {
                    title: "Orders"
                    version: "1"
                    imports: [%s]
                    operations: [
                        !operation {
                            method: POST
                            path: "/orders"
                            parameters: []
                            request: "%s"
                            responses: [
                                !response { status: 201  body: "order" }
                                !response { status: 404  body: "%s" }
                            ]
                        }
                    ]
                }""".formatted(TsonApi.SCHEMA_ID, imports, request, errorBody);
    }

    private static final String BOTH_IMPORTS = """
            "https://schemas.example.com/2026/32/app/order-1.tn"
                              "https://schemas.example.com/2026/32/app/orders-errors-1.tn\"""";

    private static Tson tson() {
        return Tson.builder().schemaSource(LIB::get).build();
    }

    @Test
    void readsADescriptionAndItsImports() {
        TsonApi api = TsonApi.read(description(BOTH_IMPORTS, "order", "sku_not_found"));
        assertEquals("Orders", api.api().title());
        assertEquals(2, api.referencedSchemas().size());
        assertEquals("order", api.operations().getFirst().request().orElseThrow());
    }

    /** The happy path: every name resolves against the imports. */
    @Test
    void aSoundDescriptionHasNoProblems() {
        assertEquals(List.of(), TsonApi.read(description(BOTH_IMPORTS, "order", "sku_not_found"))
                .validate(tson()));
    }

    /** The point of the whole exercise: a name no import declares is caught, and says what is available. */
    @Test
    void aTypeNoImportDeclaresIsReported() {
        List<Diagnostic> problems = TsonApi.read(description(BOTH_IMPORTS, "order", "sku_not_fund"))
                .validate(tson());

        assertEquals(1, problems.size(), problems.toString());
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code());
        assertTrue(only.message().contains("sku_not_fund"), only.message());
        assertTrue(only.message().contains("POST /orders response 404"),
                () -> "and says where it was written: " + only.message());
    }

    /** A name two imports declare is ambiguous — the rule a schema's own namespace has, applied here. */
    @Test
    void aTypeTwoImportsDeclareIsAmbiguous() {
        String bothDeclareOrder = """
                "https://schemas.example.com/2026/32/app/order-1.tn"
                                  "https://schemas.example.com/2026/32/app/other-1.tn\"""";
        List<Diagnostic> problems = TsonApi.read(description(bothDeclareOrder, "order", "order"))
                .validate(tson());

        assertTrue(problems.stream().anyMatch(d -> d.message().contains("more than one import")),
                problems::toString);
        assertTrue(problems.getFirst().message().contains("other-1.tn"),
                () -> "and names both: " + problems.getFirst().message());
    }

    /** An import that cannot be loaded is reported as such, not as every name in it being missing. */
    @Test
    void anUnloadableImportIsReportedOnce() {
        String missing = """
                "https://schemas.example.com/2026/32/app/order-1.tn"
                                  "https://schemas.example.com/2026/32/app/gone-1.tn\"""";
        List<Diagnostic> problems = TsonApi.read(description(missing, "order", "sku_not_found"))
                .validate(tson());

        assertTrue(problems.stream().anyMatch(d -> d.message().contains("cannot be loaded")),
                problems::toString);
    }

    /** Description problems are Diagnostics, so they render through the same error body as everything else. */
    @Test
    void problemsRenderAsAnOrdinaryProblemBody() {
        List<Diagnostic> problems = TsonApi.read(description(BOTH_IMPORTS, "nonesuch", "sku_not_found"))
                .validate(tson());
        TsonProblem body = TsonProblem.of(TsonHttpException.TYPES + "invalid-api-description", 500,
                "Invalid API description", "this server's own description does not resolve", problems);

        assertEquals(1, body.errors().size());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, body.errors().getFirst().code());
    }
}
