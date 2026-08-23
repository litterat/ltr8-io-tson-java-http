package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonProblemSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
              @doc:"Accept an order and confirm it, with the quantity doubled."
              create_order => !operation {
                method:     POST
                path:       "/orders"
                summary:    "Place an order"
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
                api.doc("create_order"),
                "@doc on the entry is the long form; summary is the short one");
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
    // ── templates, in the shipping design ──

    private static final String PAGED = """
            !!id:"https://schemas.example.com/2026/32/app/orders-api-1.tn"
            !!meta:"https://tson.io/2026/32/ltr8/http/meta-http-1.tn"
            !!import:"https://schemas.example.com/2026/32/app/order-1.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              page       => <T> { items: [T]  next: uri?  total: int32 }
              order_page => page<order>

              list_orders => !operation {
                method:     GET
                path:       "/orders"
                parameters: []
                responses:  [ !response { status: 200  body: %s } ]
              }
            }""";

    /**
     * <b>Templates work, and an operation references an application by name.</b> {@code page<order>} is
     * written once and applied, where OpenAPI hand-rolls the envelope per endpoint or bolts it on with
     * {@code allOf}. The application is a real entry with structural identity (§8.2), so two endpoints
     * returning a page of orders share one.
     */
    @Test
    void anOperationReferencesATemplateApplication() {
        Tson tson = resolved(PAGED.formatted("order_page"));
        TsonApiDescription api = TsonApiSchema.describedBy(tson, API_ID);

        Operation list = api.operations().get("list_orders");
        assertEquals("order_page", list.responseFor(200).orElseThrow().body().orElseThrow().name());

        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        String materialised = entries.get("order_page").source().orElseThrow().name();
        assertEquals("page", entries.get(materialised).source().orElseThrow().name(),
                "§8.2 records the application that built it");
    }

    /**
     * <b>Applying one inline needs the braced form, and that is by design.</b> {@code page<order>} is
     * <em>schema</em> syntax and an {@code !operation} payload is <em>data</em>, where a {@code type_ref}
     * slot takes §5.6's positional form: a bare token without arguments, a braced record with. The kernel
     * says so — <em>"a braced record is the explicit form, canonical only when `arguments` is present"</em>.
     */
    @Test
    void aTemplateAppliedInlineNeedsTheBracedForm() {
        Tson tson = resolved(PAGED.formatted("{ name: page  arguments: [ { name: order } ] }"));
        Operation list = TsonApiSchema.describedBy(tson, API_ID).operations().get("list_orders");

        var body = list.responseFor(200).orElseThrow().body().orElseThrow();
        assertEquals("page", body.name());
        assertEquals(1, body.arguments().size(), "the argument survives into the bound record");

        // The sugar in the same position is a PARSE error, before resolution is reached.
        assertTrue(assertThrows(RuntimeException.class, () -> resolved(PAGED.formatted("page<order>")))
                .getMessage().contains("adjacent values must be separated"));
    }

    /** A bad argument is caught either way, and the inline spelling names the operation. */
    @Test
    void aBadTemplateArgumentIsCaughtInBothSpellings() {
        assertTrue(assertThrows(RuntimeException.class,
                () -> resolved(PAGED.formatted("order_page").replace("page<order>", "page<no_such>")))
                .getMessage().contains("unresolved reference 'no_such'"));

        String message = assertThrows(RuntimeException.class,
                () -> resolved(PAGED.formatted("{ name: page  arguments: [ { name: no_such } ] }")))
                .getMessage();
        assertTrue(message.contains("unresolved reference 'no_such'"), message);
        assertTrue(message.contains("list_orders"), "the inline form names the operation: " + message);
    }

    /** And the payload's own shape is checked against the constructor's declaration, field by field. */
    @Test
    void aMisspeltOperationFieldIsRefused() {
        assertTrue(assertThrows(RuntimeException.class,
                () -> resolved(API.replace("method:     POST", "methd:     POST")))
                .getMessage().contains("methd"));
    }

    // ── what a server can derive from its description, instead of listing it ──

    /**
     * <b>Everything a client needs to resolve the contract</b>: the description, the meta layer governing it,
     * and its imports transitively. The bundled standard library is excluded -- every implementation has it,
     * and a service publishing {@code core.tn} would be claiming ownership of something it does not own.
     */
    @Test
    void aDescriptionKnowsWhatItsServerMustPublish() {
        Set<String> published = TsonApiSchema.describedBy(resolved(API), API_ID).referencedSchemas();

        assertTrue(published.contains(API_ID), "itself: a description a client cannot fetch is no contract");
        assertTrue(published.contains(TsonApiSchema.ID), "the meta layer, or the description will not resolve");
        assertTrue(published.contains(ORDER_ID));
        assertTrue(published.contains(TsonProblemSchema.ID));
        assertFalse(published.stream().anyMatch(id -> id.startsWith("https://tson.io/2026/32/m/")),
                () -> "the bundled standard library is not a service's to publish: " + published);
    }

    /**
     * <b>And which classes to warm.</b> Hand-listing them is how a response type added to a description never
     * gets warmed — nothing connects the two lists, and the omission costs only latency, so nothing reports
     * it. A payload type nobody binds is skipped rather than refused: {@code text} names a parameter's type
     * and {@code problem} is written by the library itself.
     */
    @Test
    void aDescriptionKnowsWhichClassesToWarm() {
        TsonApiDescription api = TsonApiSchema.describedBy(resolved(API), API_ID);

        assertEquals(Set.of("order", "problem", "text"), api.payloadTypes());
        assertEquals(List.of(String.class), api.boundClasses(Map.of("order", String.class)),
                "only what the bindings map names -- problem and text are nobody's to bind here");
    }

}
