package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonProblemSchema;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rpc-1.tn} -- the wire form of an interface, {@code call} and {@code return} as templates -- closed per
 * method by a wire schema ({@code examples/orders-wire-1.tn}), so that a packet is a fully typed document.
 *
 * <p>Measured: both schemas resolve, a choice of errors included as a template argument; a packet's request is
 * checked against the interface's own types ({@code quantity: two} is an {@code int32} violation at
 * {@code /request/order/quantity}); an error arm carries the declared error with its status pin enforced, and
 * where a method declares several the value takes the error's tag; a return has exactly one outcome; and the
 * envelope reads, {@code deadline} as a duration. Nothing here needs {@code unknown} or an in-place
 * {@code !!schema} -- the earlier design did, and the {@code unknown} reader gap now gates only the agent's
 * constants.
 */
class RpcProbe {

    static final String RPC_ID = "https://tson.io/2026/35/ltr8/http/rpc-1.tn";
    static final String EXAMPLES = "https://schemas.example.com/2026/35/experiment/meta-service/";
    static final String WIRE_ID = EXAMPLES + "orders-wire-1.tn";
    static final String ORDERS_ID = EXAMPLES + "orders-1.tn";

    static Path dir() {
        return Path.of(System.getProperty("experiments.dir", "../experiments")).resolve("meta-service");
    }

    static String read(String rel) {
        try {
            return Files.readString(dir().resolve(rel));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Tson tson() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(RPC_ID, read("rpc-1.tn"));
        lib.put(TsonProblemSchema.ID, TsonProblemSchema.source());
        lib.put(EXAMPLES + "orders-types-1.tn", read("examples/orders-types-1.tn"));
        lib.put(EXAMPLES + "orders-errors-1.tn", read("examples/orders-errors-1.tn"));
        lib.put(WIRE_ID, read("examples/orders-wire-1.tn"));
        return Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib)).build();
    }

    static String packet(String body) {
        return "!!schema:\"" + WIRE_ID + "\"\n" + body;
    }

    @Test
    void theRpcAndWireSchemasResolve() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(read("rpc-1.tn")));
        assertEquals(List.of(), tson.validateSchema(read("examples/orders-wire-1.tn")));
    }

    /** A call is a fully typed document: its request is the interface's own record, checked by the resolver. */
    @Test
    void aCallIsTypedByTheInterfacesOwnTypes() {
        Tson tson = tson();
        String call = packet("""
            !place_order_call {
              interface: "%s"  method: place_order  id: c1  deadline: PT5S
              request: { order: { sku: A-100  quantity: 2 }  idempotency_key: k1 }
            }""".formatted(ORDERS_ID));
        assertEquals(List.of(), tson.validate(call));

        TsonValue value = tson.treeReader().read(call);
        assertEquals(ORDERS_ID, value.get("interface").as(java.net.URI.class).orElseThrow().toString());
        assertEquals("c1", value.get("id").asString().orElseThrow());
        assertTrue(value.get("deadline").as(Object.class).isPresent());
        assertEquals(2, value.at("/request/order/quantity").asInt().orElseThrow());

        List<Diagnostic> problems = tson.validate(call.replace("quantity: 2", "quantity: two"));
        assertEquals(1, problems.size(), () -> "" + problems);
        assertEquals("/request/order/quantity", problems.getFirst().path().orElseThrow());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    /** The error arm is the declared error, its fixed status enforced; the response arm the declared response. */
    @Test
    void aReturnCarriesTheDeclaredResponseOrError() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(packet(
                "!place_order_return { id: c1  response: { sku: A-100  quantity: 4 } }")));
        assertEquals(List.of(), tson.validate(packet("""
            !place_order_return { id: c1
              error: { type: "https://ltr8.io/2026/35/http/problems/sku-not-found"  title: "No such SKU"
                       status: 404  sku: A-100  errors: [] } }""")));

        List<Diagnostic> wrongStatus = tson.validate(packet(
                "!place_order_return { id: c1  error: { title: \"x\"  status: 400  sku: A-100  errors: [] } }"));
        assertEquals(1, wrongStatus.size(), () -> "" + wrongStatus);
        assertEquals(Diagnostic.Code.FIELD_FIXED, wrongStatus.getFirst().code());
        assertEquals("/error/status", wrongStatus.getFirst().path().orElseThrow());
    }

    /** Exactly one outcome; a method with no declared errors closes `return_plain`, a `void` response included. */
    @Test
    void aReturnHasExactlyOneOutcome() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(packet("!list_orders_return { id: c1  response: { items: [] } }")));
        assertEquals(List.of(), tson.validate(packet(
                "!cancel_order_return { id: c1  fault: { title: \"Not implemented\"  status: 501  errors: [] } }")));

        List<Diagnostic> none = tson.validate(packet("!place_order_return { id: c1 }"));
        assertEquals(1, none.size(), () -> "" + none);
        assertTrue(none.getFirst().message().contains("exactly one of (response | error | fault)"),
                none.getFirst().message());
    }
}
