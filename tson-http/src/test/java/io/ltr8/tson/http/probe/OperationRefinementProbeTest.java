package io.ltr8.tson.http.probe;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonBindings;
import io.ltr8.tson.http.api.HttpVerb;
import io.ltr8.tson.http.api.OrdersExamples;
import io.ltr8.tson.http.api.Route;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.http.probe.operationrefinement.Binding;
import io.ltr8.tson.http.probe.operationrefinement.Document;
import io.ltr8.tson.http.probe.operationrefinement.Endpoint;
import io.ltr8.tson.http.probe.operationrefinement.Exchange;
import io.ltr8.tson.http.probe.operationrefinement.Http;
import io.ltr8.tson.http.probe.operationrefinement.Operation;
import io.ltr8.tson.http.probe.operationrefinement.Protocol;
import io.ltr8.tson.http.probe.operationrefinement.Resource;
import io.ltr8.tson.http.probe.operationrefinement.Wrapper;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a description can declare an endpoint shape of its own -- {@code paged_get => operation & { … }} -- and
 * write its endpoints {@code !paged_get { … }}, so what every paged list shares is fixed once. Measured against a
 * minimal layer written here, mirroring {@code service.endpoint}'s pattern, and once against {@code service.tn}
 * itself; {@code proposal/operation-refinement.md} is the proposal it measures. Every test pins Revision 40's outcome,
 * and one failing after a library change means the answer has moved.
 *
 * <p>The answers measured: an empty composition, a composition or a refinement pinning {@code status} or {@code
 * request} all load; {@code status: = 200} unmarked is refused as moving a default to required, a list cannot be
 * pinned at all ([TSON-SCHEMA] §5.2), and a refinement adding a field is refused. A template cannot fix an operation's
 * response: a parameter is a type, so {@code response: T} restates the field's type and is refused where it is
 * applied, and {@code response?: = T} is refused at the declaration. The description's own subtype is admitted, and
 * binds to the base's class once it is bound to it, wherever the slot is: at a record the description declares, at
 * the layer's record in the description's own value entry, and at the layer's record in a data document importing
 * the description. The real layer admits it the same way through {@code TsonApiSchema}, and {@code describedBy}
 * reads the endpoint. A subtype bound to nothing is no member of the union the base's sealed interface closes. What
 * paged lists share is a response shape, and a template over the response record says it too.
 */
class OperationRefinementProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/refinement/layer-1.tn";
    static final String ID = "https://schemas.example.com/2026/40/refinement/s-1.tn";

    /**
     * The layer: {@code service.tn}'s endpoint pattern at the root. A base, {@code endpoint}, bound to a sealed
     * interface so it has no bare form; {@code operation}, {@code binding} and {@code document} composing it; a
     * resource holding {@code {http_verb => endpoint}}; and a projection whose resources are a namespace.
     */
    static final String LAYER = "!!id:\"" + LAYER_ID + "\"\n!!import:\"" + META + "\"\n!!import:\"" + NET + "\"\n"
            + """
            !schema {
              signature     => { request?: meta.type_ref  response?: meta.type_ref  errors?: [meta.type_ref] }
              exchange      => signature & {}
              exchange_name => meta.identifier
              exchanges     => !meta.namespace { key_type: exchange_name  value_type: exchange }
              protocol      => { exchanges: exchanges }
              path_template => !unicode.identifier_type {
                start: NONE  start_add: "/"  continue_add: "/{}+%-._~!$&()*,;=:@"
                pattern: "/([^%{}]|%[0-9A-Fa-f]{2}|\\\\{[+]?[A-Za-z0-9_]+\\\\})*" }
              http_verb     => !enum [GET POST]
              endpoint      => { status?: integer ~ 200  media_types?: [media_type] }
              operation     => endpoint & signature
              binding       => endpoint & { exchange: meta.ref<exchange> }
              document      => endpoint & { request?: meta.type_ref }
              resource      => { endpoints: {http_verb => endpoint} }
              resources     => !meta.namespace { key_type: path_template  value_type: resource }
              http          => { resources: resources }
            }""";

    /** The paged shape every case declares unless it says otherwise: the status and the query record pinned. */
    static final String PAGED = "  paged_get => operation & { status?: = 200  request?: = order_query }";

    /** A description importing the layer and core, declaring the orders records, then {@code entries}. */
    static String schema(String... entries) {
        return "!!id:\"" + ID + "\"\n!!import:\"" + LAYER_ID + "\"\n!!import:\"" + CORE + "\"\n!schema {\n"
                + "  order       => { sku: text }\n"
                + "  order_query => { status?: text  page?: int32 ~ 1 }\n"
                + "  order_page  => { items: [order]  next_page?: int32 }\n"
                + String.join("\n", entries) + "\n}";
    }

    /** The projection {@code orders_http}, its one endpoint {@code GET /orders} written as {@code endpoint}. */
    static String http(String endpoint) {
        return "  orders_http => !http { resources: { \"/orders\" => { GET => " + endpoint + " } } }";
    }

    /** A record the description declares, holding an endpoint, and a value of it holding {@code endpoint}. */
    static String wrapper(String endpoint) {
        return "  wrapper => { e: endpoint }\n  w => !wrapper { e: " + endpoint + " }";
    }

    /** A data document importing the description: the projection, its one endpoint written as {@code endpoint}. */
    static String data(String endpoint) {
        return "!!import:\"" + ID + "\"\n!http { resources: { \"/orders\" => { endpoints: { GET => " + endpoint
                + " } } } }";
    }

    /** The layer's records bound, and {@code extra} beside them. */
    static Map<String, Class<?>> bound(Map<String, Class<?>> extra) {
        Map<String, Class<?>> bound = new LinkedHashMap<>();
        bound.put("exchange", Exchange.class);
        bound.put("protocol", Protocol.class);
        bound.put("endpoint", Endpoint.class);
        bound.put("operation", Operation.class);
        bound.put("binding", Binding.class);
        bound.put("document", Document.class);
        bound.put("resource", Resource.class);
        bound.put("http", Http.class);
        bound.putAll(extra);
        return bound;
    }

    static final Map<String, Class<?>> LAYER_BOUND = bound(Map.of());
    static final Map<String, Class<?>> PAGED_BOUND = bound(Map.of("paged_get", Operation.class));

    static Tson tson(String schema, Map<String, Class<?>> bound) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        documents.put(ID, schema);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(AtomContext.defaultContext().toBuilder()
                        .nameBinder(DataNameBinder.ofMap(bound)).build()));
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    /** The one diagnostic resolving {@code schema} under {@code bound} yields, asserted of {@code code}. */
    static String refusal(String schema, Map<String, Class<?>> bound, Diagnostic.Code code) {
        Diagnostic problem = only(tson(schema, bound).validateSchema(schema));
        assertEquals(code, problem.code(), problem::message);
        return problem.message();
    }

    /** The value the resolved description's entry {@code name} holds: bound, or a tree where nothing binds it. */
    static Object entry(Tson tson, String name) {
        return assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(ID).orElseThrow().schema().entries().get(name)).value();
    }

    // ── 1: which forms load ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A composition and a refinement of the layer's {@code operation} load in an importing description</b>, empty
     * or pinning {@code status} and {@code request}: a pin moves {@code status?: integer ~ 200} from DEFAULT to FIXED,
     * and {@code request} is a {@code meta.type_ref}, a reference atom, so it takes a value. Three spellings are
     * refused: {@code status: = 200} unmarked, which moves an injected field back to required (§5.7); a pin on
     * {@code media_types}, a list, which the grammar refuses at the {@code [} since a value modifier is a scalar token
     * (§5.2); and a refinement adding a field, which only {@code &} may do.
     */
    @Test
    void aCompositionOrARefinementOfOperationLoads() {
        for (String form : List.of("  paged_get => operation & {}", PAGED,
                "  paged_get => operation ^ { status?: = 200  request?: = order_query }")) {
            String schema = schema(form);
            assertEquals(List.of(), tson(schema, LAYER_BOUND).validateSchema(schema), form);
        }
        assertEquals("'paged_get': tightening 'status' from defaulted to required is not a permitted state transition "
                        + "-- a refinement can only restrict, never expand (§5.7)",
                refusal(schema("  paged_get => operation & { status: = 200 }"), LAYER_BOUND,
                        Diagnostic.Code.SCHEMA_ERROR));
        assertEquals("expected a scalar token, the void sentinel '_', or '?' after '=', found '['",
                refusal(schema("  paged_get => operation & { media_types?: = [\"application/tson\"] }"), LAYER_BOUND,
                        Diagnostic.Code.VALIDATION_ERROR));
        assertEquals("'paged_get': refinement body field 'page_size' names no inherited field -- a refinement copies "
                        + "its source's whole field set and admits no new fields; composition (`&`) is what adds one "
                        + "(§5.7)",
                refusal(schema("  paged_get => operation ^ { page_size?: int32 }"), LAYER_BOUND,
                        Diagnostic.Code.SCHEMA_ERROR));
    }

    /**
     * <b>A template cannot fix an operation's response.</b> A parameter is a type, and {@code response} holds a type
     * <em>reference</em> as its value. {@code paged => <T> operation & { response: T }} loads, since nothing is
     * judged until it is applied, but {@code response: T} restates the field's type, so {@code paged_orders =>
     * paged<order>} is refused: {@code order} is no {@code meta.type_ref}. Writing the parameter as the value, {@code
     * response?: = T}, is refused at the declaration (§5.10). A tag carries no arguments, so {@code !paged<order>} is
     * refused where it is written.
     */
    @Test
    void aTemplateCannotFixAnOperationsResponse() {
        String open = "  paged => <T> operation & { response: T }";
        assertEquals(List.of(), tson(schema(open), LAYER_BOUND).validateSchema(schema(open)));
        assertEquals("'paged_orders': not a valid refinement of 'operation' (§5.7): field 'response' is typed 'order', "
                        + "which is not 'meta.type_ref' or IS-A it",
                refusal(schema(open, "  paged_orders => paged<order>"), Map.of(), Diagnostic.Code.SCHEMA_ERROR));
        assertEquals("parameter 'T' stands at the value of field 'response' (meta.record_field.value) -- a parameter is "
                        + "a type, and stands only at a field's type or as an argument of an application there or in "
                        + "a parent, so a field's value is never one (§5.10). State the value where the application "
                        + "is declared: 'name => template<argument> & { response?: = value }'",
                refusal(schema("  paged => <T> operation & { response?: = T }"), Map.of(),
                        Diagnostic.Code.SCHEMA_ERROR));
        assertEquals("'!paged<...>' applies type arguments, which are schema syntax and not available in a data value "
                        + "(§3.2): a data type-ref is a bare name. Name the application in the schema ('my_type => "
                        + "paged<...>') and write '!my_type' here",
                refusal(schema(open, http("!paged<order> { request: order_query }")), PAGED_BOUND,
                        Diagnostic.Code.VALIDATION_ERROR));
    }

    // ── 2 and 4: admission, and what the pins buy, at a slot the description declares ───────────────────────────

    /**
     * <b>At a slot of a record the description declares, its own subtype is admitted, pinned and bound.</b> {@code
     * wrapper => { e: endpoint }} beside {@code paged_get}: {@code !paged_get { response: order_page }} at {@code e}
     * is admitted as IS-A {@code endpoint} through {@code operation}, across the import (§7.2), and arrives with
     * {@code status} 200 and {@code request} {@code order_query} filled from the pins. Unbound it is a tree tagged
     * {@code paged_get}; with {@code paged_get} bound to the base's {@code Operation} class it is an {@code
     * Operation}. {@code status: 201} contradicting the pin is refused, naming the subtype.
     */
    @Test
    void theDescriptionsSubtypeIsAdmittedAtItsOwnRecordsSlot() {
        String schema = schema(PAGED, wrapper("!paged_get { response: order_page }"));
        Tson unbound = tson(schema, Map.of());
        assertEquals(List.of(), unbound.validateSchema(schema));
        TsonValue e = assertInstanceOf(TsonValue.class, entry(unbound, "w")).get("e");
        assertEquals(Optional.of("paged_get"), e.typeRef());
        assertEquals(200, e.get("status").asInt().orElseThrow());
        assertEquals(List.of("response", "status", "request"), List.copyOf(e.fields().keySet()));

        Tson bound = tson(schema, bound(Map.of("paged_get", Operation.class, "wrapper", Wrapper.class)));
        assertEquals(List.of(), bound.validateSchema(schema));
        Operation op = assertInstanceOf(Operation.class, assertInstanceOf(Wrapper.class, entry(bound, "w")).e());
        assertEquals(200, op.status());
        assertEquals("order_query", op.request().orElseThrow().name());
        assertEquals("order_page", op.response().orElseThrow().name());

        assertEquals("'w': the body is no value of the type 'wrapper' -- at /e/status: 'status' is fixed on "
                        + "'paged_get' and cannot be given another value -- the schema declares it with '=' (fixed); "
                        + "for a default the data may override, use '~'",
                refusal(schema(PAGED, wrapper("!paged_get { status: 201 }")), Map.of(), Diagnostic.Code.SCHEMA_ERROR));
    }

    // ── 2 and 3: admission and binding at a slot the layer declares ─────────────────────────────────────────────

    /**
     * <b>At a slot of a record the layer declares, read in the description's own value entry, the description's
     * subtype is admitted</b>, as [TSON-SCHEMA] §3.3.4 computes {@code subtypes} over the namespace of the schema
     * being resolved and §7.2 admits by the tag's chain, and as a data document importing the description admits it.
     * Unbound, the endpoint is a tree tagged {@code paged_get}, its {@code status} and {@code request} filled from the
     * pins. The layer bound and {@code paged_get} bound to nothing: no member of the union the sealed interface
     * closes, as the object reader refuses it in a data document. {@code paged_get} bound to {@code Operation} too: an
     * {@code Operation} with the pinned status and request. A subtype one level down ({@code endpoint & { … }}) is
     * admitted alike, and refused as a {@code BIND_MISMATCH} under {@code Operation}, whose {@code response} and
     * {@code errors} no field of it fills. An untagged endpoint is refused naming the layer's subtypes only, where the
     * same refusal in a data document names the description's beside them. {@code UPSTREAM.md}'s entry "An importer's
     * subtype is left out of an untagged refusal at an imported record's slot" stages that difference.
     */
    @Test
    void theDescriptionsSubtypeIsAdmittedAtTheLayersSlot() {
        String schema = schema(PAGED, http("!paged_get { response: order_page }"));
        Tson unbound = tson(schema, Map.of());
        assertEquals(List.of(), unbound.validateSchema(schema));
        TsonValue tree = assertInstanceOf(TsonValue.class, entry(unbound, "orders_http"))
                .at("/resources/~1orders/endpoints/GET");
        assertEquals(Optional.of("paged_get"), tree.typeRef());
        assertEquals(200, tree.get("status").asInt().orElseThrow());
        assertEquals(List.of("response", "status", "request"), List.copyOf(tree.fields().keySet()));

        String prefix = "'orders_http': the body is no value of the type 'http' -- at /resources/~1orders/endpoints/GET: ";
        assertEquals(prefix + "'paged_get' is not a member of the union 'endpoint' binds against [Operation, Binding, "
                + "Document]", refusal(schema, LAYER_BOUND, Diagnostic.Code.SCHEMA_ERROR));

        Tson bound = tson(schema, PAGED_BOUND);
        assertEquals(List.of(), bound.validateSchema(schema));
        Operation op = assertInstanceOf(Operation.class, assertInstanceOf(Http.class, entry(bound, "orders_http"))
                .resources().get("/orders").endpoints().get("GET"));
        assertEquals(200, op.status());
        assertEquals("order_query", op.request().orElseThrow().name());
        assertEquals("order_page", op.response().orElseThrow().name());

        String oneLevel = schema("  paged_get => endpoint & { request?: meta.type_ref }",
                http("!paged_get { request: order_query }"));
        Tson oneUnbound = tson(oneLevel, Map.of());
        assertEquals(List.of(), oneUnbound.validateSchema(oneLevel));
        assertEquals(Optional.of("paged_get"), assertInstanceOf(TsonValue.class, entry(oneUnbound, "orders_http"))
                .at("/resources/~1orders/endpoints/GET").typeRef());
        Diagnostic mismatch = only(tson(oneLevel, PAGED_BOUND).validateSchema(oneLevel));
        assertEquals(Diagnostic.Code.BIND_MISMATCH, mismatch.code(), mismatch::message);
        assertTrue(mismatch.message().startsWith("'orders_http': 'paged_get' and " + Operation.class.getName()
                + " do not agree: component 'response' is filled by no field"), mismatch::message);

        assertEquals(prefix + "'endpoint' has no data of its own to bind -- provide an explicit type annotation "
                        + "(!typeName) naming one of its subtypes [operation, binding, document]",
                refusal(schema(PAGED, http("{ response: order_page }")), PAGED_BOUND, Diagnostic.Code.SCHEMA_ERROR));
    }

    /**
     * <b>A data document importing the description admits the subtype at the layer's slot, and binds it to the base's
     * class.</b> The tree reader delivers the endpoint tagged {@code paged_get}, its {@code status} and {@code
     * request} filled from the pins. The object reader refuses it as no member of the union while {@code paged_get}
     * binds nothing, and delivers an {@code Operation} once it is bound to {@code Operation}. An untagged endpoint is
     * refused naming the description's subtype beside the layer's, and a value contradicting a pin is refused as
     * {@code FIELD_FIXED} by both readers.
     */
    @Test
    void aDataDocumentAdmitsAndBindsTheSubtypeAtTheLayersSlot() {
        String schema = schema(PAGED);
        String paged = data("!paged_get { response: order_page }");
        Tson unbound = tson(schema, LAYER_BOUND);
        assertEquals(List.of(), unbound.validateSchema(schema));
        var problems = DiagnosticsReceiver.collecting();
        TsonValue endpoint = unbound.treeReader().withDiagnostics(problems).read(paged)
                .at("/resources/~1orders/endpoints/GET");
        assertEquals(List.of(), problems.diagnostics());
        assertEquals(Optional.of("paged_get"), endpoint.typeRef());
        assertEquals(200, endpoint.get("status").asInt().orElseThrow());
        assertEquals(List.of("response", "status", "request"), List.copyOf(endpoint.fields().keySet()));

        ReadException notMember = assertThrows(ReadException.class, () -> unbound.objectReader().read(paged, Http.class));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, notMember.diagnostic().code());
        assertEquals("'paged_get' is not a member of the union 'endpoint' binds against [Operation, Binding, Document]",
                notMember.diagnostic().message());

        Tson bound = tson(schema, PAGED_BOUND);
        assertEquals(List.of(), bound.validateSchema(schema));
        Operation op = assertInstanceOf(Operation.class,
                bound.objectReader().read(paged, Http.class).resources().get("/orders").endpoints().get("GET"));
        assertEquals(200, op.status());
        assertEquals("order_query", op.request().orElseThrow().name());

        ReadException untagged = assertThrows(ReadException.class,
                () -> bound.objectReader().read(data("{ response: order_page }"), Http.class));
        assertEquals("'endpoint' has no data of its own to bind -- provide an explicit type annotation (!typeName) "
                + "naming one of its subtypes [operation, binding, document, paged_get]", untagged.diagnostic().message());

        String fixed = "'request' is fixed on 'paged_get' and cannot be given another value -- the schema declares it "
                + "with '=' (fixed); for a default the data may override, use '~'";
        String contradicting = data("!paged_get { request: order  response: order_page }");
        var refused = DiagnosticsReceiver.collecting();
        bound.treeReader().withDiagnostics(refused).read(contradicting);
        Diagnostic pin = only(refused.diagnostics());
        assertEquals(Diagnostic.Code.FIELD_FIXED, pin.code());
        assertEquals(fixed, pin.message());
        assertEquals(fixed, assertThrows(ReadException.class,
                () -> bound.objectReader().read(contradicting, Http.class)).diagnostic().message());
    }

    /**
     * <b>Only a subtype with the base's field set binds to the base's class.</b> A composition adding a field, {@code
     * operation & { page_size?: int32 }}, is admitted by the tree reader and refused by the object reader under
     * {@code Operation}, which has no component for it: binding a description's subtype to the layer's class works
     * for a refinement or a field-free composition only.
     */
    @Test
    void aSubtypeAddingAFieldDoesNotBindToTheBasesClass() {
        String schema = schema("  paged_get => operation & { page_size?: int32 }");
        Tson tson = tson(schema, PAGED_BOUND);
        assertEquals(List.of(), tson.validateSchema(schema));
        String doc = data("!paged_get { response: order_page  page_size: 50 }");
        var problems = DiagnosticsReceiver.collecting();
        assertEquals(Optional.of("paged_get"), tson.treeReader().withDiagnostics(problems).read(doc)
                .at("/resources/~1orders/endpoints/GET").typeRef());
        assertEquals(List.of(), problems.diagnostics());
        ReadException mismatch = assertThrows(ReadException.class, () -> tson.objectReader().read(doc, Http.class));
        assertEquals(Diagnostic.Code.BIND_MISMATCH, mismatch.diagnostic().code());
        assertTrue(mismatch.diagnostic().message().startsWith("'paged_get' and " + Operation.class.getName()
                + " do not agree: no component for field 'page_size'"), mismatch.diagnostic()::message);
    }

    // ── what paged lists share ──────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>What paged lists share is a response shape, and a template over the response record says it today</b>, with
     * no change to the layer: {@code page => <T> { items: [T]  next_page?: int32 }} and {@code order_list =>
     * page<order>}, named by a plain {@code !operation} at the layer's slot, load and bind in the description's own
     * value entry.
     */
    @Test
    void aTemplateOverTheResponseRecordSaysWhatPagedListsShare() {
        String schema = schema("  page => <T> { items: [T]  next_page?: int32 }", "  order_list => page<order>",
                http("!operation { request: order_query  response: order_list }"));
        Tson tson = tson(schema, LAYER_BOUND);
        assertEquals(List.of(), tson.validateSchema(schema));
        Operation op = assertInstanceOf(Operation.class,
                assertInstanceOf(Http.class, entry(tson, "orders_http")).resources().get("/orders").endpoints()
                        .get("GET"));
        assertEquals("order_list", op.response().orElseThrow().name());
        assertEquals(200, op.status());
    }

    // ── 5: the real layer ───────────────────────────────────────────────────────────────────────────────────────

    static final String PAGED_ID = OrdersExamples.id("orders-api-paged-1.tn");

    /**
     * {@code orders-api-inline-1.tn} under a new identity, declaring {@code paged_get => service.operation & {
     * status?: = 200 }} and writing {@code GET /orders/{id}/items} as {@code !paged_get}.
     */
    static String pagedDescription() {
        String inline = OrdersExamples.source("orders-api-inline-1.tn");
        String paged = inline.replace(OrdersExamples.id("orders-api-inline-1.tn"), PAGED_ID)
                .replace("!schema {\n", "!schema {\n  paged_get => service.operation & { status?: = 200 }\n")
                .replace("GET => !service.operation { request: order_ref  response: order_page }",
                        "GET => !paged_get { request: order_ref  response: order_page }");
        assertFalse(paged.equals(inline));
        assertTrue(paged.contains("!paged_get {"));
        return paged;
    }

    static Tson real(String paged, Map<String, Class<?>> bindings) {
        Map<String, String> library = OrdersExamples.library();
        library.put(PAGED_ID, paged);
        ProcessorConfig config = ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library)));
        return Tson.of(bindings == null ? config : config.withDataBindContext(TsonBindings.of(bindings)));
    }

    /**
     * <b>Through {@code service.tn} and {@code TsonApiSchema} as they stand, a description's own operation loads once
     * it is bound.</b> Bound as a server binds by default, {@link TsonApiSchema#bindings()}: no member of the union
     * {@code service.endpoint} binds against, so the description never resolves and {@code describedBy} has nothing
     * to read. Unbound: admitted, a tree tagged {@code paged_get} with the pinned status. With {@code paged_get}
     * bound to {@code api.Operation} through {@link TsonApiSchema#bindingsWith}: the endpoint is an {@code
     * api.Operation}, and {@code describedBy} reads its route with status 200 and the request and response it names.
     */
    @Test
    void theRealLayerAdmitsADescriptionsOwnOperation() {
        String paged = pagedDescription();
        String path = "/orders/{id}/items";
        String at = "'orders_http': the body is no value of the type 'service.http' -- at "
                + "/resources/~1orders~1{id}~1items/endpoints/GET: ";

        Tson layerOnly = real(paged, TsonApiSchema.bindings());
        Diagnostic union = only(layerOnly.validateSchema(paged));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, union.code());
        assertEquals(at + "'paged_get' is not a member of the union 'service.endpoint' binds against [Operation, "
                + "Binding, Document]", union.message());
        assertEquals("'" + PAGED_ID + "' is not resolved -- resolve the description before reading it",
                assertThrows(IllegalArgumentException.class, () -> TsonApiSchema.describedBy(layerOnly, PAGED_ID))
                        .getMessage());

        Tson unbound = real(paged, null);
        assertEquals(List.of(), unbound.validateSchema(paged));
        TsonValue tree = assertInstanceOf(TsonValue.class, assertInstanceOf(ValueEntry.class,
                unbound.schemaRegistry().get(PAGED_ID).orElseThrow().schema().entries().get("orders_http")).value())
                .at("/resources/~1orders~1{id}~1items/endpoints/GET");
        assertEquals(Optional.of("paged_get"), tree.typeRef());
        assertEquals(200, tree.get("status").asInt().orElseThrow());

        Tson withPaged = real(paged, TsonApiSchema.bindingsWith(
                Map.of("paged_get", io.ltr8.tson.http.api.Operation.class)));
        assertEquals(List.of(), withPaged.validateSchema(paged));
        io.ltr8.tson.http.api.Http http = assertInstanceOf(io.ltr8.tson.http.api.Http.class,
                assertInstanceOf(ValueEntry.class, withPaged.schemaRegistry().get(PAGED_ID).orElseThrow().schema()
                        .entries().get("orders_http")).value());
        io.ltr8.tson.http.api.Operation op = assertInstanceOf(io.ltr8.tson.http.api.Operation.class,
                http.resources().entries().get(path).endpoints().get("GET"));
        assertEquals(200, op.status());
        Route route = TsonApiSchema.describedBy(withPaged, PAGED_ID).route(HttpVerb.GET, path).orElseThrow();
        assertEquals(200, route.status());
        assertEquals("order_ref", route.request().orElseThrow().name());
        assertEquals("order_page", route.response().orElseThrow().name());
    }
}
