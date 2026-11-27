package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.RequestPlacement.Location;
import io.ltr8.tson.http.schema.ApiKey;
import io.ltr8.tson.http.schema.ApiKeyIn;
import io.ltr8.tson.http.schema.Endpoint;
import io.ltr8.tson.http.schema.HttpAuth;
import io.ltr8.tson.http.schema.HttpProjection;
import io.ltr8.tson.http.schema.HttpVerb;
import io.ltr8.tson.http.schema.SecurityScheme;
import io.ltr8.tson.service.ExchangeName;
import io.ltr8.tson.service.TsonBindings;
import io.ltr8.tson.service.TsonServiceSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipping http layer: protocols and http projections declared in a schema importing {@code http.tn} alone, which
 * sees {@code service.tn}'s names through its import, with payload types and exchange references the compiler
 * resolves, read back by {@link TsonServiceDescription} and held to a server by {@link TsonServiceCoverage}. The
 * reader's relational checks are {@link TsonServiceDescriptionTest}'s; {@code service.tn} on its own is {@code
 * TsonServiceSchemaTest}'s.
 */
class TsonHttpSchemaTest {

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
            !!import:"https://tson.io/2026/40/io/ltr8/http/http.tn"
            !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              schema_ref => { schemaPath: text }

              orders => !service.protocol {
                exchanges: {
                  @doc:"Accept an order and confirm it, with the quantity doubled."
                  place_order => { request: order  response: order  errors: [sku_not_found] }
                }
              }

              orders_http => !http.projection {
                binds: [orders]
                resources: {
                  "/orders" => {
                    @service.summary:"Place an order"
                    POST => !http.binding { exchange: orders.exchanges.place_order  status: 201 }
                  }
                  "/{+schemaPath}" => {
                    @service.safe
                    @service.summary:"Fetch a published schema"
                    GET => !http.document { request: schema_ref  media_types: ["application/tson"] }
                  }
                }
              }
            }""";

    private static Tson tson(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.putAll(TsonHttpSchema.publishedById());
        lib.put(ORDER_ID, ORDER);
        lib.putAll(TsonProblemSchema.publishedById());
        lib.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))
                .withDataBindContext(TsonBindings.of(TsonHttpSchema.bindings())));
    }

    private static Tson resolved(String api) {
        Tson tson = tson(api);
        tson.resolve(api);
        return tson;
    }

    private static TsonServiceDescription described(String api) {
        return TsonHttpSchema.describedBy(resolved(api), API_ID);
    }

    /** The one diagnostic resolving {@code api} gives, or a failure naming them all. */
    private static String only(String api) {
        List<Diagnostic> problems = tson(api).validateSchema(api);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    private static Set<String> keys(TsonServiceDescription described) {
        Set<String> keys = new TreeSet<>();
        described.routes().forEach(route -> keys.add(route.key()));
        return keys;
    }

    @Test
    void theServiceIsReadBackFromTheResolvedSchema() {
        TsonServiceDescription api = described(API);
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

    /**
     * An entry applying {@code service.protocol} or {@code http.projection} is a value, never a type, so it cannot be
     * used as one.
     */
    @Test
    void aProtocolEntryCannotBeUsedWhereATypeBelongs() {
        String refused = only(API.replace("\n}", "\n  holder => { s: orders }\n}"));
        assertTrue(refused.contains("a value and not a type"), refused);
    }

    /**
     * The records inside are records, not constructors, so an entry tagged with one is a value of it (§4.1): judged
     * as a value of the record, as a data document's would be, and read into no route -- a description's routes are
     * the values of {@code http.projection} alone.
     */
    @Test
    void anInnerRecordHeadsAnEntryAsAValueOfIt() {
        for (String record : List.of("service.exchange", "http.operation", "http.document")) {
            String api = API.replace("\n}", "\n  x => !" + record + " { }\n}");
            assertEquals(List.of(), tson(api).validateSchema(api), record);
            assertEquals(described(API).routes(), described(api).routes(), record + " adds no route");
        }
        for (String record : List.of("http.resource", "http.binding")) {
            String refused = only(API.replace("\n}", "\n  x => !" + record + " { }\n}"));
            assertTrue(refused.contains("the body is no value of the type '" + record + "'")
                    && refused.contains("missing required field"), refused);
        }
    }

    /** The base's vocabulary is inherited by every kind of endpoint, so its constraints reach them all. */
    @Test
    void anEndpointsBaseConstraintsReachEveryKind() {
        String refused = only(API.replace("status: 201", "status: 999"));
        assertTrue(refused.contains("'999' is greater than the maximum 599"), refused);
    }

    /**
     * The http layer is a document a server publishes, so its own identity has to be what it says; and a server
     * publishing it publishes the {@code service.tn} it imports, or a client fetching it cannot resolve it.
     */
    @Test
    void theHttpSchemaDeclaresTheIdentityItIsServedAt() {
        assertTrue(TsonHttpSchema.source().startsWith("!!id:\"" + TsonHttpSchema.ID + "\""),
                TsonHttpSchema.source().lines().findFirst().orElse(""));
        assertTrue(TsonHttpSchema.source().contains("!!import:\"" + TsonServiceSchema.ID + "\""));
        assertEquals(List.of(TsonHttpSchema.source()), TsonHttpSchema.publishedSources());
        assertEquals(Map.of(TsonHttpSchema.ID, TsonHttpSchema.source(), TsonServiceSchema.ID,
                TsonServiceSchema.source()), TsonHttpSchema.publishedById());
    }

    /**
     * <b>{@code http.tn} resolves</b> from what {@link TsonHttpSchema#publishedById()} holds. Its namespace is
     * its own entries under {@code http} and its closure's, {@code service.tn}'s among them, so {@code http.projection}
     * and {@code service.protocol} are both in it; and {@code service} holds the protocol side alone, no HTTP member
     * left under it.
     */
    @Test
    void theHttpSchemaResolvesFromWhatItPublishes() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(TsonHttpSchema.publishedById())))
                .withDataBindContext(TsonBindings.of(TsonHttpSchema.bindings())));
        assertEquals(List.of(), tson.validateSchema(TsonHttpSchema.source()));
        Set<String> names = tson.schemaRegistry().get(TsonHttpSchema.ID).orElseThrow().schema().entries()
                .keySet();
        assertTrue(names.containsAll(Set.of("http", "http.projection", "http.verb", "http.auth", "service.protocol")),
                "" + names);
        assertEquals(Set.of("service.signature", "service.exchange", "service.exchanges", "service.protocol",
                "service.exchange_name", "service.summary", "service.safe", "service.idempotent", "service.destructive"),
                names.stream().filter(name -> name.startsWith("service.")).collect(Collectors.toSet()));
    }

    /**
     * Both layers' records bind through one map, keyed by qualified name: every entry of {@link
     * TsonServiceSchema#bindings()} unchanged, and {@code http.tn}'s records under {@code http}, the key set exactly
     * those names; and an application binding one of {@code service.tn}'s names is refused.
     */
    @Test
    void bothLayersBindThroughOneMap() {
        Map<String, Class<?>> bindings = TsonHttpSchema.bindings();
        assertTrue(bindings.entrySet().containsAll(TsonServiceSchema.bindings().entrySet()), "" + bindings);
        assertEquals(Set.of("service.protocol", "service.exchange", "service.signature", "http.projection",
                "http.resource", "http.endpoint", "http.operation", "http.binding", "http.document", "http.exemption",
                "http.verb", "http.security_scheme", "http.auth", "http.api_key", "http.api_key_in"), bindings.keySet());
        assertEquals(HttpProjection.class, bindings.get("http.projection"));
        assertEquals(HttpVerb.class, bindings.get("http.verb"));
        assertEquals(Endpoint.class, bindings.get("http.endpoint"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TsonHttpSchema.bindingsWith(
                Map.of("service.protocol", String.class))).getMessage().contains("service.tn's vocabulary"));
    }

    /**
     * The security schemes bind as the endpoints do: the abstract base to a sealed interface, each subtype to its
     * record, the enum as {@code http.verb} does; and an application binding one of those names is refused, since
     * within one closure a name means one thing.
     */
    @Test
    void theSecuritySchemesBindToTheirRecords() {
        Map<String, Class<?>> bindings = TsonHttpSchema.bindings();
        assertEquals(SecurityScheme.class, bindings.get("http.security_scheme"));
        assertEquals(HttpAuth.class, bindings.get("http.auth"));
        assertEquals(ApiKey.class, bindings.get("http.api_key"));
        assertEquals(ApiKeyIn.class, bindings.get("http.api_key_in"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TsonHttpSchema.bindingsWith(
                Map.of("http.auth", String.class))).getMessage().contains("'http.auth' is bound by the application and "
                + "by http.tn's vocabulary"));
    }

    // ── paths, media types, documents and errors ──

    /** {@link #API} with the schema route's path replaced. */
    private static String withSchemaPath(String path) {
        return API.replace("\"/{+schemaPath}\" =>", "\"" + path + "\" =>");
    }

    /** {@link #API} with the schema route's path replaced, and the order route moved out of the way of {@code path}. */
    private static String withPathKey(String path) {
        return API.replace("\"/orders\" =>", "\"/placed\" =>").replace("\"/{+schemaPath}\" =>", "\"" + path + "\" =>");
    }

    /**
     * <b>A path is a URI template, checked by its shape at schema load</b>: levels 1 and 2, absolute, ASCII-exact. A
     * {@code /}, then RFC 3986's path characters, percent-encoded octets and {@code {var}} or {@code {+var}}
     * expressions -- {@code {+schemaPath}}, reserved expansion, being what a path with slashes in it needs. A
     * character outside ASCII, a broken percent-encoding, a stray or unclosed brace, a variable outside RFC 6570's
     * varname, the fragment and query operators, a space and a path not starting with {@code /} are each refused at
     * the key, by the {@code pattern} of {@code http.path_template}, a text family: a path is no name.
     */
    @Test
    void aPathIsAUriTemplateCheckedAtSchemaLoad() {
        for (String admitted : List.of("/", "/orders", "/orders/{id}", "/schemas/{+path}", "/a%2Fb",
                "/a.b-c_d~e!$&'()*+,;=:@", "/{schemaPath}", "/v1.2/{+schemaPath}")) {
            String api = withPathKey(admitted);
            assertEquals(List.of(), tson(api).validateSchema(api), admitted);
        }
        for (String admitted : List.of("/{+schemaPath}", "/schemas/{+schemaPath}", "/a%2Fb/{schemaPath}")) {
            assertTrue(described(withSchemaPath(admitted)).route(HttpVerb.GET, admitted).isPresent(), admitted);
        }
        for (String refused : List.of("/订单", "/a%2", "/a{", "/{a-b}", "orders", "/{{id}}", "/{#schemaPath}",
                "/{?schemaPath}", "/a}/{schemaPath}", "/a b/{schemaPath}")) {
            String at = "/resources/" + refused.replace("~", "~0").replace("/", "~1");
            String why = "'http.path_template': '" + refused + "' does not match the required pattern ";

            String api = withPathKey(refused);
            List<Diagnostic> inSchema = tson(api).validateSchema(api);
            assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), inSchema.stream().map(Diagnostic::code).toList(),
                    () -> refused + ": " + inSchema);
            assertTrue(inSchema.getFirst().message().contains("at " + at + ": " + why), inSchema.getFirst().message());

            String data = "!!import:\"" + TsonHttpSchema.ID + "\"\n"
                    + "!http.projection { resources: { \"" + refused + "\" => { endpoints: {} } } }";
            List<Diagnostic> inData = tson(API).validate(data);
            assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                    inData.stream().map(Diagnostic::code).toList(), () -> refused + ": " + inData);
            assertEquals(Optional.of(at), inData.getFirst().path(), refused);
            assertTrue(inData.getFirst().message().startsWith(why), inData.getFirst().message());
        }
    }

    /**
     * <b>An endpoint lists the media types it speaks</b>, because a client reading the description has no other way
     * to know it takes JSON. Absent is {@code application/tson} alone; a list is read beside it, and a JSON label is
     * listed, never implied: either label alone speaks the JSON encoding, and Accept chooses among what is listed.
     */
    @Test
    void anEndpointListsTheMediaTypesItSpeaks() {
        TsonServiceDescription plain = described(API);
        Route tsonOnly = plain.route(HttpVerb.POST, "/orders").orElseThrow();
        assertEquals(Set.of("application/tson"), tsonOnly.mediaTypes(), "absent is application/tson alone");
        assertFalse(tsonOnly.speaksJson());

        for (String label : List.of("application/json", "application/tson+json", "Application/JSON")) {
            TsonServiceDescription api = described(API.replace("status: 201 }",
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
                + "application/tson+json]; an endpoint answering bytes is a !http.document", refused);

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
                () -> TsonHttpSchema.describedBy(tson, API_ID)).getMessage();
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

                  again => !http.projection { resources: { "/{+schemaPath}" => {
                      @service.safe GET => !http.document { request: schema_ref  media_types: ["text/plain"] } } } }
                }"""))).getMessage();
        assertTrue(refused.contains("'GET /{+schemaPath}' is declared twice"), refused);
    }

    /** And the payload's own shape is checked against the record's declaration, field by field. */
    @Test
    void aMisspeltEndpointFieldIsRefused() {
        String refused = only(API.replace("status: 201 }", "stauts: 201 }"));
        assertTrue(refused.contains("stauts"), refused);
    }

    // ── parametric records, in the shipping design ──

    private static final String PAGED = """
            !!id:"https://schemas.example.com/2026/40/app/orders-api-1.tn"
            !!import:"https://tson.io/2026/40/io/ltr8/http/http.tn"
            !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              page       => <T> { items: [T]  next?: uri  total: int32 }
              order_page => page<order>

              orders_http => !http.projection { resources: { "/orders" => {
                  @service.safe GET => !http.operation { response: %s } } } }
            }""";

    /**
     * <b>Parametric records work, and an endpoint references an application by name.</b> {@code page<order>} is
     * written once and applied, where OpenAPI hand-rolls the envelope per endpoint or bolts it on with {@code allOf}.
     * The application is a real entry with structural identity (§8.2), so two endpoints returning a page of orders
     * share one.
     */
    @Test
    void anEndpointReferencesAnApplication() {
        Tson tson = resolved(PAGED.formatted("order_page"));
        Route list = TsonHttpSchema.describedBy(tson, API_ID).route(HttpVerb.GET, "/orders").orElseThrow();
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
        assertEquals("'orders_http': the body is no value of the type 'http.projection' -- at "
                        + "/resources/~1orders/endpoints/GET/response: expected a type's name, found a record -- a position "
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
    void aBadArgumentIsCaughtAtTheAlias() {
        assertTrue(assertThrows(RuntimeException.class,
                () -> resolved(PAGED.formatted("order_page").replace("page<order>", "page<no_such>")))
                .getMessage().contains("unresolved reference 'no_such'"));
    }

    // ── what a server can derive from its description, instead of listing it ──

    /**
     * <b>Everything a client needs to resolve the contract</b>: the description and its imports transitively, {@code
     * http.tn} and the {@code service.tn} it imports among them. The bundled standard library is excluded -- every
     * implementation has it, and a service publishing {@code core.tn} would be claiming ownership of something it does
     * not own.
     */
    @Test
    void aDescriptionKnowsWhatItsServerMustPublish() {
        Set<String> published = described(API).referencedSchemas();

        assertTrue(published.contains(API_ID), "itself: a description a client cannot fetch is no contract");
        assertTrue(published.contains(TsonHttpSchema.ID), "the http layer, or the description will not resolve");
        assertTrue(published.contains(TsonServiceSchema.ID), "the protocol layer, which the http layer imports");
        assertTrue(published.contains(ORDER_ID));
        assertTrue(published.contains(TsonProblemSchema.ID));
        assertFalse(published.stream().anyMatch(id -> id.startsWith("https://tson.io/2026/40/m/")),
                () -> "the bundled standard library is not a service's to publish: " + published);
    }

    /**
     * <b>And which classes to warm.</b> Hand-listing them is how a response type added to a description never
     * gets warmed — nothing connects the two lists, and the omission costs only latency, so nothing reports
     * it. Each payload type is named as the binder is asked it, by the schema declaring it and its name there, and
     * one nobody binds is skipped rather than refused.
     */
    @Test
    void aDescriptionKnowsWhichClassesToWarm() {
        TsonServiceDescription api = described(API);

        assertEquals(Set.of(new PayloadType(ORDER_ID, "order"), new PayloadType(ORDER_ID, "sku_not_found"),
                new PayloadType(API_ID, "schema_ref")), api.payloadTypes());
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
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");

        assertEquals(Set.of("GET /{+schemaPath}"), coverage.unserved());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, coverage::requireComplete);
        assertTrue(thrown.getMessage().contains("GET /{+schemaPath}"), thrown.getMessage());
    }

    /** And claiming everything passes -- the shape a demo's startup ends with. */
    @Test
    void aFullyServedDescriptionIsComplete() {
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));

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
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> coverage.serving(HttpVerb.POST, "/order"));
        assertTrue(thrown.getMessage().contains("POST /order'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("POST /orders"), "and says what it does declare");
    }

    /** Two handlers for one endpoint is a bug: only one of them can ever be reached. */
    @Test
    void claimingOneEndpointTwiceIsRefused() {
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));
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
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));
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
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));

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
        TsonServiceDescription described = described(API);

        TsonServiceCoverage one = TsonServiceCoverage.of(described);
        one.serving(HttpVerb.POST, "/orders");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> one.notServedHere(HttpVerb.POST, "/orders", "why")).getMessage()
                .contains("cannot also be exempt"));

        TsonServiceCoverage other = TsonServiceCoverage.of(described);
        other.notServedHere(HttpVerb.POST, "/orders", "why");
        assertTrue(assertThrows(IllegalStateException.class, () -> other.serving(HttpVerb.POST, "/orders"))
                .getMessage().contains("cannot also be served"));
    }

    /** And the failure names the way out, so it is discoverable from the message rather than the Javadoc. */
    @Test
    void theIncompleteMessageNamesTheWayOut() {
        TsonServiceCoverage coverage = TsonServiceCoverage.of(described(API));
        coverage.serving(HttpVerb.POST, "/orders");

        assertTrue(assertThrows(IllegalStateException.class, coverage::requireComplete)
                .getMessage().contains("notServedHere"));
    }
}
