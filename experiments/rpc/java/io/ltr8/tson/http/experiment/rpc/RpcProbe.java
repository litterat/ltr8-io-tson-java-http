package io.ltr8.tson.http.experiment.rpc;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.http.api.OrdersExamples;
import io.ltr8.tson.http.api.Protocol;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rpc.tn} -- the wire form of a protocol, {@code call} and {@code return} as parametric records -- closed
 * per exchange by a wire schema ({@code orders-wire-1.tn}), so that a packet is a fully typed document. The protocol
 * is {@code tson-http}'s own {@link OrdersExamples}.
 *
 * <p>Measured: both schemas resolve, each type argument a declared name (a choice of errors is declared under
 * a name before it is passed); a packet's request is checked against the protocol's own types ({@code quantity:
 * two} is an {@code int32} violation at {@code /request/order/quantity}); an error arm carries the declared error
 * with its status pin enforced, and where an exchange declares several the value takes the error's tag; a return
 * has exactly one outcome; the envelope reads, {@code deadline} as a duration; and the wire schema is what the
 * protocol determines. Nothing here needs a scope push: the applications type the payload at the schema, so no packet
 * names its own.
 */
class RpcProbe {

    static final String RPC_ID = "https://tson.io/2026/40/io/ltr8/http/rpc.tn";
    static final String WIRE_ID = "https://schemas.example.com/2026/40/experiment/rpc/orders-wire-1.tn";
    static final String ORDERS_ID = OrdersExamples.id("orders-1.tn");

    static String read(String file) {
        try {
            return Files.readString(Path.of(System.getProperty("experiments.dir", "../experiments"))
                    .resolve("rpc").resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Tson tson() {
        Map<String, String> lib = OrdersExamples.library();
        lib.put(RPC_ID, read("rpc.tn"));
        lib.put(WIRE_ID, read("orders-wire-1.tn"));
        return OrdersExamples.tson(lib);
    }

    static String packet(String body) {
        return "!!import:\"" + WIRE_ID + "\"\n" + body;
    }

    @Test
    void theRpcAndWireSchemasResolve() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(read("rpc.tn")));
        assertEquals(List.of(), tson.validateSchema(read("orders-wire-1.tn")));
    }

    /** A call is a fully typed document: its request is the protocol's own record, checked by the resolver. */
    @Test
    void aCallIsTypedByTheProtocolsOwnTypes() {
        Tson tson = tson();
        String call = packet("""
            !place_order_call {
              interface: "%s"  method: place_order  id: c1  deadline: PT5S
              request: { order: { sku: A-100  quantity: 2 }  idempotency_key: k1 }
            }""".formatted(ORDERS_ID));
        assertEquals(List.of(), tson.validate(call));

        TsonValue value = tson.treeReader().read(call);
        assertEquals(ORDERS_ID, value.get("interface").as(io.ltr8.net.Iri.class).orElseThrow().text());
        assertEquals("c1", value.get("id").asString().orElseThrow());
        assertTrue(value.get("deadline").as(Object.class).isPresent());
        assertEquals(2, value.at("/request/order/quantity").asInt().orElseThrow());

        List<Diagnostic> problems = tson.validate(call.replace("quantity: 2", "quantity: two"));
        assertEquals(1, problems.size(), () -> "" + problems);
        assertEquals("/request/order/quantity", problems.getFirst().path().orElseThrow());
        // ATOM_FORM_INVALID rather than ATOM_CONSTRAINT_VIOLATION: `two` is a token int32's grammar
        // rejects, which §5.2 files as a parse error, where a parsed value out of range is the other code.
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code());
    }

    /** The error arm is the declared error, its fixed status enforced; the response arm the declared response. */
    @Test
    void aReturnCarriesTheDeclaredResponseOrError() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(packet(
                "!place_order_return { id: c1  response: { sku: A-100  quantity: 4 } }")));
        assertEquals(List.of(), tson.validate(packet("""
            !place_order_return { id: c1
              error: { type: "https://ltr8.io/2026/40/http/problems/sku-not-found"  title: "No such SKU"
                       status: 404  sku: A-100 } }""")));

        List<Diagnostic> wrongStatus = tson.validate(packet(
                "!place_order_return { id: c1  error: { title: \"x\"  status: 400  sku: A-100 } }"));
        assertEquals(1, wrongStatus.size(), () -> "" + wrongStatus);
        assertEquals(Diagnostic.Code.FIELD_FIXED, wrongStatus.getFirst().code());
        assertEquals("/error/status", wrongStatus.getFirst().path().orElseThrow());
    }

    /** Exactly one outcome; an exchange with no declared errors closes `return_plain`, a `void` response included. */
    @Test
    void aReturnHasExactlyOneOutcome() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(packet("!list_orders_return { id: c1  response: { items: [] } }")));
        assertEquals(List.of(), tson.validate(packet(
                "!cancel_order_return { id: c1  fault: { title: \"Not implemented\"  status: 501  errors: [] } }")));

        // The group's verdict is its own code, so assert that rather than the message's wording.
        List<Diagnostic> none = tson.validate(packet("!place_order_return { id: c1 }"));
        assertEquals(List.of(Diagnostic.Code.FIELD_GROUP), none.stream().map(Diagnostic::code).toList(),
                () -> "" + none);
    }

    /**
     * The wire schema is stated to be <b>deterministic in the protocol</b>, so it is derived here rather than
     * read: for every exchange of {@code orders}, {@code orders-wire-1.tn} must close {@code call} over its
     * request and {@code return} over its response and its declared error -- {@code return_plain} only where an
     * exchange declares none. Resolving the file says nothing about any of that, so a hand-written return that
     * could not hold its exchange's error would load clean. This is the check a generator makes unnecessary.
     */
    @Test
    void theWireSchemaIsWhatTheProtocolDetermines() {
        Tson tson = tson();
        // The wire schema does not import the protocol -- it is derived from it -- so both are resolved here.
        assertEquals(List.of(), tson.validateSchema(OrdersExamples.source("orders-1.tn")));
        assertEquals(List.of(), tson.validateSchema(read("orders-wire-1.tn")));
        var protocol = OrdersExamples.applying(tson.schemaRegistry().get(ORDERS_ID).orElseThrow().schema().entries(),
                Protocol.class).get("orders").exchanges();
        var wire = tson.schemaRegistry().get(WIRE_ID).orElseThrow().schema();

        protocol.forEach((name, exchange) -> {
            Map<String, String> call = closedOver(wire, name + "_call", "call");
            assertEquals(exchange.request().map(TypeRef::name).orElse("void"), call.get("request"),
                    name + "_call closes call over the wrong request");

            boolean declaresErrors = !exchange.errors().isEmpty();
            Map<String, String> ret = closedOver(wire, name + "_return", declaresErrors ? "return" : "return_plain");
            assertEquals(exchange.response().map(TypeRef::name).orElse("void"), ret.get("response"),
                    name + "_return closes its parametric record over the wrong response");
            if (exchange.errors().size() == 1) {
                assertEquals(exchange.errors().getFirst().name(), ret.get("error"),
                        name + "_return does not carry the error its exchange declares");
            }
        });
    }

    /**
     * One closed application of a parametric record, as its fields: a declaration naming an application is that
     * application's entry ([TSON-SCHEMA] §8.2), so the application its key carries names the parametric record it
     * closes, and both halves -- which parametric record, over what -- are readable from the resolved schema without
     * parsing a generated name.
     */
    static Map<String, String> closedOver(TsonSchema wire, String entry, String parametric) {
        Top closed = wire.entries().get(entry);
        assertNotNull(closed, "the wire schema declares no '" + entry + "'");
        assertEquals(parametric, wire.applicationOf(entry).orElseThrow().head().name(),
                entry + " closes the wrong parametric record");
        Map<String, String> fields = new LinkedHashMap<>();
        assertInstanceOf(RecordBody.class, closed).fields()
                .forEach((name, field) -> fields.put(name, field.type().text()));
        return fields;
    }
}
