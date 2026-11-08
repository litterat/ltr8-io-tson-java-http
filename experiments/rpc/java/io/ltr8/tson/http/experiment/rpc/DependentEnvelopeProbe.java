package io.ltr8.tson.http.experiment.rpc;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.http.api.OrdersExamples;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependent field ({@code body: request!}, [TSON-SCHEMA] §5.2) as the alternative to closing {@code rpc.tn}'s
 * templates per exchange: one envelope whose payload is read, per packet, against the type a sibling field names.
 *
 * <p>Measured: an envelope keyed by the exchange, {@code { exchange: meta.ref<exchange>  body: exchange! }}, does not
 * resolve. An exchange is a value, not a type, so a reference to exchanges names no type a body could be read
 * against, and the sibling rule refuses it at the declaration. An envelope keyed by the request type, {@code {
 * request: meta.type_ref  body: request! }}, resolves, reads each packet's body against the type it names with the
 * violation pointed into the body, refuses an exchange's qualified name where a type is wanted, and refuses a body
 * written before the sibling that types it. So the form this project could use carries the request's type, and the
 * exchange's address stays a field of its own, as {@code call.method} is; the per-exchange closing in {@code
 * orders-wire-1.tn} is still what ties a request type to the exchange that takes it.
 */
class DependentEnvelopeProbe {

    static final String ENVELOPE_ID = "https://schemas.example.com/2026/39/experiment/rpc/envelope-1.tn";

    static String envelope(String declarations) {
        return """
                !!id:"%s"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s
                }""".formatted(ENVELOPE_ID, OrdersExamples.id("orders-1.tn"), OrdersExamples.id("orders-types-1.tn"),
                declarations);
    }

    static Tson tson(String envelope) {
        Map<String, String> lib = OrdersExamples.library();
        lib.put(ENVELOPE_ID, envelope);
        return OrdersExamples.tson(lib);
    }

    static String packet(String body) {
        return "!!import:\"" + ENVELOPE_ID + "\"\n" + body;
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    /**
     * A reference to values is no sibling: {@code meta.ref<exchange>} holds exchanges, and an exchange names no type.
     * The refusal names the sibling by its synthetic entry rather than as written.
     */
    @Test
    void anEnvelopeKeyedByTheExchangeIsRefused() {
        String schema = envelope("  by_exchange => { exchange: meta.ref<exchange>  body: exchange! }");
        Diagnostic refused = only(tson(schema).validateSchema(schema));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, refused.code());
        assertTrue(refused.message().contains("'by_exchange': field 'body' takes its type from 'exchange', which is"
                + " typed 'reference_type_exchange_false_"), refused.message());
        assertTrue(refused.message().contains("a sibling is typed by a reference whose values name types,"
                + " meta.type_ref, a meta.ref<C> over a constructor, or a refinement of either"), refused.message());
    }

    /** Keyed by the request's type, each packet's body is read against the type its {@code request} names. */
    @Test
    void anEnvelopeKeyedByTheRequestTypeReadsEachBodyAgainstIt() {
        String schema = envelope("  by_type => { request: meta.type_ref  body: request! }");
        Tson tson = tson(schema);
        assertEquals(List.of(), tson.validateSchema(schema));

        String call = packet("!by_type { request: new_order  body: { order: { sku: A-100  quantity: 2 } } }");
        assertEquals(List.of(), tson.validate(call));
        assertEquals(List.of(), tson.validate(packet("!by_type { request: order  body: { sku: A-100  quantity: 2 } }")));
        TsonValue read = tson.treeReader().read(call);
        assertEquals(2, read.at("/body/order/quantity").asInt().orElseThrow());

        Diagnostic typed = only(tson.validate(call.replace("quantity: 2", "quantity: two")));
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, typed.code());
        assertEquals("/body/order/quantity", typed.path().orElseThrow());
    }

    /**
     * An exchange's qualified name is no type, so it is refused at the sibling -- the request type is what a packet
     * names -- and a free sibling is written before the field it types.
     */
    @Test
    void theSiblingNamesATypeAndIsWrittenFirst() {
        String schema = envelope("  by_type => { request: meta.type_ref  body: request! }");
        Tson tson = tson(schema);

        Diagnostic exchange = only(tson.validate(packet(
                "!by_type { request: orders.place_order  body: { sku: A-100  quantity: 2 } }")));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, exchange.code());
        assertEquals("/request", exchange.path().orElseThrow());
        assertTrue(exchange.message().startsWith("'meta.type_ref': 'orders.place_order' is not a member of this kind"
                + " -- expected the name of a type whose value IS-A 'meta.type', one of ["), exchange.message());

        Diagnostic late = only(tson.validate(packet("!by_type { body: { sku: A-100  quantity: 2 }  request: order }")));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, late.code());
        assertEquals("/body", late.path().orElseThrow());
        assertTrue(late.message().contains("field 'body' on 'by_type' is written before 'request', the sibling whose"
                + " value names its type"), late.message());
    }
}
