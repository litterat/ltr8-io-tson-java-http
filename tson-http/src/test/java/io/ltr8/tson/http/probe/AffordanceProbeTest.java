package io.ltr8.tson.http.probe;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.probe.affordance.Binding;
import io.ltr8.tson.http.probe.affordance.Document;
import io.ltr8.tson.http.probe.affordance.Endpoint;
import io.ltr8.tson.http.probe.affordance.Event;
import io.ltr8.tson.http.probe.affordance.EventSource;
import io.ltr8.tson.http.probe.affordance.Exchange;
import io.ltr8.tson.http.probe.affordance.Http;
import io.ltr8.tson.http.probe.affordance.Instance;
import io.ltr8.tson.http.probe.affordance.Interface;
import io.ltr8.tson.http.probe.affordance.Live;
import io.ltr8.tson.http.probe.affordance.Property;
import io.ltr8.tson.http.probe.affordance.Protocol;
import io.ltr8.tson.http.probe.affordance.Resource;
import io.ltr8.tson.http.probe.affordance.ServedDocument;
import io.ltr8.tson.http.probe.affordance.ServedExchange;
import io.ltr8.tson.http.probe.affordance.ServedInterface;
import io.ltr8.tson.http.probe.affordance.ServedProperty;
import io.ltr8.tson.http.probe.affordance.Source;
import io.ltr8.tson.http.probe.affordance.State;
import io.ltr8.tson.http.probe.affordance.Stream;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the service layer could hold SDF's other two affordance kinds (RFC 9880 §2.2.2, §2.2.4) beside its
 * exchanges, and whether a running deployment's concrete paths could be described as a namespace of live objects
 * referring back into the description. Measured against a minimal layer written here, not against {@code service.tn};
 * {@code proposal/affordances.md} is the proposal it measures. Every test pins Revision 40's outcome, and one failing
 * after a library change means the answer has moved.
 *
 * <p>The answers measured: optional {@code properties} and {@code events} namespace fields beside a protocol's
 * required {@code exchanges} break no document written with exchanges only, in the full form or the positional one;
 * making {@code exchanges} optional too withdraws the positional form. A property's facts written as annotations on
 * its key survive into the namespace's key annotations, and a misspelt one is refused. A streaming endpoint kind
 * composing the endpoint base is admitted beside {@code binding} and {@code document}, its one {@code meta.ref} slot
 * reaching an event or a property through their common base, and an untagged endpoint is refused naming every
 * subtype. A namespace keyed by concrete paths needs an identifier family widened as {@code path_template} is; a text
 * family is refused as a key type and {@code meta.identifier} refuses the leading {@code /}. A family admitting {@code
 * .} is refused as a key type too, so a concrete path holding one, a well-known path or a schema's identity path, keys
 * no namespace. Read as a data document
 * importing the description, an instance's objects arrive with their references delivered through the tree reader and
 * the object reader, but only while the document imports one schema: importing the description and a separate
 * instance vocabulary throws at the first reference walking a protocol's field.
 */
class AffordanceProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/affordance/layer-1.tn";
    static final String INSTANCE_ID = "https://schemas.example.com/2026/40/affordance/instance-1.tn";
    static final String ID = "https://schemas.example.com/2026/40/affordance/s-1.tn";

    /** The protocol record: required exchanges, and two optional sibling namespaces. */
    static final String PROTOCOL = "protocol => { exchanges: exchanges  properties?: properties  events?: events }";

    /**
     * The concrete-path key family: {@code path_template}'s character set without a template's braces. Neither holds
     * {@code .}, which no namespace's key type admits ([TSON-SCHEMA] §5.12).
     */
    static final String CONCRETE = """
            concrete_path => !unicode.identifier_type {
                start: NONE  start_add: "/"  continue_add: "/+%-_~!$&()*,;=:@"
                pattern: "/([^%{}]|%[0-9A-Fa-f]{2})*" }""";

    /** The instance vocabulary over {@code concrete_path}: a tagged family of object kinds keyed by path. */
    static final String INSTANCE = """
              live             => {}
              served_document  => live & { media_type: media_type  identity?: text }
              served_exchange  => live & { exchange: meta.ref<exchange> }
              served_property  => live & { property: meta.ref<property> }
              served_interface => live & { protocol: meta.ref<protocol> }
              event_source     => live & { event: meta.ref<event> }
              objects          => !meta.namespace { key_type: concrete_path  value_type: live }
              instance         => { name: text  objects: objects }""";

    /**
     * The layer: exchanges, properties and events as three namespaces under one protocol record; the endpoint base
     * with {@code binding} and {@code document} as {@code http.tn} has them, plus {@code stream}, {@code state} and
     * {@code interface}; and {@code extra} declarations at the end.
     */
    static String layer(String protocol, String extra) {
        return "!!id:\"" + LAYER_ID + "\"\n!!import:\"" + META + "\"\n!!import:\"" + NET + "\"\n" + """
            !schema {
              exchange        => { request?: meta.type_ref  response?: meta.type_ref }
              affordance_name => meta.identifier
              exchanges       => !meta.namespace { key_type: affordance_name  value_type: exchange }
              source          => {}
              property        => source & { type: meta.type_ref }
              properties      => !meta.namespace { key_type: affordance_name  value_type: property }
              event           => source & { payload: meta.type_ref }
              events          => !meta.namespace { key_type: affordance_name  value_type: event }
              %s
              safe            => @meta.annotation void
              writable        => @meta.annotation void
              observable      => @meta.annotation void
              path_template   => !unicode.identifier_type {
                start: NONE  start_add: "/"  continue_add: "/{}+%%-_~!$&()*,;=:@"
                pattern: "/([^%%{}]|%%[0-9A-Fa-f]{2}|\\\\{[+]?[A-Za-z0-9_]+\\\\})*" }
              http_verb       => !enum [GET PUT POST]
              endpoint        => { status?: integer  media_types?: [media_type] }
              binding         => endpoint & { exchange: meta.ref<exchange> }
              document        => endpoint & { request?: meta.type_ref }
              stream          => endpoint & { source: meta.ref<source> }
              state           => endpoint & { property: meta.ref<property> }
              interface       => endpoint & { protocol: meta.ref<protocol> }
              resource        => { endpoints: {http_verb => endpoint} }
              resources       => !meta.namespace { key_type: path_template  value_type: resource }
              http            => { binds?: [meta.ref<protocol>]  resources: resources }
            %s
            }""".formatted(protocol, extra);
    }

    /** The layer with the instance vocabulary declared in it, so an instance imports the description alone. */
    static final String LAYER = layer(PROTOCOL, "  " + CONCRETE + "\n" + INSTANCE);

    /** The instance vocabulary as a schema of its own, importing the layer. */
    static final String INSTANCE_SCHEMA = "!!id:\"" + INSTANCE_ID + "\"\n!!import:\"" + META + "\"\n!!import:\"" + NET
            + "\"\n!!import:\"" + LAYER_ID + "\"\n!schema {\n  " + CONCRETE + "\n" + INSTANCE + "\n}";

    /** A description importing the layer and core, declaring {@code order} and {@code heat}, then {@code entries}. */
    static String schema(String... entries) {
        return "!!id:\"" + ID + "\"\n!!import:\"" + LAYER_ID + "\"\n!!import:\"" + CORE + "\"\n!schema {\n"
                + "  order => { sku: text }\n  heat => { celsius: int32 }\n" + String.join("\n", entries) + "\n}";
    }

    static final String ORDERS =
            "  orders => !protocol { exchanges: { @safe get_order => { request: order  response: order } } }";

    /** A protocol writing all three kinds; {@code brightness} is both an exchange and a property. */
    static final String LAMP = """
              lamp => !protocol {
                exchanges:  { @safe brightness => { response: heat } }
                properties: { @writable @observable brightness => { type: int32 }  serial => { type: text } }
                events:     { overheated => { payload: heat } }
              }""";

    static final String LAMP_HTTP = """
              lamp_http => !http { binds: [lamp]  resources: {
                "/lamp/brightness"      => { GET => !state { property: lamp.properties.brightness }
                                             PUT => !state { property: lamp.properties.brightness  status: 204 } }
                "/lamp/events"          => { GET => !stream { source: lamp.events.overheated
                                                              media_types: ["text/event-stream"] } }
                "/lamp/brightness/live" => { GET => !stream { source: lamp.properties.brightness
                                                              media_types: ["text/event-stream"] } }
                "/lamp/reading"         => { GET => !binding { exchange: lamp.exchanges.brightness } }
                "/rpc/lamp"             => { POST => !interface { protocol: lamp } }
                "/schemas"              => { GET => !document { media_types: ["application/tson"] } }
              } }""";

    static final String DESCRIPTION = schema(ORDERS, LAMP, LAMP_HTTP);

    static Tson tson(String layer, String schema) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, layer);
        documents.put(INSTANCE_ID, INSTANCE_SCHEMA);
        documents.put(ID, schema);
        Map<String, Class<?>> bound = new LinkedHashMap<>();
        bound.put("exchange", Exchange.class);
        bound.put("source", Source.class);
        bound.put("property", Property.class);
        bound.put("event", Event.class);
        bound.put("protocol", Protocol.class);
        bound.put("endpoint", Endpoint.class);
        bound.put("binding", Binding.class);
        bound.put("document", Document.class);
        bound.put("stream", Stream.class);
        bound.put("state", State.class);
        bound.put("interface", Interface.class);
        bound.put("resource", Resource.class);
        bound.put("http", Http.class);
        bound.put("live", Live.class);
        bound.put("served_document", ServedDocument.class);
        bound.put("served_exchange", ServedExchange.class);
        bound.put("served_property", ServedProperty.class);
        bound.put("served_interface", ServedInterface.class);
        bound.put("event_source", EventSource.class);
        bound.put("instance", Instance.class);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(AtomContext.defaultContext().toBuilder()
                        .nameBinder(DataNameBinder.ofMap(bound)).build()));
    }

    static Tson tson(String schema) {
        return tson(LAYER, schema);
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    /** The one refusal resolving {@code schema} under {@code layer} yields, asserted a schema error. */
    static String refusal(String layer, String schema) {
        Diagnostic problem = only(tson(layer, schema).validateSchema(schema));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problem::message);
        return problem.message();
    }

    static String refusal(String schema) {
        return refusal(LAYER, schema);
    }

    /** The value the resolved description's entry {@code name} holds, bound. */
    static Object entry(Tson tson, String name) {
        return assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(ID).orElseThrow().schema().entries().get(name)).value();
    }

    // ── 1: optional sibling namespaces ──────────────────────────────────────────────────────────────────────────

    static final String POSITIONAL = schema("  orders => !protocol { get_order => { request: order } }");

    /**
     * <b>Optional {@code properties} and {@code events} beside a required {@code exchanges} break no exchanges-only
     * document.</b> The protocol keeps one bare required field, so [TSON-SCHEMA] §5.6's positional form still stands in
     * for it: {@code !protocol { get_order => … }} loads as {@code !protocol { exchanges: { get_order => … } }} does,
     * and a protocol writing all three kinds loads. An absent sibling arrives as {@code null}.
     */
    @Test
    void optionalSiblingNamespacesKeepEveryExchangesOnlyDocument() {
        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(LAYER));
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Protocol orders = assertInstanceOf(Protocol.class, entry(tson, "orders"));
        assertEquals(List.of("get_order"), List.copyOf(orders.exchanges().keySet()));
        assertNull(orders.properties());
        assertNull(orders.events());
        Protocol lamp = assertInstanceOf(Protocol.class, entry(tson, "lamp"));
        assertEquals(List.of("brightness", "serial"), List.copyOf(lamp.properties().keySet()));
        assertEquals("heat", lamp.events().get("overheated").payload().name());

        Tson positional = tson(POSITIONAL);
        assertEquals(List.of(), positional.validateSchema(POSITIONAL));
        Protocol bare = assertInstanceOf(Protocol.class, entry(positional, "orders"));
        assertEquals(List.of("get_order"), List.copyOf(bare.exchanges().keySet()));
        assertNull(bare.properties());
    }

    /**
     * <b>A protocol with no required field loses the positional form, and one with a required {@code exchanges} makes
     * a properties-only protocol write it empty.</b> With {@code exchanges?} optional, the map written alone is refused
     * as no record. With {@code exchanges} required, a protocol writing only properties is refused for the missing
     * field, and loads with {@code exchanges: {}} written.
     */
    @Test
    void aProtocolWithNoRequiredFieldLosesThePositionalForm() {
        String allOptional = layer("protocol => { exchanges?: exchanges  properties?: properties  events?: events }",
                "");
        assertEquals("'orders': the body is no value of the type 'protocol' -- expected a record for 'protocol', found "
                + "a map", refusal(allOptional, POSITIONAL));

        String propertiesOnly = schema("  lamp => !protocol { properties: { serial => { type: text } } }");
        assertEquals("'lamp': the body is no value of the type 'protocol' -- at /exchanges: missing required field "
                + "'exchanges' for 'protocol'", refusal(propertiesOnly));
        assertEquals(List.of(), tson(allOptional, propertiesOnly).validateSchema(propertiesOnly));

        String emptyExchanges = schema("  lamp => !protocol { exchanges: {}  properties: { serial => { type: text } } }");
        assertEquals(List.of(), tson(emptyExchanges).validateSchema(emptyExchanges));
    }

    // ── 2: a property's facts ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A property's facts written as annotations on its key survive into the namespace's key annotations</b>, as an
     * exchange's {@code @safe} does, and a misspelt one is refused naming it, so a marker is as checked as a field.
     * A property and an exchange may share a name: they are keys of two namespaces, and each reference spells its
     * field ({@code lamp.properties.brightness}, {@code lamp.exchanges.brightness}).
     */
    @Test
    void aPropertysFactsAreAnnotationsOnItsKey() {
        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Protocol lamp = assertInstanceOf(Protocol.class, entry(tson, "lamp"));
        var facts = lamp.properties().getAnnotations("brightness");
        assertTrue(facts.has("writable"));
        assertTrue(facts.has("observable"));
        assertFalse(lamp.properties().getAnnotations("serial").has("writable"));
        assertTrue(lamp.exchanges().getAnnotations("brightness").has("safe"));
        assertEquals("int32", lamp.properties().get("brightness").type().name());

        String misspelt = schema("  lamp => !protocol { exchanges: {}  properties: { @obsrvable serial => { type: text } }"
                + " }");
        assertEquals("'lamp': the body is no value of the type 'protocol' -- at /properties/?: annotation '@obsrvable' "
                + "names no type the governing schema declares (§6)", refusal(misspelt));
    }

    // ── 3: endpoint kinds ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A streaming endpoint kind composing the endpoint base is admitted beside {@code binding} and {@code
     * document}</b>, as are a property's {@code state} endpoint and a protocol's {@code interface} endpoint. Its {@code
     * source} is a {@code meta.ref<source>}, the empty base of {@code property} and {@code event}, and reaches either,
     * delivered; an exchange there is refused as no {@code source}. An untagged endpoint is refused by the binder
     * naming every subtype, the new ones included.
     */
    @Test
    void aStreamEndpointIsAdmittedBesideBindingAndDocument() {
        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Http http = assertInstanceOf(Http.class, entry(tson, "lamp_http"));
        Stream events = assertInstanceOf(Stream.class, http.resources().get("/lamp/events").endpoints().get("GET"));
        assertEquals(List.of("text/event-stream"), events.mediaTypes());
        assertEquals("lamp.events.overheated", events.source().name());
        assertInstanceOf(Event.class, events.source().get());
        Stream observed = assertInstanceOf(Stream.class,
                http.resources().get("/lamp/brightness/live").endpoints().get("GET"));
        assertInstanceOf(Property.class, observed.source().get());
        assertInstanceOf(Binding.class, http.resources().get("/lamp/reading").endpoints().get("GET"));
        assertInstanceOf(Document.class, http.resources().get("/schemas").endpoints().get("GET"));
        Interface calls = assertInstanceOf(Interface.class, http.resources().get("/rpc/lamp").endpoints().get("POST"));
        assertInstanceOf(Protocol.class, calls.protocol().get());

        assertEquals("'lamp_http' at /resources/~1lamp~1events/endpoints/GET/source wants a 'source', and "
                + "'lamp.exchanges.brightness' is an 'exchange', which is no 'source'",
                refusal(DESCRIPTION.replace("source: lamp.events.overheated", "source: lamp.exchanges.brightness")));
        assertEquals("'lamp_http': the body is no value of the type 'http' -- at /resources/~1lamp~1events/endpoints/GET: "
                + "'endpoint' has no data of its own to bind -- provide an explicit type annotation (!typeName) naming "
                + "one of its subtypes [binding, document, stream, state, interface]",
                refusal(DESCRIPTION.replace("GET => !stream { source: lamp.events.overheated",
                        "GET => { source: lamp.events.overheated")));
    }

    /**
     * <b>A property binds at two verbs of one path, and the resolver judges only the kind each names.</b> {@code GET}
     * and {@code PUT} under {@code /lamp/brightness} each name {@code lamp.properties.brightness} with no placement;
     * an event at the {@code property} slot is refused. That {@code PUT} needs {@code @writable}, and what a {@code PUT}
     * body is when there is no request record, would be the reader's.
     */
    @Test
    void aPropertyBindsAtTwoVerbsOfOnePath() {
        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        Http http = assertInstanceOf(Http.class, entry(tson, "lamp_http"));
        Map<String, Endpoint> verbs = http.resources().get("/lamp/brightness").endpoints();
        State get = assertInstanceOf(State.class, verbs.get("GET"));
        State put = assertInstanceOf(State.class, verbs.get("PUT"));
        assertEquals(get.property(), put.property());
        assertEquals(Optional.of(204), put.status());

        assertEquals("'lamp_http' at /resources/~1lamp~1brightness/endpoints/PUT/property wants a 'property', and "
                + "'lamp.events.overheated' is an 'event', which is no 'property'",
                refusal(DESCRIPTION.replace("PUT => !state { property: lamp.properties.brightness",
                        "PUT => !state { property: lamp.events.overheated")));
    }

    // ── 4: a namespace keyed by concrete path ───────────────────────────────────────────────────────────────────

    static final String HEAD = "!!import:\"" + ID + "\"\n";

    static String instanceOf(String objects) {
        return HEAD + "!instance { name: \"orders-1\"  objects: {\n" + objects + "\n} }";
    }

    static final String SERVED = instanceOf("""
              "/"                                => !served_document { media_type: "text/html" }
              "/orders/42"                       => !served_exchange { exchange: orders.exchanges.get_order }
              "/lamp/brightness"                 => !served_property { property: lamp.properties.brightness }
              "/rpc/lamp"                        => !served_interface { protocol: lamp }
              "/lamp/events"                     => !event_source { event: lamp.events.overheated }
              "/schemas/service"                 => !served_document { media_type: "application/tson"
                                                      identity: "https://tson.io/2026/40/io/ltr8/http/service.tn" }""");

    /** The diagnostics a tree read of {@code data} reports. */
    static List<Diagnostic> read(Tson tson, String data) {
        var problems = DiagnosticsReceiver.collecting();
        tson.treeReader().withDiagnostics(problems).read(data);
        return problems.diagnostics();
    }

    static String one(Tson tson, String objects) {
        Diagnostic problem = only(read(tson, instanceOf(objects)));
        return problem.code() + ": " + problem.message();
    }

    /**
     * <b>A namespace keyed by concrete paths needs an identifier family widened to a path's characters</b>, as {@code
     * path_template} is. A text family with a {@code pattern} is refused as a namespace's key type. {@code
     * meta.identifier} is accepted as one and refuses {@code /orders/42} at its first character. A family admitting
     * {@code .} is refused as a key type, since a name holding one could not be reached by a reference ([TSON-SCHEMA]
     * §5.12), so the widened family leaves {@code .} out: it admits the root, a digit segment and a nested path, and
     * refuses a template variable, a relative path, a query, a mixed-script path, a well-known path and a schema's
     * identity path, each naming the key.
     */
    @Test
    void aConcretePathKeyIsAnIdentifierFamilyWidenedAsAPathTemplateIs() {
        String textKeyed = layer(PROTOCOL, "  concrete_path => !unicode.text_type { pattern: \"/[^{}]*\" }\n" + INSTANCE);
        Diagnostic text = only(tson(textKeyed, DESCRIPTION).validateSchema(textKeyed));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, text.code());
        assertTrue(text.message().startsWith("'objects' key_type names 'concrete_path', which is no identifier family"),
                text.message());

        String identifierKeyed = layer(PROTOCOL, "  concrete_path => meta.identifier\n" + INSTANCE);
        Tson plain = tson(identifierKeyed, DESCRIPTION);
        assertEquals(List.of(), plain.validateSchema(identifierKeyed));
        assertEquals(List.of(), plain.validateSchema(DESCRIPTION));
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': '/orders/42': U+002F at index 0 cannot start an identifier",
                one(plain, "\"/orders/42\" => !served_exchange { exchange: orders.exchanges.get_order }"));

        String dotted = layer(PROTOCOL, "  " + CONCRETE.replace("\"/+%-_~", "\"/+%-._~") + "\n" + INSTANCE);
        Diagnostic dot = only(tson(dotted, DESCRIPTION).validateSchema(dotted));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, dot.code());
        assertEquals("'objects' key_type names 'concrete_path', which cannot name a namespace's keys -- its profile "
                + "admits '.' (U+002E), which joins the segments of a qualified name, so a name holding one could not be "
                + "reached by a reference ([TSON-SCHEMA] §5.12) ([TSON-SCHEMA] §4.2)", dot.message());

        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        assertEquals(List.of(), read(tson, SERVED));
        String exchange = " => !served_exchange { exchange: orders.exchanges.get_order }";
        String document = " => !served_document { media_type: \"application/tson\" }";
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': '/.well-known/tson-deployment': U+002E at index 1 cannot "
                + "appear in an identifier", one(tson, "\"/.well-known/tson-deployment\"" + document));
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': '/2026/40/io/ltr8/http/service.tn': U+002E at index 29 "
                + "cannot appear in an identifier", one(tson, "\"/2026/40/io/ltr8/http/service.tn\"" + document));
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': '/orders/{id}': U+007B at index 8 cannot appear in an "
                + "identifier", one(tson, "\"/orders/{id}\"" + exchange));
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': 'orders/42': U+006F at index 0 cannot start an identifier -- "
                + "its Start characters are '/'", one(tson, "\"orders/42\"" + exchange));
        assertEquals("ATOM_FORM_INVALID: 'concrete_path': '/orders/42?x=1': U+003F at index 10 cannot appear in an "
                + "identifier", one(tson, "\"/orders/42?x=1\"" + exchange));
        assertEquals("RESTRICTED_SCRIPT: the name '/pаth' mixes the scripts [LATIN, CYRILLIC], which UTS #39 §5.2's "
                + "HIGHLY_RESTRICTIVE does not admit -- a homograph reads as another name exactly by mixing scripts "
                + "inside one word", one(tson, "\"/pаth\"" + exchange));
    }

    // ── 5: an instance as a data document ───────────────────────────────────────────────────────────────────────

    /**
     * <b>An instance is a data document importing the description, and its objects refer back into it.</b> Its root
     * is a record holding a namespace keyed by concrete path, whose values are tagged object kinds; the document
     * exports nothing. It validates clean; through the tree reader the object at {@code /orders/42} holds a {@link
     * ValueRef} delivered the exchange it names; through the object reader every object arrives as its kind, in the
     * order written, each reference delivered. A key the protocol does not hold, a reference to the wrong kind, and an
     * untagged object are refused at the object.
     */
    @Test
    void anInstanceIsADataDocumentReferringIntoTheDescription() {
        Tson tson = tson(DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        assertEquals(List.of(), tson.validate(SERVED));

        var problems = DiagnosticsReceiver.collecting();
        TsonAtom atom = assertInstanceOf(TsonAtom.class,
                tson.treeReader().withDiagnostics(problems).read(SERVED).at("/objects/~1orders~142/exchange"));
        assertEquals(List.of(), problems.diagnostics());
        ValueRef<?> reference = assertInstanceOf(ValueRef.class, atom.value());
        assertEquals("orders.exchanges.get_order", reference.name());
        assertEquals("order", assertInstanceOf(Exchange.class, reference.get()).request().orElseThrow().name());

        Instance instance = tson.objectReader().read(SERVED, Instance.class);
        assertEquals("objects", instance.objects().type());
        assertEquals(List.of("/", "/orders/42", "/lamp/brightness", "/rpc/lamp", "/lamp/events", "/schemas/service"),
                List.copyOf(instance.objects().keySet()));
        ServedExchange served = assertInstanceOf(ServedExchange.class, instance.objects().get("/orders/42"));
        assertEquals("order", served.exchange().get().response().orElseThrow().name());
        ServedProperty property = assertInstanceOf(ServedProperty.class, instance.objects().get("/lamp/brightness"));
        assertEquals("int32", property.property().get().type().name());
        assertInstanceOf(Protocol.class,
                assertInstanceOf(ServedInterface.class, instance.objects().get("/rpc/lamp")).protocol().get());
        assertEquals("heat", assertInstanceOf(EventSource.class, instance.objects().get("/lamp/events")).event().get()
                .payload().name());
        assertEquals(Optional.of("https://tson.io/2026/40/io/ltr8/http/service.tn"), assertInstanceOf(
                ServedDocument.class, instance.objects().get("/schemas/service")).identity());

        assertEquals("ATOM_CONSTRAINT_VIOLATION: 'meta.ref<exchange>': this reference names 'orders.exchanges.nope', "
                + "and 'orders.exchanges' has no key 'nope' -- it holds get_order",
                one(tson, "\"/orders/42\" => !served_exchange { exchange: orders.exchanges.nope }"));
        assertEquals("ATOM_CONSTRAINT_VIOLATION: 'meta.ref<exchange>': this reference wants an 'exchange', and "
                + "'lamp.properties.brightness' is a 'property', which is no 'exchange'",
                one(tson, "\"/orders/42\" => !served_exchange { exchange: lamp.properties.brightness }"));
        assertEquals("UNRECOGNIZED_FIELD: unknown field 'exchange' on 'live' -- a record is closed under its type "
                + "(§7.2), whose fields are ()",
                one(tson, "\"/orders/42\" => { exchange: orders.exchanges.get_order }"));
    }

    /**
     * <b>Open: an instance importing the description and a separate instance vocabulary throws at the first reference
     * walking a protocol's field.</b> A data document importing several schemas is read under a closure the registry
     * links with no bind context ({@code TsonCompiledSchemaRegistry.get(List, …)}), and a reference through a record
     * value's field ({@code orders.exchanges.get_order}) needs one to read the field. Validation, the tree reader and
     * the object reader each throw {@link IllegalStateException} rather than report, warm or cold. So an instance
     * vocabulary would live in the layer the description imports until the gap closes. Staged in {@code UPSTREAM.md}
     * as the data document importing two schemas that throws at a reference through a record value's field; the
     * assertions flip when the read reports or succeeds.
     */
    @Test
    void anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField() {
        String layerAlone = layer(PROTOCOL, "");
        String twoImports = SERVED.replace(HEAD, HEAD + "!!import:\"" + INSTANCE_ID + "\"\n");
        String expected = "this reference names 'orders.exchanges.get_order', and 'orders.exchanges' is a field of a "
                + "record value, and this schema was linked with no bind context to read it through -- link it with the "
                + "context its values were bound under (TsonCompiledVocabularyRegistry.link)";

        Tson tson = tson(layerAlone, DESCRIPTION);
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));
        assertEquals(List.of(), tson.validateSchema(INSTANCE_SCHEMA));
        assertEquals(expected, assertThrows(IllegalStateException.class, () -> tson.validate(twoImports)).getMessage());
        assertEquals(expected, assertThrows(IllegalStateException.class, () -> tson.treeReader().read(twoImports))
                .getMessage());
        assertEquals(expected, assertThrows(IllegalStateException.class,
                () -> tson.objectReader().read(twoImports, Instance.class)).getMessage());

        Tson cold = tson(layerAlone, DESCRIPTION);
        assertEquals(expected, assertThrows(IllegalStateException.class, () -> cold.validate(twoImports)).getMessage());
    }
}
