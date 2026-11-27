package io.ltr8.tson.http.probe;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.probe.schemasplit.Binding;
import io.ltr8.tson.http.probe.schemasplit.Document;
import io.ltr8.tson.http.probe.schemasplit.Endpoint;
import io.ltr8.tson.http.probe.schemasplit.Exchange;
import io.ltr8.tson.http.probe.schemasplit.Exemption;
import io.ltr8.tson.http.probe.schemasplit.Http;
import io.ltr8.tson.http.probe.schemasplit.HttpAuth;
import io.ltr8.tson.http.probe.schemasplit.Operation;
import io.ltr8.tson.http.probe.schemasplit.Protocol;
import io.ltr8.tson.http.probe.schemasplit.Resource;
import io.ltr8.tson.http.probe.schemasplit.RpcService;
import io.ltr8.tson.http.probe.schemasplit.SecurityScheme;
import io.ltr8.tson.http.probe.schemasplit.Signature;
import io.ltr8.tson.http.probe.schemasplit.Wrapped;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.tree.TsonAtom;
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
 * Whether {@code service.tn} could become two schemas: a protocol schema keeping the root map {@code service}, and an
 * http schema importing it and declaring the projection. Measured against a cut-down pair written here, not against
 * {@code service.tn}; {@code BACKLOG.md}'s "The module split" holds the question. Every test pins Revision 40's
 * outcome, and one failing after a library change means the answer has moved.
 *
 * <p>The http schema's root map is {@link #ROOT}, a placeholder: what the real one is called is the project owner's
 * to choose, and the probe also measures the http schema adding its entries to the protocol schema's own {@code
 * service} map, which [TSON-SCHEMA] §2.2.3 admits since a nested schema map is open.
 *
 * <p>The answers measured: both schemas resolve, the http one composing, holding and referencing the protocol one's
 * records. A description importing both declares a protocol and a projection of it in one document; every reference
 * across the layers is judged at load and delivered its referent, in the value entry and in a data document importing
 * the description. A description importing the http schema alone sees the protocol schema through the closure. Split
 * over two description documents, the projection's binding walks {@code orders.exchanges.get_order} across the import
 * of the first and is judged and delivered there too, and a second projection beside it resolves, as does a schema
 * importing both. A data document importing the protocol description and the http schema separately throws, the gap
 * {@code AffordanceProbeTest} pins. The exchange facts read through, the positional form holds, and one bind context
 * binds both layers. The http schema may add its entries to {@code service}, so the split need rename nothing; a
 * member both declare collides, and so may any importer's member, since the map is open to every schema.
 */
class SchemaSplitProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String BASE = "https://schemas.example.com/2026/40/schemasplit/";
    static final String PROTOCOL_ID = BASE + "protocol-1.tn";
    static final String HTTP_ID = BASE + "http-1.tn";
    static final String RPC_ID = BASE + "rpc-1.tn";
    static final String DOMAIN_ID = BASE + "domain-1.tn";
    static final String DESCRIPTION_ID = BASE + "orders-api-1.tn";
    static final String PROTOCOL_DESCRIPTION_ID = BASE + "orders-protocol-1.tn";
    static final String HTTP_DESCRIPTION_ID = BASE + "orders-http-1.tn";
    static final String RPC_DESCRIPTION_ID = BASE + "orders-rpc-1.tn";
    static final String SERVER_ID = BASE + "orders-server-1.tn";
    static final String HTTP_ONLY_ID = BASE + "orders-http-only-1.tn";

    /**
     * The http schema's root map. A placeholder, not a proposal: the real name is the project owner's to choose, and
     * this one is spelt so that nobody mistakes it for a decision.
     */
    static final String ROOT = "tbd_http";

    static String header(String id, String... imports) {
        StringBuilder text = new StringBuilder("!!id:\"" + id + "\"\n");
        for (String one : imports) {
            text.append("!!import:\"").append(one).append("\"\n");
        }
        return text.toString();
    }

    /** The protocol schema: {@code service.tn}'s protocol side, cut down, under the root map {@code service}. */
    static final String PROTOCOL_LAYER = header(PROTOCOL_ID, META) + """
            !schema {
              service => !schema {
                signature     => { request?: meta.type_ref  response?: meta.type_ref  errors?: [meta.type_ref] }
                exchange      => service.signature & {}
                exchange_name => meta.identifier
                exchanges     => !meta.namespace { key_type: service.exchange_name  value_type: service.exchange }
                protocol      => { exchanges: service.exchanges }
                summary       => @meta.annotation text
                safe          => @meta.annotation void
                idempotent    => @meta.annotation void
                destructive   => @meta.annotation void
              }
            }""";

    /**
     * The http schema's entries under {@link #ROOT}: {@code service.tn}'s projection side, cut down. It reaches the
     * protocol schema at four points: {@code operation} composes {@code service.signature}, {@code wrapped} holds one
     * as a field, {@code binding} and {@code exemption} name a {@code service.exchange}, and {@code http.binds} names a
     * {@code service.protocol}. The abstract {@code endpoint} and its subtypes are all declared here.
     */
    static final String HTTP_ENTRIES = """
                tbd_http => !schema {
                  http_verb       => !enum [GET POST PUT DELETE]
                  status_code     => !integer ^ { min: 100  max: 599 }
                  path_template   => !unicode.text_type { pattern: "/[A-Za-z0-9_/{}+-]*" }
                  endpoint        => abstract {
                    query?:       [meta.identifier]
                    status?:      tbd_http.status_code ~ 200
                    media_types?: [media_type]
                    security?:    [meta.ref<tbd_http.security_scheme>]
                  }
                  operation       => tbd_http.endpoint & service.signature
                  binding         => tbd_http.endpoint & { exchange: meta.ref<service.exchange> }
                  document        => tbd_http.endpoint & { request?: meta.type_ref }
                  wrapped         => tbd_http.endpoint & { signature: service.signature }
                  resource        => { endpoints: {tbd_http.http_verb => tbd_http.endpoint} }
                  exemption       => { exchange: meta.ref<service.exchange>  reason: text }
                  resources       => {tbd_http.path_template => tbd_http.resource}
                  http            => {
                    binds?:     [meta.ref<service.protocol>]
                    not_bound?: [tbd_http.exemption]
                    security?:  [meta.ref<tbd_http.security_scheme>]
                    resources:  tbd_http.resources
                  }
                  security_scheme => abstract { }
                  http_auth       => tbd_http.security_scheme & { scheme: text }
                }""";

    /** The http schema with its entries under the root map {@code root}, importing {@code imports}. */
    static String httpLayer(String root, String... imports) {
        return header(HTTP_ID, imports) + "!schema {\n" + HTTP_ENTRIES.replace(ROOT, root) + "\n}";
    }

    static final String HTTP_LAYER = httpLayer(ROOT, META, NET, PROTOCOL_ID);

    /** A second projection layer beside the http one, standing in for gRPC. */
    static final String RPC_LAYER = header(RPC_ID, META, PROTOCOL_ID) + """
            !schema {
              tbd_rpc => !schema {
                service_def => { protocol: meta.ref<service.protocol>  methods: [meta.ref<service.exchange>] }
              }
            }""";

    static final String DOMAIN = header(DOMAIN_ID, CORE) + """
            !schema {
              order_ref       => { id: text }
              new_order       => { sku: text }
              order           => { id: text  sku: text }
              order_not_found => { status: integer  id: text }
              health          => { up: boolean }
            }""";

    static final String PROTOCOL_ENTRY = """
              orders => !service.protocol {
                exchanges: {
                  @service.safe @service.summary:"Fetch an order."
                  get_order    => { request: order_ref  response: order  errors: [order_not_found] }
                  @service.idempotent @service.destructive
                  cancel_order => { request: order_ref }
                  place_order  => { request: new_order  response: order }
                }
              }""";

    /**
     * The projection: a binding per exchange but one, which is exempted; an inline operation whose verb key carries
     * the protocol schema's {@code @service.safe}; an endpoint holding a signature; a document; a security scheme.
     */
    static final String PROJECTION_ENTRY = """
              bearer => !tbd_http.http_auth { scheme: Bearer }
              orders_http => !tbd_http.http {
                binds:     [orders]
                not_bound: [{ exchange: orders.exchanges.cancel_order  reason: "served by the back office" }]
                security:  [bearer]
                resources: {
                  "/orders"         => { @service.summary:"Place an order."
                                         POST => !tbd_http.binding { exchange: orders.exchanges.place_order
                                                                     status: 201 } }
                  "/orders/{id}"    => { GET => !tbd_http.binding { exchange: orders.exchanges.get_order } }
                  "/health"         => { @service.safe
                                         GET => !tbd_http.operation { response: health  security: [] } }
                  "/health/wrapped" => { GET => !tbd_http.wrapped { signature: { response: health } } }
                  "/"               => { GET => !tbd_http.document { media_types: ["text/html"] } }
                }
              }""";

    static String schema(String header, String... entries) {
        return header + "!schema {\n" + String.join("\n", entries) + "\n}";
    }

    /** The one-document description: both layers and the domain imported, a protocol and a projection of it. */
    static final String DESCRIPTION = schema(header(DESCRIPTION_ID, PROTOCOL_ID, HTTP_ID, DOMAIN_ID),
            PROTOCOL_ENTRY, PROJECTION_ENTRY);

    /** The two-document description's first half: the protocol entry alone, under the protocol schema. */
    static final String PROTOCOL_DESCRIPTION = schema(header(PROTOCOL_DESCRIPTION_ID, PROTOCOL_ID, DOMAIN_ID),
            PROTOCOL_ENTRY);

    /** Its second half: the projection, importing the first half and the http schema. */
    static final String HTTP_DESCRIPTION = schema(header(HTTP_DESCRIPTION_ID, PROTOCOL_DESCRIPTION_ID, HTTP_ID),
            PROJECTION_ENTRY);

    /** A gRPC-shaped projection of the same first half, beside the http one. */
    static final String RPC_DESCRIPTION = schema(header(RPC_DESCRIPTION_ID, PROTOCOL_DESCRIPTION_ID, RPC_ID),
            "  orders_rpc => !tbd_rpc.service_def { protocol: orders  methods: [orders.exchanges.get_order"
                    + "  orders.exchanges.place_order] }");

    /** One schema importing both projections, each over the protocol description: a diamond. */
    static final String SERVER = schema(header(SERVER_ID, HTTP_DESCRIPTION_ID, RPC_DESCRIPTION_ID),
            "  pick => { exchange: meta.ref<service.exchange> }",
            "  picked => !pick { exchange: orders.exchanges.get_order }");

    /** A description importing the http schema and the domain, and not the protocol schema. */
    static final String HTTP_ONLY = schema(header(HTTP_ONLY_ID, HTTP_ID, DOMAIN_ID), PROTOCOL_ENTRY, PROJECTION_ENTRY);

    /** The name binder's map, keyed by qualified name as {@code TsonServiceSchema.bindings()} keys {@code service.tn}'s. */
    static Map<String, Class<?>> bindings(String root) {
        Map<String, Class<?>> bound = new LinkedHashMap<>();
        bound.put("service.signature", Signature.class);
        bound.put("service.exchange", Exchange.class);
        bound.put("service.protocol", Protocol.class);
        bound.put(root + ".endpoint", Endpoint.class);
        bound.put(root + ".operation", Operation.class);
        bound.put(root + ".binding", Binding.class);
        bound.put(root + ".document", Document.class);
        bound.put(root + ".wrapped", Wrapped.class);
        bound.put(root + ".resource", Resource.class);
        bound.put(root + ".exemption", Exemption.class);
        bound.put(root + ".http", Http.class);
        bound.put(root + ".security_scheme", SecurityScheme.class);
        bound.put(root + ".http_auth", HttpAuth.class);
        bound.put("tbd_rpc.service_def", RpcService.class);
        return bound;
    }

    /** Every document of the probe, keyed by identity, with the http schema under {@link #ROOT}. */
    static Map<String, String> documents() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(PROTOCOL_ID, PROTOCOL_LAYER);
        documents.put(HTTP_ID, HTTP_LAYER);
        documents.put(RPC_ID, RPC_LAYER);
        documents.put(DOMAIN_ID, DOMAIN);
        documents.put(DESCRIPTION_ID, DESCRIPTION);
        documents.put(PROTOCOL_DESCRIPTION_ID, PROTOCOL_DESCRIPTION);
        documents.put(HTTP_DESCRIPTION_ID, HTTP_DESCRIPTION);
        documents.put(RPC_DESCRIPTION_ID, RPC_DESCRIPTION);
        documents.put(SERVER_ID, SERVER);
        documents.put(HTTP_ONLY_ID, HTTP_ONLY);
        return documents;
    }

    /**
     * {@link #documents()} with the http schema's entries under the root map {@code root}, and every description
     * spelling them so.
     */
    static Map<String, String> documents(String root) {
        Map<String, String> documents = documents();
        documents.replaceAll((id, text) -> id.equals(HTTP_ID) ? httpLayer(root, META, NET, PROTOCOL_ID)
                : text.replace(ROOT + ".", root + "."));
        return documents;
    }

    static Tson tson(Map<String, String> documents, Map<String, Class<?>> bound) {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(AtomContext.defaultContext().toBuilder()
                        .nameBinder(DataNameBinder.ofMap(bound)).build()));
    }

    static Tson tson() {
        return tson(documents(), bindings(ROOT));
    }

    /** {@link #documents()} with {@code id} holding {@code text}. */
    static Tson tsonWith(String id, String text) {
        Map<String, String> documents = documents();
        documents.put(id, text);
        return tson(documents, bindings(ROOT));
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    /** The one refusal resolving {@code schema} yields under {@code tson}, asserted a schema error. */
    static String refusal(Tson tson, String schema) {
        Diagnostic problem = only(tson.validateSchema(schema));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problem::message);
        return problem.message();
    }

    /** The one refusal resolving the one-document description with {@code from} replaced by {@code to} yields. */
    static String refusal(String from, String to) {
        String changed = DESCRIPTION.replace(from, to);
        return refusal(tsonWith(DESCRIPTION_ID, changed), changed);
    }

    /** The value the resolved schema {@code id}'s entry {@code name} holds, bound. */
    static Object entry(Tson tson, String id, String name) {
        return assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(id).orElseThrow().schema().entries().get(name)).value();
    }

    static String data(String id, String body) {
        return "!!import:\"" + id + "\"\n" + body;
    }

    static final String GET_ORDER = "!tbd_http.binding { exchange: orders.exchanges.get_order }";

    /**
     * The projection entry {@code orders_http} of the schema {@code id}, bound, with every reference across the
     * layers asserted delivered its referent.
     */
    static Http assertProjectionDelivered(Tson tson, String id) {
        Http http = assertInstanceOf(Http.class, entry(tson, id, "orders_http"));
        ValueRef<Protocol> bound = http.binds().getFirst();
        assertEquals("orders", bound.name());
        assertTrue(bound.isResolved());
        assertEquals(List.of("get_order", "cancel_order", "place_order"),
                List.copyOf(assertInstanceOf(Protocol.class, bound.get()).exchanges().keySet()));

        Binding get = assertInstanceOf(Binding.class, http.resources().get("/orders/{id}").endpoints().get("GET"));
        assertEquals("orders.exchanges.get_order", get.exchange().name());
        Exchange reached = assertInstanceOf(Exchange.class, get.exchange().get());
        assertEquals("order_ref", reached.request().orElseThrow().name());
        assertEquals("order_not_found", reached.errors().getFirst().name());
        assertEquals(200, get.status());
        assertEquals(201, assertInstanceOf(Binding.class,
                http.resources().get("/orders").endpoints().get("POST")).status());

        Exemption exempt = http.notBound().getFirst();
        assertEquals("orders.exchanges.cancel_order", exempt.exchange().name());
        assertEquals(Optional.empty(), assertInstanceOf(Exchange.class, exempt.exchange().get()).response());
        assertEquals("Bearer", assertInstanceOf(HttpAuth.class, http.security().getFirst().get()).scheme());
        return http;
    }

    // ── 1: the two schemas resolve ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The protocol schema and an http schema importing it resolve clean</b>, the http one composing an imported
     * record ({@code tbd_http.operation => tbd_http.endpoint & service.signature}), holding one as a field, and
     * typing {@code meta.ref<service.exchange>} and {@code meta.ref<service.protocol>} slots over the imported records.
     * Its abstract {@code endpoint} and every subtype are its own, the composing one among them, and an untagged
     * endpoint in a description is refused naming all four by their qualified names.
     */
    @Test
    void theHttpSchemaImportingTheProtocolSchemaResolves() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(PROTOCOL_LAYER));
        assertEquals(List.of(), tson.validateSchema(HTTP_LAYER));
        assertEquals(List.of(), tson.validateSchema(DOMAIN));

        assertEquals("'orders_http': the body is no value of the type 'tbd_http.http' -- at /resources/~1orders~1{id}/"
                        + "endpoints/GET: 'tbd_http.endpoint' is abstract and has no direct instances, so a value here "
                        + "must name its type -- one of (tbd_http.operation | tbd_http.binding | tbd_http.document | "
                        + "tbd_http.wrapped)",
                refusal("GET => " + GET_ORDER, "GET => { exchange: orders.exchanges.get_order }"));
    }

    // ── 2: the one-document description ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A description importing both schemas and the domain resolves, and every reference across the layers is
     * delivered its referent.</b> {@code binds: [orders]} arrives as a resolved {@link ValueRef} holding the {@link
     * Protocol}; each binding's {@code orders.exchanges.…} walks the protocol's field and holds the {@link Exchange};
     * the exemption's reference likewise; and the projection's {@code security} holds the scheme. The protocol entry
     * binds as its class does today.
     */
    @Test
    void theOneDocumentDescriptionDeliversEveryReferenceAcrossTheLayers() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Protocol orders = assertInstanceOf(Protocol.class, entry(tson, DESCRIPTION_ID, "orders"));
        assertEquals("service.exchanges", orders.exchanges().type());
        assertProjectionDelivered(tson, DESCRIPTION_ID);
    }

    /**
     * <b>Each reference across the layers is judged at load.</b> A key the protocol does not hold, a reference
     * skipping the {@code exchanges} field, {@code binds} naming a domain type or a projection, a binding naming the
     * protocol itself, and an exemption naming a key the protocol does not hold are each refused, wording the wanted
     * kind by the protocol schema's qualified name and the found one by whichever schema declares it.
     */
    @Test
    void theOneDocumentDescriptionJudgesEachReferenceAcrossTheLayers() {
        String at = "'orders_http' at /resources/~1orders~1{id}/endpoints/GET/exchange ";
        assertEquals(at + "names 'orders.exchanges.nope', and 'orders.exchanges' has no key 'nope' -- it holds "
                + "get_order, cancel_order, place_order", refusal("orders.exchanges.get_order", "orders.exchanges.nope"));
        assertEquals(at + "names 'orders.get_order', and 'orders' is a 'service.protocol', which has no field "
                + "'get_order' -- it declares exchanges", refusal("orders.exchanges.get_order", "orders.get_order"));
        assertEquals(at + "wants a 'service.exchange', and 'orders' is an entry whose body is built by "
                + "'service.protocol', which is no 'service.exchange'",
                refusal("exchange: orders.exchanges.get_order", "exchange: orders"));
        assertEquals("'orders_http' at /binds/0 wants a 'service.protocol', and 'order' is an entry whose body is built "
                + "by 'meta.record', which is no 'service.protocol'", refusal("binds:     [orders]", "binds: [order]"));
        assertEquals("'orders_http' at /binds/0 wants a 'service.protocol', and 'orders_http' is an entry whose body is "
                + "built by 'tbd_http.http', which is no 'service.protocol'",
                refusal("binds:     [orders]", "binds: [orders_http]"));
        assertEquals("'orders_http' at /not_bound/0/exchange names 'orders.exchanges.refund', and 'orders.exchanges' "
                + "has no key 'refund' -- it holds get_order, cancel_order, place_order",
                refusal("orders.exchanges.cancel_order", "orders.exchanges.refund"));
    }

    /**
     * <b>A data document importing the one-document description reads a binding through every entry point</b>, the
     * http schema's type and the protocol's value reached through one import: {@code validate} reports nothing, the
     * tree reader holds a {@link ValueRef} delivered the {@link Exchange}, and the object reader binds a {@link
     * Binding} whose reference is delivered, on a {@code Tson} that resolved the description first and on one that did
     * not.
     */
    @Test
    void aDataDocumentImportingTheDescriptionReadsABindingThroughEveryEntryPoint() {
        String binding = data(DESCRIPTION_ID, GET_ORDER);
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        assertEquals(List.of(), tson.validate(binding));

        var problems = DiagnosticsReceiver.collecting();
        TsonAtom atom = assertInstanceOf(TsonAtom.class,
                tson.treeReader().withDiagnostics(problems).read(binding).at("/exchange"));
        assertEquals(List.of(), problems.diagnostics());
        ValueRef<?> reference = assertInstanceOf(ValueRef.class, atom.value());
        assertEquals("orders.exchanges.get_order", reference.name());
        assertEquals("order", assertInstanceOf(Exchange.class, reference.get()).response().orElseThrow().name());

        assertEquals("order_ref",
                tson.objectReader().read(binding, Binding.class).exchange().get().request().orElseThrow().name());
        assertEquals("order_ref",
                tson().objectReader().read(binding, Binding.class).exchange().get().request().orElseThrow().name());
    }

    // ── 3: a description importing only the http schema ─────────────────────────────────────────────────────────

    /**
     * <b>A description importing the http schema alone sees the protocol schema through the closure</b>: an import
     * crosses no level, and a schema's namespace is its own entries and its whole import closure's ([TSON-SCHEMA]
     * §3.3), so {@code !service.protocol}, the facts annotations and {@code service.exchange} resolve with no import of
     * the protocol schema, and the projection is delivered as in the full form. The closure is what is imported and
     * nothing else: the http schema importing the protocol schema alone does not see {@code net.tn}'s {@code
     * media_type}, which the protocol schema does not import, and is refused at each endpoint kind; it does see {@code
     * meta.tn}'s names, which the protocol schema imports.
     */
    @Test
    void aDescriptionImportingOnlyTheHttpSchemaSeesTheProtocolSchemaThroughItsClosure() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(HTTP_ONLY));
        Protocol orders = assertInstanceOf(Protocol.class, entry(tson, HTTP_ONLY_ID, "orders"));
        assertTrue(orders.exchanges().getAnnotations("get_order").has("service.safe"));
        assertProjectionDelivered(tson, HTTP_ONLY_ID);

        String noNet = httpLayer(ROOT, PROTOCOL_ID);
        List<Diagnostic> problems = tsonWith(HTTP_ID, noNet).validateSchema(noNet);
        assertEquals(List.of("/tbd_http.endpoint", "/tbd_http.operation", "/tbd_http.binding", "/tbd_http.document",
                "/tbd_http.wrapped"), problems.stream().map(p -> p.schemaPointer().orElseThrow()).toList());
        assertTrue(problems.stream().allMatch(p -> p.code() == Diagnostic.Code.SCHEMA_ERROR
                && p.message().equals("'[media_type]' element_type has an unresolved reference 'media_type'")),
                () -> "" + problems);

        String noMeta = httpLayer(ROOT, NET, PROTOCOL_ID);
        assertEquals(List.of(), tsonWith(HTTP_ID, noMeta).validateSchema(noMeta));
    }

    // ── 4: the two-document description ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>Split over two description documents, the projection's references walk the protocol across the import.</b>
     * The protocol entry is in one schema importing the protocol schema; the projection is in a second importing that
     * one and the http schema. Both resolve, warm or cold; the binding's {@code orders.exchanges.get_order}, a
     * reference through a record value's field in an imported schema's value entry, is delivered its {@link Exchange},
     * as are {@code binds} and the exemption; and a key the protocol does not hold is refused at load as in one
     * document. The open {@code UPSTREAM.md} entry "A data document importing two schemas throws at a reference through
     * a record value's field" does not reach a schema document.
     */
    @Test
    void theTwoDocumentDescriptionWalksAnExchangeAcrossTheImport() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(PROTOCOL_DESCRIPTION));
        assertEquals(List.of(), tson.validateSchema(HTTP_DESCRIPTION));
        assertProjectionDelivered(tson, HTTP_DESCRIPTION_ID);

        Tson cold = tson();
        assertEquals(List.of(), cold.validateSchema(HTTP_DESCRIPTION));
        assertProjectionDelivered(cold, HTTP_DESCRIPTION_ID);

        String unsound = HTTP_DESCRIPTION.replace("orders.exchanges.get_order", "orders.exchanges.nope");
        assertEquals("'orders_http' at /resources/~1orders~1{id}/endpoints/GET/exchange names 'orders.exchanges.nope', "
                + "and 'orders.exchanges' has no key 'nope' -- it holds get_order, cancel_order, place_order",
                refusal(tsonWith(HTTP_DESCRIPTION_ID, unsound), unsound));
    }

    /**
     * <b>A data document importing the projection's description alone reads a binding through every entry point</b>,
     * though the protocol entry it walks is one import further down: the document names one schema, so it is read
     * under the closure the registry links with a bind context.
     */
    @Test
    void aDataDocumentImportingTheProjectionsDescriptionReadsABinding() {
        String binding = data(HTTP_DESCRIPTION_ID, GET_ORDER);
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(HTTP_DESCRIPTION));
        assertEquals(List.of(), tson.validate(binding));

        var problems = DiagnosticsReceiver.collecting();
        TsonAtom atom = assertInstanceOf(TsonAtom.class,
                tson.treeReader().withDiagnostics(problems).read(binding).at("/exchange"));
        assertEquals(List.of(), problems.diagnostics());
        assertInstanceOf(Exchange.class, assertInstanceOf(ValueRef.class, atom.value()).get());
        assertEquals("order",
                tson.objectReader().read(binding, Binding.class).exchange().get().response().orElseThrow().name());
    }

    /**
     * <b>Open: a data document importing the protocol's description and the http schema separately throws at the
     * binding's reference</b>, from {@code validate}, the tree reader and the object reader alike. The same gap {@code
     * AffordanceProbeTest.anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField} pins, staged in {@code
     * UPSTREAM.md} as "A data document importing two schemas throws at a reference through a record value's field". The
     * split is what makes the shape ordinary: under one {@code service.tn} a protocol-only description already carries
     * the http types, and here a document wanting them beside the protocol imports two schemas, or a description that
     * imports both. The assertions flip when the read reports or succeeds.
     */
    @Test
    void aDataDocumentImportingTheProtocolDescriptionAndTheHttpSchemaThrows() {
        String twoImports = "!!import:\"" + PROTOCOL_DESCRIPTION_ID + "\"\n!!import:\"" + HTTP_ID + "\"\n" + GET_ORDER;
        String expected = "this reference names 'orders.exchanges.get_order', and 'orders.exchanges' is a field of a "
                + "record value, and this schema was linked with no bind context to read it through -- link it with the "
                + "context its values were bound under (TsonCompiledVocabularyRegistry.link)";
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(PROTOCOL_DESCRIPTION));
        assertEquals(expected, assertThrows(IllegalStateException.class, () -> tson.validate(twoImports)).getMessage());
        assertEquals(expected, assertThrows(IllegalStateException.class, () -> tson.treeReader().read(twoImports))
                .getMessage());
        assertEquals(expected, assertThrows(IllegalStateException.class,
                () -> tson.objectReader().read(twoImports, Binding.class)).getMessage());
    }

    /**
     * <b>A second projection beside the http one resolves, and so does a schema importing both.</b> A gRPC-shaped
     * layer importing the protocol schema, and a description importing the protocol's description and that layer,
     * resolve with its references delivered. A schema importing the http and the gRPC descriptions reaches the
     * protocol's description by two routes, which unify ([TSON-SCHEMA] §2.2.3), and its own reference into the
     * protocol is judged.
     */
    @Test
    void aSecondProjectionBesideTheHttpOneResolvesAndSoDoesASchemaImportingBoth() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(RPC_LAYER));
        assertEquals(List.of(), tson.validateSchema(RPC_DESCRIPTION));
        RpcService rpc = assertInstanceOf(RpcService.class, entry(tson, RPC_DESCRIPTION_ID, "orders_rpc"));
        assertInstanceOf(Protocol.class, rpc.protocol().get());
        assertEquals("new_order", rpc.methods().get(1).get().request().orElseThrow().name());

        assertEquals(List.of(), tson.validateSchema(SERVER));
        assertEquals(List.of(), tson().validateSchema(SERVER));
        String unsound = SERVER.replace("picked => !pick { exchange: orders.exchanges.get_order }",
                "picked => !pick { exchange: orders.exchanges.nope }");
        assertEquals("'picked' at /exchange names 'orders.exchanges.nope', and 'orders.exchanges' has no key 'nope' -- "
                + "it holds get_order, cancel_order, place_order", refusal(tsonWith(SERVER_ID, unsound), unsound));
    }

    // ── 5: the facts, and the positional form ───────────────────────────────────────────────────────────────────

    /**
     * <b>The exchange facts read through the split as they do today</b>, and the http side's use of them too. On an
     * exchange's key, {@code @service.safe} and {@code @service.summary} are reported under the qualified names
     * written, as are {@code @service.idempotent} and {@code @service.destructive}. Before a verb key in the projection,
     * {@code @service.summary} and {@code @service.safe} are the protocol schema's names used by the http side, and
     * read from the resource's key annotations. A misspelt fact, and one written under the http schema's root, are
     * refused naming it.
     */
    @Test
    void theFactsAnnotationsReadThroughTheSplit() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Protocol orders = assertInstanceOf(Protocol.class, entry(tson, DESCRIPTION_ID, "orders"));
        Annotations get = orders.exchanges().getAnnotations("get_order");
        assertTrue(get.has("service.safe"));
        assertEquals(Optional.of("Fetch an order."), get.value("service.summary", String.class));
        Annotations cancel = orders.exchanges().getAnnotations("cancel_order");
        assertTrue(cancel.has("service.idempotent"));
        assertTrue(cancel.has("service.destructive"));
        assertFalse(orders.exchanges().getAnnotations("place_order").has("service.safe"));

        Http http = assertInstanceOf(Http.class, entry(tson, DESCRIPTION_ID, "orders_http"));
        assertEquals(Optional.of("Place an order."), http.resources().get("/orders").endpoints().getAnnotations("POST")
                .value("service.summary", String.class));
        assertTrue(http.resources().get("/health").endpoints().getAnnotations("GET").has("service.safe"));

        assertEquals("'orders': the body is no value of the type 'service.protocol' -- at /exchanges/?: annotation "
                + "'@service.saf' names no type the governing schema declares (§6)",
                refusal("@service.safe @service.summary", "@service.saf @service.summary"));
        assertEquals("'orders': the body is no value of the type 'service.protocol' -- at /exchanges/?: annotation "
                + "'@tbd_http.safe' names no type the governing schema declares (§6)",
                refusal("@service.safe @service.summary", "@tbd_http.safe @service.summary"));
    }

    /**
     * <b>The positional form holds with the projection in another schema</b> ([TSON-SCHEMA] §5.6): {@code
     * !service.protocol { get_order => … }} and {@code !tbd_http.http { "/orders/{id}" => … }} each write the map
     * alone, and the binding's reference still walks the {@code exchanges} field the document did not write.
     */
    @Test
    void thePositionalFormHoldsWithTheProjectionInAnotherSchema() {
        String positional = schema(header(DESCRIPTION_ID, PROTOCOL_ID, HTTP_ID, DOMAIN_ID),
                "  orders => !service.protocol { @service.safe get_order => { request: order_ref  response: order } }",
                "  orders_http => !tbd_http.http { \"/orders/{id}\" => { GET => " + GET_ORDER + " } }");
        Tson tson = tsonWith(DESCRIPTION_ID, positional);
        assertEquals(List.of(), tson.validateSchema(positional));
        Protocol orders = assertInstanceOf(Protocol.class, entry(tson, DESCRIPTION_ID, "orders"));
        assertTrue(orders.exchanges().getAnnotations("get_order").has("service.safe"));
        Http http = assertInstanceOf(Http.class, entry(tson, DESCRIPTION_ID, "orders_http"));
        assertEquals(List.of(), http.binds());
        Binding get = assertInstanceOf(Binding.class, http.resources().get("/orders/{id}").endpoints().get("GET"));
        assertEquals("order", assertInstanceOf(Exchange.class, get.exchange().get()).response().orElseThrow().name());
    }

    // ── 6: binding ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>One bind context binds both layers</b>, its name binder keyed by qualified name across the two schemas. A
     * record of the http schema composing the protocol's ({@link Operation}) binds the imported fields as its own
     * components; one holding it as a field ({@link Wrapped}) binds that component to the class bound to the protocol
     * schema's {@code service.signature}; and a reference slot typed over an imported record binds to a {@code
     * ValueRef} of the protocol's class. Each reads in the description's value entry and in a data document.
     */
    @Test
    void oneBindContextBindsBothLayers() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Http http = assertInstanceOf(Http.class, entry(tson, DESCRIPTION_ID, "orders_http"));
        Operation health = assertInstanceOf(Operation.class, http.resources().get("/health").endpoints().get("GET"));
        assertEquals("health", health.response().orElseThrow().name());
        assertEquals(Optional.of(List.of()), health.security());
        Wrapped wrapped = assertInstanceOf(Wrapped.class,
                http.resources().get("/health/wrapped").endpoints().get("GET"));
        assertEquals("health", wrapped.signature().response().orElseThrow().name());
        assertEquals(List.of("text/html"), http.resources().get("/").endpoints().get("GET").mediaTypes());

        Wrapped read = tson.objectReader().read(data(DESCRIPTION_ID,
                "!tbd_http.wrapped { signature: { request: order_ref  errors: [order_not_found] }  status: 202 }"),
                Wrapped.class);
        assertEquals("order_ref", read.signature().request().orElseThrow().name());
        assertEquals("order_not_found", read.signature().errors().getFirst().name());
        assertEquals(202, read.status());
        Operation operation = tson.objectReader().read(data(DESCRIPTION_ID,
                "!tbd_http.operation { request: order_ref  response: order  query: [id] }"), Operation.class);
        assertEquals("order", operation.response().orElseThrow().name());
        assertEquals(List.of("id"), operation.query());
    }

    // ── 7: the root name, and what else the split meets ─────────────────────────────────────────────────────────

    /**
     * <b>The http schema may add its entries to the protocol schema's {@code service} map, so the split need rename
     * nothing.</b> A nested schema map is open: one path declared by several documents of a closure is one map, the
     * union of their members ([TSON-SCHEMA] §2.2.3). With the http schema declaring {@code service => !schema { http
     * => … binding => … }}, every name keeps today's spelling ({@code !service.http}, {@code !service.binding}) and the
     * bind map today's keys; the one-document description, a description importing the http schema alone, and the
     * two-document description each resolve and deliver every reference, and a data document reads a binding.
     */
    @Test
    void theHttpSchemaMayAddItsEntriesToTheOpenServiceMap() {
        Map<String, String> documents = documents("service");
        Tson tson = tson(documents, bindings("service"));
        assertEquals(List.of(), tson.validateSchema(documents.get(HTTP_ID)));
        for (String id : List.of(DESCRIPTION_ID, HTTP_ONLY_ID, HTTP_DESCRIPTION_ID)) {
            assertEquals(List.of(), tson.validateSchema(documents.get(id)), id);
            assertProjectionDelivered(tson, id);
        }
        Binding read = tson.objectReader().read(data(HTTP_DESCRIPTION_ID,
                "!service.binding { exchange: orders.exchanges.get_order }"), Binding.class);
        assertEquals("order", read.exchange().get().response().orElseThrow().name());
    }

    /**
     * <b>A member both schemas declare in the open map collides, naming the member; and so does a root entry of the
     * map's name that is no schema map.</b> The http schema declaring {@code service.exchange} is refused, and so is
     * one declaring a record {@code service}. Pinned: [TSON-SCHEMA] §2.2.3 says the diagnostic names both declaring
     * schemas, and a local declaration colliding with an import's names neither, its {@code schemaId} empty, where
     * two imports colliding name both ({@link #theHttpRootIsReservedInEveryDescriptionAndTheServiceMapIsOpenToAll}).
     * {@code UPSTREAM.md}'s entry "A local declaration colliding with an imported one names neither schema" stages it;
     * the assertions flip when the message names the schemas.
     */
    @Test
    void aMemberBothSchemasDeclareCollidesNamingNeitherSchema() {
        String member = header(HTTP_ID, META, PROTOCOL_ID) + "!schema {\n  service => !schema {\n"
                + "    exchange => { a: text }\n  }\n}";
        Diagnostic collision = only(tsonWith(HTTP_ID, member).validateSchema(member));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, collision.code());
        assertEquals("'service.exchange' collides with an entry of the same name brought in by !!import",
                collision.message());
        assertEquals("", collision.schemaId());

        String record = header(HTTP_ID, META, PROTOCOL_ID) + "!schema {\n  service => { a: text }\n}";
        assertEquals("'service' collides with an entry of the same name brought in by !!import",
                refusal(tsonWith(HTTP_ID, record), record));
    }

    /**
     * <b>The http schema's root, whatever it is called, is reserved in every description; {@code service} is reserved
     * only against what is no schema map.</b> A domain schema declaring a record {@code tbd_http} beside the http
     * schema collides naming both schemas, as one declaring a record {@code service} does beside the protocol schema.
     * A domain declaring {@code service => !schema { thing => … }} resolves and its {@code service.thing} reads: the
     * open map takes members from any schema, and only a member path two schemas declare collides, as a domain's
     * {@code service.binding} does beside the http schema adding its entries to {@code service}.
     */
    @Test
    void theHttpRootIsReservedInEveryDescriptionAndTheServiceMapIsOpenToAll() {
        String last = "health          => { up: boolean }";
        String rootRecord = DOMAIN.replace(last, last + "\n  tbd_http => { a: text }");
        assertEquals("'tbd_http' is declared by two different schemas reached through !!import ('schemas.example.com/"
                + "2026/40/schemasplit/http-1.tn' and 'schemas.example.com/2026/40/schemasplit/domain-1.tn') -- distinct "
                + "types cannot share one name in the flat namespace; import one of them, or a version of each that "
                + "agrees on where 'tbd_http' is declared", refusal(tsonWith(DOMAIN_ID, rootRecord), DESCRIPTION));
        String serviceRecord = DOMAIN.replace(last, last + "\n  service => { a: text }");
        assertEquals("'service' is declared by two different schemas reached through !!import ('schemas.example.com/"
                + "2026/40/schemasplit/protocol-1.tn' and 'schemas.example.com/2026/40/schemasplit/domain-1.tn') -- "
                + "distinct types cannot share one name in the flat namespace; import one of them, or a version of each "
                + "that agrees on where 'service' is declared", refusal(tsonWith(DOMAIN_ID, serviceRecord), DESCRIPTION));

        Tson joined = tsonWith(DOMAIN_ID, DOMAIN.replace(last, last + "\n  service => !schema { thing => { a: text } }"));
        assertEquals(List.of(), joined.validateSchema(DESCRIPTION));
        assertEquals(List.of(), joined.validate(data(DESCRIPTION_ID, "!service.thing { a: \"x\" }")));

        Map<String, String> open = documents("service");
        open.put(DOMAIN_ID, DOMAIN.replace(last, last + "\n  service => !schema { binding => { a: text } }"));
        assertEquals("'service.binding' is declared by two different schemas reached through !!import ('schemas."
                + "example.com/2026/40/schemasplit/http-1.tn' and 'schemas.example.com/2026/40/schemasplit/domain-1.tn')"
                + " -- distinct types cannot share one name in the flat namespace; import one of them, or a version of "
                + "each that agrees on where 'service.binding' is declared",
                refusal(tson(open, bindings("service")), open.get(DESCRIPTION_ID)));
    }

    /**
     * <b>A root map may hold a member of its own name</b>: with the http schema's root called {@code http}, its
     * projection record is {@code http.http}, and a description applying {@code !http.http} and {@code !http.binding}
     * resolves and delivers every reference. Measured only so that a root named for the transport is known to be
     * admissible; which name it is stays the project owner's.
     */
    @Test
    void aRootMapMayHoldAMemberOfItsOwnName() {
        Map<String, String> documents = documents("http");
        Tson tson = tson(documents, bindings("http"));
        assertEquals(List.of(), tson.validateSchema(documents.get(HTTP_ID)));
        assertEquals(List.of(), tson.validateSchema(documents.get(DESCRIPTION_ID)));
        assertProjectionDelivered(tson, DESCRIPTION_ID);
    }
}
