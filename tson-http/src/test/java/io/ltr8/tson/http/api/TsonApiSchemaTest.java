package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonBindings;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.api.RequestPlacement.Location;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping service-description layer: protocols and http projections declared in a schema importing {@code
 * service.tn}, with payload types and exchange references the compiler resolves, read back by {@link
 * TsonApiDescription} and held to a server by {@link TsonApiCoverage}. The reader's relational checks are {@link
 * TsonApiDescriptionTest}'s.
 */
class TsonApiSchemaTest {

    private static final String ORDER_ID = "https://schemas.example.com/2026/40/app/order-1.tn";
    private static final String API_ID = "https://schemas.example.com/2026/40/app/orders-api-1.tn";

    private static final String ORDER = """
            !!id:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
            !schema {
              non_empty_text => !text ^ { min_length: 1 }
              order          => { sku: non_empty_text  quantity: int32 }
              sku_not_found  => problem.details & { status: = 404  sku: text }
            }""";

    /** A protocol, an http projection binding it, and the schema route as a document. */
    private static final String API = """
            !!id:"https://schemas.example.com/2026/40/app/orders-api-1.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/service.tn"
            !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              schema_ref => { schemaPath: text }

              orders => !service.protocol {
                @doc:"Accept an order and confirm it, with the quantity doubled."
                place_order => { request: order  response: order  errors: [sku_not_found] }
              }

              @service.binds:[orders]
              orders_http => !service.http {
                "/orders" => {
                  @service.summary:"Place an order"
                  POST => !service.binding { exchange: orders.place_order  status: 201 }
                }
                "/{+schemaPath}" => {
                  @service.safe
                  @service.summary:"Fetch a published schema"
                  GET => !service.document { request: schema_ref  media_types: ["application/tson"] }
                }
              }
            }""";

    private static Tson tson(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.putAll(TsonApiSchema.publishedById());
        lib.put(ORDER_ID, ORDER);
        lib.putAll(TsonProblemSchema.publishedById());
        lib.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))
                .withDataBindContext(TsonBindings.of(TsonApiSchema.bindings())));
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
        assertEquals(Optional.of(new ExchangeName("orders", "place_order")), create.exchange());
        assertEquals("order", create.request().orElseThrow().name(), "borrowed from the exchange");
        assertEquals("order", create.response().orElseThrow().name());
        assertEquals(201, create.status());
        assertEquals("sku_not_found", create.errorFor(404).orElseThrow().name(), "by the status its type fixes");
        assertEquals(Optional.of("Place an order"), create.summary(), "the verb key's @service.summary");
        assertEquals(Optional.of("Accept an order and confirm it, with the quantity doubled."), create.doc(),
                "the exchange's @doc, since the verb key carries none");
        assertEquals(List.of("sku", "quantity"), create.placement().at(Location.BODY), "a POST's remainder");

        Route schema = api.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertTrue(schema.document());
        assertEquals(Set.of("application/tson"), schema.mediaTypes());
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

    /** An entry applying {@code protocol} or {@code http} is a value, never a type, so it cannot be used as one. */
    @Test
    void aProtocolEntryCannotBeUsedWhereATypeBelongs() {
        String refused = only(API.replace("\n}", "\n  holder => { s: orders }\n}"));
        assertTrue(refused.contains("a value and not a type"), refused);
    }

    /**
     * The records inside are records, not constructors, so an entry tagged with one is a value of it (§4.1): judged
     * as a value of the record, as a data document's would be, and read into no route -- a description's routes are
     * the values of {@code http} alone.
     */
    @Test
    void anInnerRecordHeadsAnEntryAsAValueOfIt() {
        for (String record : List.of("exchange", "operation", "document")) {
            String api = API.replace("\n}", "\n  x => !service." + record + " { }\n}");
            assertEquals(List.of(), tson(api).validateSchema(api), record);
            assertEquals(described(API).routes(), described(api).routes(), record + " adds no route");
        }
        for (String record : List.of("resource", "binding")) {
            String refused = only(API.replace("\n}", "\n  x => !service." + record + " { }\n}"));
            assertTrue(refused.contains("the body is no value of the type 'service." + record + "'")
                    && refused.contains("missing required field"), refused);
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

    // ── paths, media types, documents and errors ──

    /** {@link #API} with the schema route's path replaced. */
    private static String withSchemaPath(String path) {
        return API.replace("\"/{+schemaPath}\" =>", "\"" + path + "\" =>");
    }

    /**
     * <b>A path is a URI template, checked by its shape at schema load</b>: levels 1 and 2, absolute. {@code
     * {+schemaPath}} -- reserved expansion, what a path with slashes in it needs -- is admitted, as are a dot, a
     * percent-encoded octet and RFC 3986's other path characters; the fragment operator, a level-3 form, an
     * unclosed or stray brace, a broken percent-encoding, a space and a path not starting with {@code /} are not.
     * The character set is the identifier family's and the structure its {@code pattern}'s, and either refusal
     * names the role.
     */
    @Test
    void aPathIsAUriTemplateCheckedAtSchemaLoad() {
        for (String admitted : List.of("/{schemaPath}", "/{+schemaPath}", "/schemas/{+schemaPath}",
                "/v1.2/{+schemaPath}", "/a%2Fb/{schemaPath}", "/x:y@z/{schemaPath}")) {
            assertTrue(described(withSchemaPath(admitted)).route(HttpVerb.GET, admitted).isPresent(), admitted);
        }
        for (String refused : List.of("/{#schemaPath}", "/{?schemaPath}", "/{schemaPath", "/a}/{schemaPath}",
                "/a%zz/{schemaPath}", "/a b/{schemaPath}", "{+schemaPath}")) {
            String message = only(withSchemaPath(refused));
            assertTrue(message.contains("path_template"), refused + ": " + message);
        }
    }

    /**
     * <b>An endpoint lists the media types it speaks</b>, because a client reading the description has no other way
     * to know it takes JSON. Absent is {@code application/tson} alone; a list is read beside it, and a JSON label is
     * listed, never implied: either label alone speaks the JSON encoding, and Accept chooses among what is listed.
     */
    @Test
    void anEndpointListsTheMediaTypesItSpeaks() {
        TsonApiDescription plain = described(API);
        Route tsonOnly = plain.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Set.of("application/tson"), tsonOnly.mediaTypes(), "absent is application/tson alone");
        assertFalse(tsonOnly.speaksJson());

        for (String label : List.of("application/json", "application/tson+json", "Application/JSON")) {
            TsonApiDescription api = described(API.replace("status: 201 }",
                    "status: 201  media_types: [\"" + label + "\"] }"));
            Route create = api.route(HttpVerb.POST, "/orders").orElseThrow();
            assertTrue(create.speaksJson(), label);
            assertEquals(List.of("application/tson", label.toLowerCase(Locale.ROOT)),
                    List.copyOf(create.mediaTypes()), "application/tson first, then what is listed, folded");
        }

        Route schema = plain.route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertFalse(schema.speaksJson());
    }

    /**
     * <b>A media type no codec here can speak is refused at startup</b>, the shape of {@code prepareToRead}: an
     * endpoint answering a TSON value can be written only in an encoding a codec holds, so a description naming
     * another fails to read and never answers 500. A document's body is bytes, so any type labels it.
     */
    @Test
    void aMediaTypeNoCodecSpeaksIsRefusedAtStartup() {
        String refused = assertThrows(IllegalArgumentException.class, () -> described(API.replace("status: 201 }",
                "status: 201  media_types: [\"application/xml\"] }"))).getMessage();
        assertEquals("'orders_http POST /orders' lists the media type 'application/xml', which no codec this server "
                + "holds can speak -- an endpoint answering a TSON value speaks [application/json, application/tson, "
                + "application/tson+json]; an endpoint answering bytes is a !service.document", refused);

        Route page = described(API.replace("media_types: [\"application/tson\"]", "media_types: [\"text/html\"]"))
                .route(HttpVerb.GET, "/{+schemaPath}").orElseThrow();
        assertEquals(Set.of("text/html"), page.mediaTypes(), "a document implies no application/tson");
    }

    /**
     * <b>A document lists a media type</b>: none is implied for it, and {@code media_types} is optional on the base
     * record, so only the reader can require it.
     */
    @Test
    void aDocumentListingNoMediaTypeIsRefused() {
        String refused = assertThrows(IllegalArgumentException.class, () -> described(API.replace(
                "  media_types: [\"application/tson\"]", ""))).getMessage();
        assertEquals("'orders_http GET /{+schemaPath}' is a document listing no media type -- a document's body is "
                + "bytes, so media_types is the whole of its contract; list one", refused);
    }

    /** A media type is a type and a subtype; anything else is refused at load. */
    @Test
    void aMediaTypeIsATypeAndASubtype() {
        String refused = only(API.replace("media_types: [\"application/tson\"]", "media_types: [\"tson\"]"));
        assertTrue(refused.contains("media_type"), refused);
    }

    /**
     * <b>A business error fixes its status</b>, which is how a client tells it from the response by status alone,
     * and two errors cannot fix the same one. Both are refused when the description is read.
     */
    @Test
    void anErrorFixesAStatusOfItsOwn() {
        String unfixed = ORDER.replace("sku_not_found  => problem.details & { status: = 404  sku: text }",
                "sku_not_found  => problem.details & { sku: text }");
        Tson tson = tson(API);
        tson.resolve(unfixed);
        tson.resolve(API);
        String message = assertThrows(IllegalArgumentException.class,
                () -> TsonApiSchema.describedBy(tson, API_ID)).getMessage();
        assertTrue(message.contains("'sku_not_found', which fixes no status"), message);

        String twice = assertThrows(IllegalArgumentException.class, () -> described(API.replace(
                "errors: [sku_not_found]", "errors: [sku_not_found problem_404]").replace(
                "schema_ref => { schemaPath: text }",
                "schema_ref => { schemaPath: text }\n  problem_404 => problem.details & { status: = 404 }"))).getMessage();
        assertTrue(twice.contains("both fix status 404"), twice);
    }

    /**
     * A verb and a path are one endpoint across every http projection a description holds, since a server serves one
     * handler.
     */
    @Test
    void twoEndpointsCannotShareAVerbAndPath() {
        String refused = assertThrows(IllegalArgumentException.class, () -> described(API.replace("\n}", """

                  again => !service.http { "/{+schemaPath}" => {
                    @service.safe GET => !service.document { request: schema_ref  media_types: ["text/plain"] } } }
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
            !!id:"https://schemas.example.com/2026/40/app/orders-api-1.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/service.tn"
            !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              page       => <T> { items: [T]  next?: uri  total: int32 }
              order_page => page<order>

              orders_http => !service.http { "/orders" => {
                @service.safe GET => !service.operation { response: %s } } }
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
        assertEquals("page", resolved.applicationOf("order_page").orElseThrow().head().name(),
                "§8.2 records the application that built it");
    }

    /**
     * <b>An application is named through an alias, never written inline.</b> {@code response} is typed by a kind,
     * so it holds the name of a type: one bare token. An endpoint is a value body, where a reference-typed slot holds
     * a name and nothing else. A record written there is refused as no type's name, and the schema sugar {@code
     * page<order>} is a parse error, the data grammar holding no argument list ([TSON-DATA] §7.2.5). The remedy is a
     * declaration that gives the application its entry ({@code order_page => page<order>}), whose name the slot holds.
     */
    @Test
    void anApplicationIsNamedThroughAnAliasNotInline() {
        assertEquals("'orders_http': the body is no value of the type 'service.http' -- at "
                        + "/~1orders/endpoints/GET/response: expected a type's name, found a record -- a position "
                        + "typed by a kind holds the name of a type, one bare token",
                only(PAGED.formatted("{ head: page  arguments: [order] }")));

        String sugar = PAGED.formatted("page<order>");
        List<Diagnostic> problems = tson(sugar).validateSchema(sugar);
        assertEquals(List.of(Diagnostic.Code.VALIDATION_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        assertEquals("adjacent values must be separated by whitespace, a comma, or both",
                problems.getFirst().message(), "the parser's ordinary refusal at the '<'");
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
     * <b>Everything a client needs to resolve the contract</b>: the description and its imports transitively, {@code
     * service.tn} among them. The bundled standard library is excluded -- every implementation has it, and a
     * service publishing {@code core.tn} would be claiming ownership of something it does not own.
     */
    @Test
    void aDescriptionKnowsWhatItsServerMustPublish() {
        Set<String> published = described(API).referencedSchemas();

        assertTrue(published.contains(API_ID), "itself: a description a client cannot fetch is no contract");
        assertTrue(published.contains(TsonApiSchema.ID), "the meta layer, or the description will not resolve");
        assertTrue(published.contains(ORDER_ID));
        assertTrue(published.contains(TsonProblemSchema.ID));
        assertFalse(published.stream().anyMatch(id -> id.startsWith("https://tson.io/2026/40/m/")),
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
