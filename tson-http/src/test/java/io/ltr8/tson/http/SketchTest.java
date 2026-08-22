package io.ltr8.tson.http;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.Tson;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sketch/} schemas, held to what {@code sketch/README.md} claims about them.
 *
 * <p>Two designs for an API description made of types rather than data about types. One works today and one
 * is blocked; this asserts which is which, so a fix upstream shows up as a <em>failing</em> test rather than as
 * nothing happening.
 */
class SketchTest {

    private static String sketch(String name) throws Exception {
        return Files.readString(Path.of("..", "sketch", name));
    }

    private static Optional<String> value(Annotations annotations, String name) {
        return annotations.values().stream().filter(a -> a.name().equals(name)).findFirst()
                .flatMap(Annotation::value).map(Object::toString);
    }

    // ── the design that works: annotations in a meta layer, operations as `top &` entries ──

    private static Tson resolved() throws Exception {
        Map<String, String> lib = Map.of("https://tson.io/2026/32/ltr8/http/meta-http-2.tn",
                sketch("meta-http-2.tn"));
        Tson tson = Tson.builder().schemaSource(lib::get).build();
        tson.resolve(sketch("orders-api-2.tn"));
        return tson;
    }

    /**
     * The whole claim: an API model can be read back out of an ordinary resolved schema, with every payload a
     * real type reference rather than a string.
     */
    @Test
    void theApiModelIsReadableFromTheResolvedSchema() throws Exception {
        var entries = resolved().schemaRegistry()
                .get("https://schemas.example.com/2026/32/app/orders-api-2.tn").orElseThrow()
                .schema().entries();

        TypeDefinition create = entries.get("create_order");
        assertEquals(Optional.of("POST"), value(create.annotations(), "method"));
        assertEquals(Optional.of("/orders"), value(create.annotations(), "path"));
        assertEquals(List.of("top"), create.supertypes(), "no data value can be an operation");

        List<String> responses = new ArrayList<>();
        RecordBody body = (RecordBody) create.body();
        body.fields().forEach(f -> value(f.annotations(), "status")
                .ifPresent(status -> responses.add(status + " -> " + f.type().name())));

        assertEquals(List.of("201 -> order", "400 -> problem", "404 -> sku_not_found"), responses);

        // The request is a resolved reference, not a name that happens to look like one.
        assertEquals("order", body.fields().stream().filter(f -> f.name().equals("request"))
                .findFirst().orElseThrow().type().name());
    }

    /** And the property a data-shaped description cannot have: a payload type that does not exist is refused. */
    @Test
    void aResponseNamingATypeThatDoesNotExistFailsToLoad() throws Exception {
        String broken = sketch("orders-api-2.tn")
                .replace("@status:404 no_such_sku: sku_not_found", "@status:404 no_such_sku: sku_not_fund");
        Map<String, String> lib = Map.of("https://tson.io/2026/32/ltr8/http/meta-http-2.tn",
                sketch("meta-http-2.tn"));

        var problems = Tson.builder().schemaSource(lib::get).build().validateSchema(broken);

        assertTrue(problems.stream().anyMatch(d -> d.message().contains("sku_not_fund")),
                () -> "expected an unresolved-reference error, got " + problems);
    }

    // ── the plainest design: an ordinary schema, no annotations, no top, no meta layer ──

    /**
     * {@code orders-api-3.tn} needs nothing but the ordinary header — {@code meta.tn} governing,
     * {@code core.tn} imported. Metadata is carried by FIXED fields, which survive into resolver output with
     * their values, where a locally declared annotation's value would have been dropped (#12).
     */
    @Test
    void fixedFieldsCarryTheMetadataInAnOrdinarySchema() throws Exception {
        Tson tson = Tson.builder().schemaSource(u -> null).build();
        var entries = tson.resolve(sketch("orders-api-3.tn")).schema().entries();

        TypeDefinition create = entries.get("create_order");
        assertTrue(create.supertypes().contains("operation"),
                "an operation is found by its supertype, not by a naming convention");

        Map<String, RecordField> fields = new LinkedHashMap<>();
        ((RecordBody) create.body()).fields().forEach(f -> fields.put(f.name(), f));

        assertEquals(FieldState.REQUIRED_FIXED, fields.get("method").state());
        assertEquals("POST", fields.get("method").value().orElseThrow().text());
        assertEquals("/orders", fields.get("path").value().orElseThrow().text());

        // The payloads are resolved references, which is the whole point.
        assertEquals("order", fields.get("request").type().name());
        assertTrue(fields.get("response").type().name().startsWith("choice_"),
                fields.get("response").type().name());
    }

    /** And the same property: a payload type that does not exist is refused. */
    @Test
    void anOrdinarySchemaStillChecksItsPayloadTypes() throws Exception {
        String broken = sketch("orders-api-3.tn").replace("body: sku_not_found", "body: sku_not_fund");
        var problems = Tson.builder().schemaSource(u -> null).build().validateSchema(broken);
        assertTrue(problems.stream().anyMatch(d -> d.message().contains("sku_not_fund")),
                () -> "expected an unresolved-reference error, got " + problems);
    }

    // ── the design that is blocked: an `operation` type constructor in a meta layer ──

    /** meta-http-1.tn resolves: the constructor itself is expressible today. */
    @Test
    void theConstructorMetaLayerResolves() throws Exception {
        assertEquals(List.of(), Tson.builder().build().validateSchema(sketch("meta-http-1.tn")));
    }

    /**
     * But a schema governed by it cannot apply the constructor. {@code UnsupportedOperationException} is this
     * project's classification for <em>not implemented</em>, which is what makes this a gap rather than a
     * defect in the sketch. When it lands, this test fails and the README needs updating.
     */
    @Test
    void applyingAUserDefinedConstructorIsNotImplemented() throws Exception {
        Map<String, String> lib = Map.of(
                "https://tson.io/2026/32/ltr8/http/meta-http-1.tn", sketch("meta-http-1.tn"),
                "https://schemas.example.com/2026/32/app/order-1.tn", """
                        !!id:"https://schemas.example.com/2026/32/app/order-1.tn"
                        !!meta:"https://tson.io/2026/32/m/meta.tn"
                        !!import:"https://tson.io/2026/32/m/core.tn"
                        { order => { sku: text } }""");

        UnsupportedOperationException gap = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> Tson.builder().schemaSource(lib::get).build().validateSchema("""
                        !!id:"https://schemas.example.com/2026/32/app/probe-1.tn"
                        !!meta:"https://tson.io/2026/32/ltr8/http/meta-http-1.tn"
                        !!import:"https://schemas.example.com/2026/32/app/order-1.tn"
                        { create => !operation { method: POST  path: "/o"  parameters: []  responses: [] } }"""));

        assertTrue(gap.getMessage().contains("is not a constructor"), gap.getMessage());
    }
}
