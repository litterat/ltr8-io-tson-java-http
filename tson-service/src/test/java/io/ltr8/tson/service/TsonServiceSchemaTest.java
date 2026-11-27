package io.ltr8.tson.service;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.service.schema.Exchange;
import io.ltr8.tson.service.schema.Protocol;
import io.ltr8.tson.service.schema.Signature;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code service.tn} on its own: it declares the identity it is served at, resolves from what {@link
 * TsonServiceSchema#publishedById()} holds, binds the protocol records and nothing else, and leaves every HTTP name
 * out of a description importing it alone. The http layer importing it is {@code TsonHttpSchemaTest}'s.
 */
class TsonServiceSchemaTest {

    private static final String API_ID = "https://schemas.example.com/2026/40/app/orders-protocol-1.tn";

    /** A protocol-only description: {@code service.tn} and core, a protocol, and {@code extra} beside it. */
    private static String api(String extra) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  order_ref => { id: text }
                  order     => { id: text  sku: text }
                  orders => !service.protocol {
                    exchanges: { @service.safe @service.summary:"Fetch an order." get_order => { request: order_ref
                                                                                                  response: order } }
                  }
                %s}""".formatted(API_ID, TsonServiceSchema.ID, extra);
    }

    private static Tson tson(String api) {
        Map<String, String> library = new LinkedHashMap<>(TsonServiceSchema.publishedById());
        library.put(API_ID, api);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library)))
                .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindings())));
    }

    /** The protocol layer is a document a server publishes, so its own identity has to be what it says. */
    @Test
    void theProtocolSchemaDeclaresTheIdentityItIsServedAt() {
        assertTrue(TsonServiceSchema.source().startsWith("!!id:\"" + TsonServiceSchema.ID + "\""),
                TsonServiceSchema.source().lines().findFirst().orElse(""));
        assertEquals(List.of(TsonServiceSchema.source()), TsonServiceSchema.publishedSources());
        assertEquals(Map.of(TsonServiceSchema.ID, TsonServiceSchema.source()), TsonServiceSchema.publishedById());
    }

    /**
     * <b>{@code service.tn} resolves on its own</b>, its namespace holding the protocol side under {@code service} and
     * no member of it beside those, nor any name under {@code http}; and a protocol-only description importing it
     * reads a protocol whose exchange carries its facts.
     */
    @Test
    void theProtocolSchemaResolvesOnItsOwn() {
        String api = api("");
        Tson tson = tson(api);
        assertEquals(List.of(), tson.validateSchema(TsonServiceSchema.source()));
        Set<String> names = tson.schemaRegistry().get(TsonServiceSchema.ID).orElseThrow().schema().entries().keySet();
        assertEquals(Set.of("service.signature", "service.exchange", "service.exchanges", "service.protocol",
                "service.exchange_name", "service.summary", "service.safe", "service.idempotent", "service.destructive"),
                names.stream().filter(name -> name.startsWith("service.")).collect(Collectors.toSet()));
        assertTrue(names.contains("service"), "" + names);
        assertTrue(names.stream().noneMatch(name -> name.startsWith("http")), "" + names);

        assertEquals(List.of(), tson.validateSchema(api));
        Protocol orders = assertInstanceOf(Protocol.class, assertInstanceOf(ValueEntry.class,
                tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().get("orders")).value());
        assertTrue(orders.exchanges().getAnnotations("get_order").has("service.safe"));
    }

    /**
     * <b>An HTTP tag does not resolve through {@code service.tn}</b>: a description importing it alone and applying
     * {@code http.projection} is refused naming the tag, since nothing in its closure declares it.
     */
    @Test
    void anHttpTagDoesNotResolveThroughTheProtocolSchema() {
        String api = api("  orders_http => !http.projection { resources: {} }\n");
        List<Diagnostic> problems = tson(api).validateSchema(api);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), problems.stream().map(Diagnostic::code).toList(),
                () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("http.projection"), problems.getFirst().message());
    }

    /**
     * The protocol records bind by their qualified names and nothing else does; an application binding one of them is
     * refused, since within one closure a name means one thing.
     */
    @Test
    void theProtocolRecordsBindAndNothingElse() {
        assertEquals(Map.of("service.protocol", Protocol.class, "service.exchange", Exchange.class,
                "service.signature", Signature.class), TsonServiceSchema.bindings());
        assertEquals("'service.exchange' is bound by the application and by service.tn's vocabulary; within one "
                        + "closure a name means one thing",
                assertThrows(IllegalArgumentException.class, () -> TsonServiceSchema.bindingsWith(
                        Map.of("service.exchange", String.class))).getMessage());
    }
}
