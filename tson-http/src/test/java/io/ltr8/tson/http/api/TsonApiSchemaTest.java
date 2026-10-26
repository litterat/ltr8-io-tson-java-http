package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.api.RequestPlacement.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping service-description layer: interfaces and apis declared in a schema governed by {@code
 * meta-service.tn}, with payload types and method references the compiler resolves, read back by {@link
 * TsonApiDescription} and held to a server by {@link TsonApiCoverage}. The reader's relational checks are {@link
 * TsonApiDescriptionTest}'s.
 */
class TsonApiSchemaTest {

    private static final String ORDER_ID = "https://schemas.example.com/2026/38/app/order-1.tn";
    private static final String API_ID = "https://schemas.example.com/2026/38/app/orders-api-1.tn";

    private static final String ORDER = """
            !!id:"https://schemas.example.com/2026/38/app/order-1.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"https://tson.io/2026/38/io/ltr8/http/problem.tn"
            {
              non_empty_text => !text ^ { min_length: 1 }
              order          => { sku: non_empty_text  quantity: int32 }
              sku_not_found  => problem & { status: = 404  sku: text }
            }""";

    /** An interface, an api binding it, and the schema route as a document. */
    private static final String API = """
            !!id:"https://schemas.example.com/2026/38/app/orders-api-1.tn"
            !!meta:"https://tson.io/2026/38/io/ltr8/http/meta-service.tn"
            !!import:"https://schemas.example.com/2026/38/app/order-1.tn"
            !!import:"https://tson.io/2026/38/io/ltr8/http/problem.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              schema_ref => { schemaPath: text }

              orders => !interfaces { orders => {
                @doc:"Accept an order and confirm it, with the quantity doubled."
                place_order => { request: order  response: order  errors: [sku_not_found] }
              } }

              orders_api => !apis { orders_api => {
                implements: [orders.orders]
                resources: {
                  "/orders" => !resource {
                    @summary:"Place an order"
                    POST => !binding { method: orders.orders.methods.place_order  status: 201 }
                  }
                  "/{+schemaPath}" => !resource {
                    @safe
                    @summary:"Fetch a published schema"
                    GET => !document { request: schema_ref  media_type: "application/tson" }
                  }
                }
              } }
            }""";

    private static Tson tson(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.putAll(TsonApiSchema.publishedById());
        lib.put(ORDER_ID, ORDER);
        lib.putAll(TsonProblemSchema.publishedById());
        lib.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))
                .withMetaNameBinder(TsonApiSchema.metaNameBinder()));
    }

    private static Tson resolved(String api) {
        Tson tson = tson(api);
        tson.resolve(api);
        return tson;
    }

    private static TsonApiDescription described(String api) {
        return TsonApiSchema.describedBy(resolved(api), API_ID);
    }

    /** The one diagnostic resolving {@code api} gives, or a failure naming them all. */
    private static String only(String api) {
        List<Diagnostic> problems = tson(api).validateSchema(api);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    private static Set<String> keys(TsonApiDescription described) {
        Set<String> keys = new TreeSet<>();
        described.routes().forEach(route -> keys.add(route.key()));
        return keys;
    }

    @Test
    void theServiceIsReadBackFromTheResolvedSchema() {
        TsonApiDescription api = described(API);
        assertEquals(Set.of("GET /{+schemaPath}", "POST /orders"), keys(api), "an endpoint is its verb and path");

        Route create = api.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Optional.of(new InterfaceMethod("orders.orders", "place_order")), create.method());
        assertEquals("order", create.request().orElseThrow().name(), "borrowed from the method");
        assertEquals("order", create.response().orElseThrow().name());
        assertEquals(201, create.status());
        assertEquals("sku_not_found", create.errorFor(404).orElseThrow().name(), "by the status its type fixes");
        assertEquals(Optional.of("Place an order"), create.summary(), "the verb key's @summary");
        assertEquals(Optional.of("Accept an order and confirm it, with the quantity doubled."), create.doc(),
                "the method's @doc, since the verb key carries none");
        assertEquals(List.of("sku", "quantity"), create.placement().at(Location.BODY), "a POST's remainder");

        Route schema = api.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertEquals(Optional.of("application/tson"), schema.mediaType());
        assertEquals(Optional.empty(), schema.response(), "a document's body is labelled, not typed");
        assertEquals(Map.of("schemaPath", Location.PATH), schema.placement().fields(),
                "reserved expansion places one field, which may carry slashes");
    }

    /**
     * <b>The property the whole design is for.</b> A payload type nothing declares does not resolve, so an
     * unsound description cannot be read at all -- where a data description carries the bad name happily and
     * needs its own resolver to notice.
     */
    @Test
    void aPayloadTypeNothingDeclaresIsRefusedByTheCompiler() {
        String refused = only(API.replace("response: order  errors", "response: ordr  errors"));
        assertTrue(refused.contains("ordr"), refused);
    }

    /** An entry holding interfaces or apis is a value, never a type, so it cannot be used where one belongs. */
    @Test
    void anInterfacesEntryCannotBeUsedWhereATypeBelongs() {
        String refused = only(API.replace("\n}", "\n  holder => { s: orders }\n}"));
        assertTrue(refused.contains("a value and not a type"), refused);
    }

    /**
     * And the records inside are records, not constructors: none may head a schema entry. Only {@code interfaces}
     * and {@code apis} are applied at entry level.
     */
    @Test
    void anInnerRecordCannotHeadAnEntry() {
        for (String record : List.of("method", "resource", "operation", "binding", "document")) {
            String refused = only(API.replace("\n}", "\n  x => !" + record + " { }\n}"));
            assertTrue(refused.contains("'!" + record + "' is not applicable") && refused.contains("not IS-A 'top'"),
                    refused);
        }
    }

    /** The base's vocabulary is inherited by every kind of endpoint, so its constraints reach them all. */
    @Test
    void anEndpointsBaseConstraintsReachEveryKind() {
        String refused = only(API.replace("status: 201", "status: 999"));
        assertTrue(refused.contains("'999' is greater than the maximum 599"), refused);
    }

    /** The meta layer is a document a server publishes, so its own identity has to be what it says. */
    @Test
    void theMetaSchemaDeclaresTheIdentityItIsServedAt() {
        assertTrue(TsonApiSchema.source().startsWith("!!id:\"" + TsonApiSchema.ID + "\""),
                TsonApiSchema.source().lines().findFirst().orElse(""));
        assertEquals(List.of(TsonApiSchema.source()), TsonApiSchema.publishedSources());
    }

    // ── paths, encodings, documents and errors ──

    /** {@link #API} with the schema route's path replaced. */
    private static String withSchemaPath(String path) {
        return API.replace("\"/{+schemaPath}\" =>", "\"" + path + "\" =>");
    }

    /**
     * <b>A path is a URI template, checked by its shape at schema load</b>: levels 1 and 2, absolute. {@code
     * {+schemaPath}} -- reserved expansion, what a path with slashes in it needs -- is admitted; the fragment
     * operator, a level-3 form, an unclosed brace, a space and a path not starting with {@code /} are not.
     */
    @Test
    void aPathIsAUriTemplateCheckedAtSchemaLoad() {
        for (String admitted : List.of("/{schemaPath}", "/{+schemaPath}", "/schemas/{+schemaPath}")) {
            assertTrue(described(withSchemaPath(admitted)).route(HttpVerb.GET, admitted).isPresent(), admitted);
        }
        for (String refused : List.of("/{#schemaPath}", "/{?schemaPath}", "/{schemaPath", "/a b/{schemaPath}",
                "{+schemaPath}")) {
            String message = only(withSchemaPath(refused));
            assertTrue(message.contains("path_template"), refused + ": " + message);
        }
    }

    /**
     * <b>An endpoint says which encodings it speaks</b>, because a client reading the description has no other way
     * to know it takes JSON. Absent is TSON alone; a list is read beside TSON, which every endpoint speaks.
     */
    @Test
    void anEndpointDeclaresTheEncodingsItSpeaks() {
        TsonApiDescription api = described(API.replace("status: 201 }", "status: 201  encodings: [JSON] }"));

        Route create = api.route(HttpVerb.POST, "/orders").orElseThrow();
        assertTrue(create.speaksJson());
        assertEquals(Set.of(Encoding.TSON, Encoding.JSON), create.encodings());

        Route schema = api.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertEquals(Set.of(Encoding.TSON), schema.encodings(), "absent is TSON alone");
        assertFalse(schema.speaksJson());
    }

    /** An encoding is checked when the description loads, like everything else in it. */
    @Test
    void anUnknownEncodingDoesNotResolve() {
        assertThrows(RuntimeException.class,
                () -> resolved(API.replace("status: 201 }", "status: 201  encodings: [XML] }")));
    }

    /** A document's media type is a type and a subtype; anything else is refused at load. */
    @Test
    void aDocumentStatesAMediaType() {
        String refused = only(API.replace("media_type: \"application/tson\"", "media_type: \"tson\""));
        assertTrue(refused.contains("media_type"), refused);
    }

    /**
     * <b>A business error fixes its status</b>, which is how a client tells it from the response by status alone,
     * and two errors cannot fix the same one. Both are refused when the description is read.
     */
    @Test
    void anErrorFixesAStatusOfItsOwn() {
        String unfixed = ORDER.replace("sku_not_found  => problem & { status: = 404  sku: text }",
                "sku_not_found  => problem & { sku: text }");
        Tson tson = tson(API);
        tson.resolve(unfixed);
        tson.resolve(API);
        String message = assertThrows(IllegalArgumentException.class,
                () -> TsonApiSchema.describedBy(tson, API_ID)).getMessage();
        assertTrue(message.contains("'sku_not_found', which fixes no status"), message);

        String twice = assertThrows(IllegalArgumentException.class, () -> described(API.replace(
                "errors: [sku_not_found]", "errors: [sku_not_found problem_404]").replace(
                "schema_ref => { schemaPath: text }",
                "schema_ref => { schemaPath: text }\n  problem_404 => problem & { status: = 404 }"))).getMessage();
        assertTrue(twice.contains("both fix status 404"), twice);
    }

    /** A verb and a path are one endpoint across every api a description holds, since a server serves one handler. */
    @Test
    void twoEndpointsCannotShareAVerbAndPath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> described(API.replace("\n}", """

                  again => !apis { again => { "/{+schemaPath}" => !resource {
                    @safe GET => !document { request: schema_ref  media_type: "text/plain" } } } }
                }"""))).getMessage();
        assertTrue(refused.contains("'GET /{+schemaPath}' is declared twice"), refused);
    }

    /** And the payload's own shape is checked against the record's declaration, field by field. */
    @Test
    void aMisspeltEndpointFieldIsRefused() {
        String refused = only(API.replace("status: 201 }", "stauts: 201 }"));
        assertTrue(refused.contains("stauts"), refused);
    }

    // ── templates, in the shipping design ──

    private static final String PAGED = """
            !!id:"https://schemas.example.com/2026/38/app/orders-api-1.tn"
            !!meta:"https://tson.io/2026/38/io/ltr8/http/meta-service.tn"
            !!import:"https://schemas.example.com/2026/38/app/order-1.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              page       => <T> { items: [T]  next?: uri  total: int32 }
              order_page => page<order>

              orders_api => !apis { orders_api => { "/orders" => !resource {
                @safe GET => !operation { response: %s } } } }
            }""";

    /**
     * <b>Templates work, and an endpoint references an application by name.</b> {@code page<order>} is written once
     * and applied, where OpenAPI hand-rolls the envelope per endpoint or bolts it on with {@code allOf}. The
     * application is a real entry with structural identity (§8.2), so two endpoints returning a page of orders
     * share one.
     */
    @Test
    void anEndpointReferencesATemplateApplication() {
        Tson tson = resolved(PAGED.formatted("order_page"));
        Route list = TsonApiSchema.describedBy(tson, API_ID).route(HttpVerb.GET, "/orders").orElseThrow();
        assertEquals("order_page", list.response().orElseThrow().name());

        // A declaration naming an application is that application's entry (§8.2): no hop onto a minted name.
        var resolved = tson.schemaRegistry().get(API_ID).orElseThrow().schema();
        assertEquals("page", resolved.applicationOf("order_page").orElseThrow().name(),
                "§8.2 records the application that built it");
    }

    /**
     * <b>An application is named through an alias, never written inline.</b> {@code response} is typed by a kind,
     * so it holds the name of a type: one bare token. An endpoint is a value body, where nothing lifts or mints, so
     * an application written there would be the name of no entry. §5.6's braced form is therefore refused, naming
     * the alias spelling that gives the application its entry; the schema sugar {@code page<order>} in the same
     * position is a parse error, before resolution is reached.
     */
    @Test
    void anApplicationIsNamedThroughAnAliasNotInline() {
        String message = assertThrows(RuntimeException.class,
                () -> resolved(PAGED.formatted("{ name: page  arguments: [ { name: order } ] }"))).getMessage();
        assertTrue(message.contains("declare an alias"), "the refusal names the alias spelling: " + message);

        assertTrue(assertThrows(RuntimeException.class, () -> resolved(PAGED.formatted("page<order>")))
                .getMessage().contains("adjacent values must be separated"));
    }

    /** A bad argument is caught where the application is spelled, which is the alias. */
    @Test
    void aBadTemplateArgumentIsCaughtAtTheAlias() {
        assertTrue(assertThrows(RuntimeException.class,
                () -> resolved(PAGED.formatted("order_page").replace("page<order>", "page<no_such>")))
                .getMessage().contains("unresolved reference 'no_such'"));
    }

    // ── what a server can derive from its description, instead of listing it ──

    /**
     * <b>Everything a client needs to resolve the contract</b>: the description, the meta layer governing it,
     * and its imports transitively. The bundled standard library is excluded -- every implementation has it,
     * and a service publishing {@code core.tn} would be claiming ownership of something it does not own.
     */
    @Test
    void aDescriptionKnowsWhatItsServerMustPublish() {
        Set<String> published = described(API).referencedSchemas();

        assertTrue(published.contains(API_ID), "itself: a description a client cannot fetch is no contract");
        assertTrue(published.contains(TsonApiSchema.ID), "the meta layer, or the description will not resolve");
        assertTrue(published.contains(ORDER_ID));
        assertTrue(published.contains(TsonProblemSchema.ID));
        assertFalse(published.stream().anyMatch(id -> id.startsWith("https://tson.io/2026/38/m/")),
                () -> "the bundled standard library is not a service's to publish: " + published);
    }

    /**
     * <b>And which classes to warm.</b> Hand-listing them is how a response type added to a description never
     * gets warmed — nothing connects the two lists, and the omission costs only latency, so nothing reports
     * it. A payload type nobody binds is skipped rather than refused.
     */
    @Test
    void aDescriptionKnowsWhichClassesToWarm() {
        TsonApiDescription api = described(API);

        assertEquals(Set.of("order", "sku_not_found", "schema_ref"), api.payloadTypes());
        assertEquals(List.of(String.class), api.boundClasses(Map.of("order", String.class)),
                "only what the bindings map names");
    }

    // ── holding a server to its own description ──

    /**
     * <b>An endpoint declared and served by nobody is a promise the server does not keep</b>, and it fails for the
     * client that read the contract and believed it. Both halves are known before a request exists, so this is a
     * startup failure.
     */
    @Test
    void anUnservedEndpointIsRefusedAtStartup() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");

        assertEquals(Set.of("GET /{+schemaPath}"), coverage.unserved());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, coverage::requireComplete);
        assertTrue(thrown.getMessage().contains("GET /{+schemaPath}"), thrown.getMessage());
    }

    /** And claiming everything passes -- the shape a demo's startup ends with. */
    @Test
    void aFullyServedDescriptionIsComplete() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));

        assertEquals("/orders", coverage.serving(HttpVerb.POST, "/orders").path(),
                "serving hands back the endpoint, so nothing about it is written twice");
        coverage.serving(HttpVerb.GET, "/{+schemaPath}");

        assertEquals(Set.of(), coverage.unserved());
        coverage.requireComplete();
    }

    /**
     * <b>Drift the other way.</b> A handler claiming an endpoint the description does not declare is a route moved
     * in one place and not the other, and it fails where it is written rather than as a mystery 404 later.
     */
    @Test
    void servingAnEndpointTheDescriptionDoesNotDeclareIsRefused() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> coverage.serving(HttpVerb.POST, "/order"));
        assertTrue(thrown.getMessage().contains("POST /order'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("POST /orders"), "and says what it does declare");
    }

    /** Two handlers for one endpoint is a bug: only one of them can ever be reached. */
    @Test
    void claimingOneEndpointTwiceIsRefused() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");

        assertTrue(assertThrows(IllegalStateException.class, () -> coverage.serving(HttpVerb.POST, "/orders"))
                .getMessage().contains("already served"));
    }

    /**
     * <b>The opinion has a way out, and it costs a reason.</b> An endpoint documented ahead of being built, or
     * served by another process behind the same proxy, is a real case — and a check that cannot express it gets
     * switched off wholesale, taking the endpoints it was right about with it.
     */
    @Test
    void aDeclaredEndpointCanBeExemptedWithAReason() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");
        coverage.notServedHere(HttpVerb.GET, "/{+schemaPath}", "served by the static origin in front of this service");

        assertEquals(Set.of(), coverage.unserved());
        coverage.requireComplete();
        assertEquals(Map.of("GET /{+schemaPath}", "served by the static origin in front of this service"),
                coverage.exemptions(), "readable back, so a server can log or publish what it does not serve");
    }

    /** A reason is required: without one an exemption is indistinguishable from a hole punched to go green. */
    @Test
    void anExemptionWithoutAReasonIsRefused() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> coverage.notServedHere(HttpVerb.GET, "/{+schemaPath}", "  ")).getMessage()
                .contains("needs a reason"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> coverage.notServedHere(HttpVerb.GET, "/nowhere", "because")).getMessage()
                .contains("GET /nowhere"), "and it is still an endpoint the description has to declare");
    }

    /** Served and exempt are contradictory, in either order. */
    @Test
    void anEndpointCannotBeBothServedAndExempt() {
        TsonApiDescription described = described(API);

        TsonApiCoverage one = TsonApiCoverage.of(described);
        one.serving(HttpVerb.POST, "/orders");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> one.notServedHere(HttpVerb.POST, "/orders", "why")).getMessage()
                .contains("cannot also be exempt"));

        TsonApiCoverage other = TsonApiCoverage.of(described);
        other.notServedHere(HttpVerb.POST, "/orders", "why");
        assertTrue(assertThrows(IllegalStateException.class, () -> other.serving(HttpVerb.POST, "/orders"))
                .getMessage().contains("cannot also be served"));
    }

    /** And the failure names the way out, so it is discoverable from the message rather than the Javadoc. */
    @Test
    void theIncompleteMessageNamesTheWayOut() {
        TsonApiCoverage coverage = TsonApiCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");

        assertTrue(assertThrows(IllegalStateException.class, coverage::requireComplete)
                .getMessage().contains("notServedHere"));
    }
}
