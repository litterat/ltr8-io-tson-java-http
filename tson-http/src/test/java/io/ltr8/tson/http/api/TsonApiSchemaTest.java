package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping API-description design: operations declared in a schema governed by {@code meta-http-1.tn},
 * with payload types the compiler resolves.
 */
class TsonApiSchemaTest {

    private static final String ORDER_ID = "https://schemas.example.com/2026/32/app/order-1.tn";
    private static final String API_ID = "https://schemas.example.com/2026/32/app/orders-api-1.tn";

    private static final String ORDER = """
            !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { order => { sku: non_empty_text  quantity: int32 } }""";

    private static final String API = """
            !!id:"https://schemas.example.com/2026/32/app/orders-api-1.tn"
            !!meta:"https://tson.io/2026/32/ltr8/http/meta-http-1.tn"
            !!import:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!import:"https://tson.io/2026/32/ltr8/http/problem-1.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              create_order => !operation {
                method:     POST
                path:       "/orders"
                summary:     "Place an order"
                description: "Accept an order and confirm it, with the quantity doubled."
                parameters: []
                request:    order
                responses:  [
                  !response { status: 201  body: order    description: "The confirmed order" }
                  !response { status: 400  body: problem  description: "Not a valid order" }
                ]
              }

              get_schema => !operation {
                method:     GET
                path:       "/{schemaPath}"
                deprecated:  true
                parameters: [
                  !parameter { name: "schemaPath"  in: PATH  type: text  required: true
                               description: "The path component of the schema's own !!id" }
                ]
                responses:  [ !response { status: 200  description: "The schema, as bytes" } ]
              }
            }""";

    private static Tson resolved(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(TsonApiSchema.ID, TsonApiSchema.source());
        lib.put(ORDER_ID, ORDER);
        lib.putAll(TsonProblemSchema.publishedById());
        lib.put(API_ID, api);
        Tson tson = Tson.builder().schemaSource(lib::get)
                .metaNameBinder(TsonApiSchema.metaNameBinder()).build();
        tson.resolve(api);
        return tson;
    }

    @Test
    void theApiIsReadBackFromTheResolvedSchema() {
        TsonApiDescription api = TsonApiSchema.describedBy(resolved(API), API_ID);

        assertEquals(java.util.Set.of("create_order", "get_schema"), api.operations().keySet(),
                "the entry name is the operationId");

        Operation create = api.operations().get("create_order");
        assertEquals(HttpMethod.POST, create.method());
        assertEquals("/orders", create.path());
        assertEquals(Optional.of("Place an order"), create.summary());
        assertEquals(Optional.of("Accept an order and confirm it, with the quantity doubled."),
                create.description(),
                "a field rather than @doc, which does not survive resolution -- UPSTREAM.md #20");
        assertEquals("order", create.request().orElseThrow().name());
        assertEquals(Optional.of("The confirmed order"), create.responseFor(201).orElseThrow().description());
        assertEquals("problem", create.responseFor(400).orElseThrow().body().orElseThrow().name());
        assertFalse(create.isDeprecated());
    }

    @Test
    void aParameterAndADeprecationAreCarried() {
        TsonApiDescription api = TsonApiSchema.describedBy(resolved(API), API_ID);
        Operation get = api.operation(HttpMethod.GET, "/{schemaPath}").orElseThrow();

        assertTrue(get.isDeprecated());
        Parameter parameter = get.parameters().getFirst();
        assertEquals("schemaPath", parameter.name());
        assertEquals(ParameterLocation.PATH, parameter.in());
        assertTrue(parameter.required());
        assertEquals(Optional.empty(), get.responseFor(200).orElseThrow().body(), "204/bytes carry no type");
    }

    /**
     * <b>The property the whole design is for.</b> A payload type nothing declares does not resolve, so an
     * unsound description cannot be read at all -- where a data description carries the bad name happily and
     * needs its own resolver to notice.
     */
    @Test
    void aPayloadTypeNothingDeclaresIsRefusedByTheCompiler() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> resolved(API.replace("body: problem ", "body: problm ")));

        assertTrue(thrown.getMessage().contains("problm"), thrown.getMessage());
    }

    /** And an operation is not a type, so it cannot be used where one belongs. */
    @Test
    void anOperationCannotBeUsedWhereATypeBelongs() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> resolved(API.replace("\n}", "\n  holder => { op: create_order }\n}")));

        assertTrue(thrown.getMessage().contains("describes something other than a data value"),
                thrown.getMessage());
    }

    /** The meta layer is a document a server publishes, so its own identity has to be what it says. */
    @Test
    void theMetaSchemaDeclaresTheIdentityItIsServedAt() {
        assertTrue(TsonApiSchema.source().startsWith("!!id:\"" + TsonApiSchema.ID + "\""),
                TsonApiSchema.source().lines().findFirst().orElse(""));
        assertEquals(List.of(TsonApiSchema.source()), TsonApiSchema.publishedSources());
    }
}
