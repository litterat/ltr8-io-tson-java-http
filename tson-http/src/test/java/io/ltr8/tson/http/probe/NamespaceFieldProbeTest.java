package io.ltr8.tson.http.probe;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.probe.namespacefield.AnnotatedMapProtocol;
import io.ltr8.tson.http.probe.namespacefield.Binding;
import io.ltr8.tson.http.probe.namespacefield.Exchange;
import io.ltr8.tson.http.probe.namespacefield.Exemption;
import io.ltr8.tson.http.probe.namespacefield.Http;
import io.ltr8.tson.http.probe.namespacefield.MapProtocol;
import io.ltr8.tson.http.probe.namespacefield.Protocol;
import io.ltr8.tson.http.probe.namespacefield.Resource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.tree.TsonAtom;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a protocol and an http projection can be ordinary records -- declared fields beside one field typed by a
 * namespace -- so that a projection's claims are fields the resolver judges rather than annotations on its entry.
 * Measured against a minimal layer written here, not against {@code meta-service.tn}; {@code
 * proposal/namespace-fields.md} is the proposal it measures. Every test pins Revision 40's outcome, and one failing
 * after a library change means the answer has moved.
 *
 * <p>The answers measured: the layer and a schema applying it resolve clean. A claim written as a field is judged at
 * load: {@code binds} naming nothing, or naming a type or a value of another record type, is refused, and so is an
 * exemption naming a key its protocol does not hold. A reference to an exchange walks the entry, the {@code
 * exchanges} field and the key, {@code orders.exchanges.place_order}; skipping the field, or stopping at it, is
 * refused, and the walk spells the field even where the positional form left it unwritten. No qualified name
 * reaches a path key: a segment is an identifier ([TSON-DATA] §7.7), and neither the quoted nor the bare form
 * parses. A namespace-typed field binds to a {@link Map}, an {@link AnnotatedMap} or a {@link Namespace}, and only
 * the last two keep the keys' order and annotations. A reference judged at load arrives in a schema's value entry as
 * a {@link ValueRef} delivered the value it names, where one from an annotation arrives undelivered.
 */
class NamespaceFieldProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/field/layer-1.tn";
    static final String ID = "https://schemas.example.com/2026/40/field/s-1.tn";

    /**
     * The layer: {@code protocol} and {@code http} as records, each holding one namespace-typed field. {@code safe}
     * marks an exchange's key; {@code claims} is the annotation form of {@code binds}, kept for the comparison.
     */
    static final String LAYER = "!!id:\"" + LAYER_ID + "\"\n!!import:\"" + META + "\"\n!!import:\"" + NET + "\"\n"
            + """
            !schema {
              exchange      => { request?: meta.type_ref  response?: meta.type_ref }
              exchange_name => meta.identifier
              exchanges     => !meta.namespace { key_type: exchange_name  value_type: exchange }
              protocol      => { version?: text  exchanges: exchanges }
              path_template => !unicode.identifier_type {
                start: NONE  start_add: "/"  continue_add: "/{}+%-._~!$&()*,;=:@"
                pattern: "/([^%{}]|%[0-9A-Fa-f]{2}|\\\\{[+]?[A-Za-z0-9_]+\\\\})*" }
              http_verb     => !enum [GET POST]
              binding       => { exchange: meta.ref<exchange>  status?: integer }
              resource      => { endpoints: {http_verb => binding} }
              exemption     => { exchange: meta.ref<exchange>  reason: text }
              resources     => !meta.namespace { key_type: path_template  value_type: resource }
              safe          => @meta.annotation void
              claims        => @meta.annotation [meta.ref<protocol>]
              http          => { binds?: [meta.ref<protocol>]  not_bound?: [exemption]  base?: uri_reference
                                 resources: resources }
            }""";

    static final String ORDERS = "  orders => !protocol { version: \"1\"  exchanges: { place_order => { request: order }"
            + " } }";

    /** A schema importing the layer and core, declaring {@code order}, then {@code entries}. */
    static String schema(String... entries) {
        return "!!id:\"" + ID + "\"\n!!import:\"" + LAYER_ID + "\"\n!!import:\"" + CORE + "\"\n!schema {\n"
                + "  order => { sku: text }\n" + String.join("\n", entries) + "\n}";
    }

    /** The {@code orders_http} projection: {@code binds} as written, and one POST naming {@code exchange}. */
    static String http(String binds, String exchange) {
        return "  orders_http => !http { binds: [" + binds + "]  resources: { \"/orders\" => { endpoints: {"
                + " POST => { exchange: " + exchange + "  status: 201 } } } } }";
    }

    static final String SOUND = schema(ORDERS, http("orders", "orders.exchanges.place_order"));

    static Tson tson(String schema, Class<?> protocol) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        documents.put(ID, schema);
        Map<String, Class<?>> bound = Map.of("exchange", Exchange.class, "protocol", protocol, "binding", Binding.class,
                "resource", Resource.class, "exemption", Exemption.class, "http", Http.class);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(AtomContext.defaultContext().toBuilder()
                        .nameBinder(DataNameBinder.ofMap(bound)).build()));
    }

    static Tson tson(String schema) {
        return tson(schema, Protocol.class);
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    /** The one refusal resolving {@code schema} yields, asserted a schema error. */
    static String refusal(String schema) {
        Diagnostic problem = only(tson(schema).validateSchema(schema));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problem::message);
        return problem.message();
    }

    /** The value the resolved schema's entry {@code name} holds, bound. */
    static Object entry(Tson tson, String name) {
        return assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(ID).orElseThrow().schema().entries().get(name)).value();
    }

    // ── 1: resolution ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A protocol and an http projection written as records resolve clean.</b> The layer declares each with one
     * namespace-typed field ({@code exchanges}, {@code resources}) beside its declared fields, and a schema applying
     * them writes the field names: {@code !protocol { version: "1"  exchanges: { … } }} and {@code !http { binds:
     * [orders]  resources: { "/orders" => … } }}, the binding naming {@code orders.exchanges.place_order}.
     */
    @Test
    void aProtocolAndAnHttpProjectionAsRecordsResolve() {
        Tson tson = tson(SOUND);
        assertEquals(List.of(), tson.validateSchema(LAYER));
        assertEquals(List.of(), tson.validateSchema(SOUND));
    }

    // ── 2: a claim as a field is judged at load ────────────────────────────────────────────────────────────────

    /**
     * <b>A claim written as a field is judged at schema load</b>, where the annotation form is not judged at all
     * ({@code UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged}). {@code binds} naming nothing is refused
     * naming the first segment; naming the type {@code order}, or the value {@code orders_http} of another record
     * type, is refused as no {@code protocol}; and an exemption's exchange naming a key its protocol does not hold is
     * refused as a binding's is. The wording for a type and for a value is one: {@code order} "is an entry whose body
     * is built by 'meta.record'", and the value {@code orders_http} "is an entry whose body is built by 'http'", the
     * record type it is a value of read as though it were a constructor.
     */
    @Test
    void aClaimAsAFieldIsJudgedAtLoad() {
        assertEquals("'orders_http' at /binds/0 names 'no_such', and 'no_such' is no entry of this schema or its "
                        + "imports -- the first segment names the entry a value is reached through, and a member is "
                        + "reached through its entry",
                refusal(schema(ORDERS, http("no_such", "orders.exchanges.place_order"))));
        assertEquals("'orders_http' at /binds/0 wants a 'protocol', and 'order' is an entry whose body is built by "
                        + "'meta.record', which is no 'protocol'",
                refusal(schema(ORDERS, http("order", "orders.exchanges.place_order"))));
        assertEquals("'orders_http' at /binds/0 wants a 'protocol', and 'orders_http' is an entry whose body is "
                        + "built by 'http', which is no 'protocol'",
                refusal(schema(ORDERS, http("orders_http", "orders.exchanges.place_order"))));
        assertEquals("'orders_http' at /not_bound/0/exchange names 'orders.exchanges.nope', and 'orders.exchanges' has "
                        + "no key 'nope' -- it holds place_order",
                refusal(schema(ORDERS, "  orders_http => !http { binds: [orders]  not_bound: [ { exchange: "
                        + "orders.exchanges.nope  reason: \"later\" } ]  resources: { \"/orders\" => { endpoints: {} } }"
                        + " }")));
    }

    // ── 3: the exchange reference walks through the field ──────────────────────────────────────────────────────

    /**
     * <b>An exchange is reached as the entry, the {@code exchanges} field and the key</b>, and only so. A key the
     * namespace does not hold is refused naming the keys it does; skipping the field ({@code orders.place_order}) is
     * refused as no field of {@code protocol}, naming the fields it declares; and stopping at the field ({@code
     * orders.exchanges}) is refused as a value of the namespace type, no {@code exchange}. The JSON Pointer in each
     * escapes the path key's {@code /} as {@code ~1}.
     */
    @Test
    void anExchangeReferenceWalksThroughTheExchangesField() {
        String at = "'orders_http' at /resources/~1orders/endpoints/POST/exchange ";
        assertEquals(at + "names 'orders.exchanges.nope', and 'orders.exchanges' has no key 'nope' -- it holds "
                + "place_order", refusal(schema(ORDERS, http("orders", "orders.exchanges.nope"))));
        assertEquals(at + "names 'orders.place_order', and 'orders' is a 'protocol', which has no field 'place_order' "
                + "-- it declares version, exchanges", refusal(schema(ORDERS, http("orders", "orders.place_order"))));
        assertEquals(at + "wants an 'exchange', and 'orders.exchanges' is an 'exchanges', which is no 'exchange'",
                refusal(schema(ORDERS, http("orders", "orders.exchanges"))));
    }

    // ── 4: a path key is no qualified-name segment ─────────────────────────────────────────────────────────────

    static final String PICK = "  pick => { r: meta.ref<resource> }";

    /**
     * <b>No qualified name reaches a resource through its path key.</b> A segment is an identifier ([TSON-DATA]
     * §7.7) and a path key starts with {@code /}, so neither spelling parses: {@code orders_http.resources."/orders"}
     * ends the name at the dot and leaves a quoted token adjacent to it, and {@code orders_http.resources./orders} is
     * no token. In a data document the reference slot reports the truncated name too, as an empty last segment. This
     * project references no endpoint, so nothing is lost; the proposal states it.
     */
    @Test
    void aPathKeyIsNoQualifiedNameSegment() {
        String quoted = schema(ORDERS, http("orders", "orders.exchanges.place_order"), PICK,
                "  p => !pick { r: orders_http.resources.\"/orders\" }");
        Diagnostic adjacent = only(tson(quoted).validateSchema(quoted));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, adjacent.code());
        assertEquals("adjacent values must be separated by whitespace, a comma, or both", adjacent.message());

        String bare = schema(ORDERS, http("orders", "orders.exchanges.place_order"), PICK,
                "  p => !pick { r: orders_http.resources./orders }");
        Diagnostic unrecognised = only(tson(bare).validateSchema(bare));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, unrecognised.code());
        assertEquals("unrecognised character U+002F", unrecognised.message());

        String picking = schema(ORDERS, http("orders", "orders.exchanges.place_order"), PICK);
        Tson tson = tson(picking);
        assertEquals(List.of(), tson.validateSchema(picking));
        var problems = DiagnosticsReceiver.collecting();
        assertNull(tson.treeReader().withDiagnostics(problems)
                .read("!!import:\"" + ID + "\"\n!pick { r: orders_http.resources.\"/orders\" }"));
        assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID, Diagnostic.Code.VALIDATION_ERROR),
                problems.diagnostics().stream().map(Diagnostic::code).toList(), () -> "" + problems.diagnostics());
        assertEquals("'meta.ref<resource>': 'orders_http.resources.' is no reference -- 'orders_http.resources.' has "
                + "an empty segment -- each segment between the dots is a name", problems.diagnostics().getFirst()
                .message());
    }

    // ── the positional form ─────────────────────────────────────────────────────────────────────────────────

    static final String POSITIONAL = schema("  orders => !protocol { place_order => { request: order } }",
            "  orders_http => !http { \"/orders\" => { POST => { exchange: orders.exchanges.place_order } } }");

    /**
     * <b>The positional form stands in for the namespace-typed field</b> ([TSON-SCHEMA] §5.6): {@code protocol} and
     * {@code http} each have one bare required field, so a value writing nothing else may write the map alone, {@code
     * !protocol { place_order => … }} and {@code !http { "/orders" => … }}, as {@code resource} already does. The
     * reference still walks the field the document did not write, {@code orders.exchanges.place_order}, and {@code
     * orders.place_order} is refused as before. A projection writing {@code binds} cannot use the form: a brace
     * holds fields or keys, and one writing a field and then a key is a parse error at the {@code =>}.
     */
    @Test
    void thePositionalFormStandsInForTheNamespaceField() {
        Tson tson = tson(POSITIONAL);
        assertEquals(List.of(), tson.validateSchema(POSITIONAL));
        Protocol protocol = assertInstanceOf(Protocol.class, entry(tson, "orders"));
        assertEquals(List.of("place_order"), List.copyOf(protocol.exchanges().keySet()));
        Http http = assertInstanceOf(Http.class, entry(tson, "orders_http"));
        assertNull(http.binds());
        assertEquals("orders.exchanges.place_order",
                http.resources().get("/orders").endpoints().get("POST").exchange().name());

        assertEquals("'orders_http' at /resources/~1orders/endpoints/POST/exchange names 'orders.place_order', and "
                + "'orders' is a 'protocol', which has no field 'place_order' -- it declares version, exchanges",
                refusal(POSITIONAL.replace("orders.exchanges.place_order", "orders.place_order")));

        String mixed = POSITIONAL.replace("!http { ", "!http { binds: [orders]  ");
        Diagnostic problem = only(tson(mixed).validateSchema(mixed));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problem.code());
        assertEquals("expected a record field's ':', found '=>'", problem.message());
    }

    // ── 5: binding ──────────────────────────────────────────────────────────────────────────────────────────────

    static final String ANNOTATED = schema("  orders => !protocol { exchanges: { zeta => { request: order }"
            + "  @safe alpha => { request: order }  mid => {} } }");

    /**
     * <b>A namespace-typed field binds to a {@link Map}, an {@link AnnotatedMap} or a {@link Namespace}</b>, and only
     * the last two keep what an exchange's key carries. A {@code Map} component receives a {@link HashMap}: the keys
     * written {@code zeta}, {@code alpha}, {@code mid} lose their order and {@code @safe} on {@code alpha} is gone. An
     * {@code AnnotatedMap} keeps both, and a {@code Namespace} keeps both and names its namespace type, {@code
     * exchanges}. {@code TsonApiDescription} reads {@code @safe} and {@code @idempotent} from the exchange's key, so a
     * {@code Map} would lose the verb check.
     */
    @Test
    void aNamespaceTypedFieldBindsToAMapAnAnnotatedMapOrANamespace() {
        Tson asMap = tson(ANNOTATED, MapProtocol.class);
        assertEquals(List.of(), asMap.validateSchema(ANNOTATED));
        Map<String, Exchange> map = assertInstanceOf(MapProtocol.class, entry(asMap, "orders")).exchanges();
        assertSame(HashMap.class, map.getClass(), "a plain HashMap: no order, no key annotations");
        assertEquals(Set.of("zeta", "alpha", "mid"), map.keySet());

        Tson asAnnotated = tson(ANNOTATED, AnnotatedMapProtocol.class);
        assertEquals(List.of(), asAnnotated.validateSchema(ANNOTATED));
        AnnotatedMap<String, Exchange> annotated =
                assertInstanceOf(AnnotatedMapProtocol.class, entry(asAnnotated, "orders")).exchanges();
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(annotated.keySet()));
        assertTrue(annotated.getAnnotations("alpha").has("safe"));

        Tson asNamespace = tson(ANNOTATED, Protocol.class);
        assertEquals(List.of(), asNamespace.validateSchema(ANNOTATED));
        Protocol protocol = assertInstanceOf(Protocol.class, entry(asNamespace, "orders"));
        assertEquals(Optional.empty(), protocol.version());
        assertEquals("exchanges", protocol.exchanges().type());
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(protocol.exchanges().keySet()));
        assertTrue(protocol.exchanges().getAnnotations("alpha").has("safe"));
        assertEquals("order", protocol.exchanges().get("zeta").request().orElseThrow().name());
    }

    static final String CLAIMED = schema(ORDERS, "  @claims:[orders]\n  holder => { a: text }",
            "  orders_http => !http { binds: [orders]  base: \"https://api.example.com/v1/\"  resources: {} }");

    /**
     * <b>A claim field arrives as a {@link ValueRef} delivered the value it names; a claim in an annotation arrives
     * undelivered.</b> The resolver judged {@code binds: [orders]} at load, and the value entry holds a {@code
     * ValueRef} {@code orders} whose {@code get()} is the bound {@link Protocol}. The {@code @claims:[orders]}
     * annotation's element is a {@code ValueRef} of the same name, equal to it, since a reference's identity is its
     * name, but never delivered: {@code isResolved()} is false. A reference read in a <em>data</em> document under the
     * schema is delivered at the read, in a tree read and a bound one alike: the exchange the name reaches. An absent
     * {@code not_bound} arrives as {@code null}, and {@code base} as an {@code Iri}.
     */
    @Test
    void aClaimFieldArrivesAsADeliveredValueRefAndAnAnnotationsAsUndelivered() {
        Tson tson = tson(CLAIMED);
        assertEquals(List.of(), tson.validateSchema(CLAIMED));
        Http http = assertInstanceOf(Http.class, entry(tson, "orders_http"));
        ValueRef<?> field = http.binds().getFirst();
        assertEquals("orders", field.name());
        assertTrue(field.isResolved());
        assertEquals(Optional.of("1"), assertInstanceOf(Protocol.class, field.get()).version());
        assertNull(http.notBound());
        assertEquals("https://api.example.com/v1/", http.base().orElseThrow().toString());

        var annotations = tson.schemaRegistry().get(ID).orElseThrow().schema().entries().getAnnotations("holder");
        ValueRef<?> annotated = assertInstanceOf(ValueRef.class,
                annotations.value("claims", List.class).orElseThrow().getFirst());
        assertFalse(annotated.isResolved());
        assertEquals(field, annotated);

        String data = "!!import:\"" + ID + "\"\n!binding { exchange: orders.exchanges.place_order }";
        var problems = DiagnosticsReceiver.collecting();
        TsonAtom atom = assertInstanceOf(TsonAtom.class,
                tson.treeReader().withDiagnostics(problems).read(data).at("/exchange"));
        assertEquals(List.of(), problems.diagnostics());
        ValueRef<?> inData = assertInstanceOf(ValueRef.class, atom.value());
        Exchange reached = assertInstanceOf(Exchange.class, inData.get());
        assertEquals("order", reached.request().orElseThrow().name());
        Binding bound = tson.objectReader().read(data, Binding.class);
        assertEquals("order", bound.exchange().get().request().orElseThrow().name());
    }

    /**
     * <b>An endpoint's exchange arrives as a {@link ValueRef} of three segments</b>, {@code orders.exchanges
     * .place_order}, reached in the bound value as {@code resources."/orders".endpoints.POST}, and delivered the
     * {@link Exchange} it names. A reader splitting the name takes the first segment as the protocol's entry and the
     * last as the exchange's key; the middle is the field name, fixed by the layer.
     */
    @Test
    void anEndpointsExchangeArrivesAsAValueRefOfThreeSegments() {
        Tson tson = tson(SOUND);
        assertEquals(List.of(), tson.validateSchema(SOUND));
        Http http = assertInstanceOf(Http.class, entry(tson, "orders_http"));
        assertEquals(List.of("orders"), http.binds().stream().map(ValueRef::name).toList());
        assertEquals("resources", http.resources().type());
        Binding post = http.resources().get("/orders").endpoints().get("POST");
        assertEquals("orders.exchanges.place_order", post.exchange().name());
        assertEquals(List.of("orders", "exchanges", "place_order"), List.of(post.exchange().name().split("\\.")));
        assertEquals("order", post.exchange().get().request().orElseThrow().name());
        assertEquals(Optional.of(201), post.status());
        assertEquals(Optional.of("1"), assertInstanceOf(Protocol.class, entry(tson, "orders")).version());
    }
}
