package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading the API model back out of {@code orders-api-3.tn}'s resolved form — everything a client generator,
 * a router or a documentation tool would have to do.
 *
 * <p><b>This exists to make a cost concrete.</b> The description is expressed with the schema layer's own
 * machinery, so the payload types are real and checked — but they are reachable only by walking resolved type
 * structure: through a synthetic choice entry, through an instantiation reference, to the materialised record.
 * Every consumer of such a description reimplements this traversal.
 *
 * <p>An {@code operation} constructor in the meta layer ({@code UPSTREAM.md} #15) would replace all of it with
 * {@code body() instanceof Operation op} and {@code op.responses()}, because the type names would sit in the
 * Java record as {@code type_ref} values rather than being recovered from the shapes they produced.
 */
class ApiModelExtractionTest {

    private record Response(String status, String body) {
    }

    private record Operation(String method, String path, Optional<String> request, List<Response> responses) {
    }

    private static String sketch(String n) throws Exception {
        return Files.readString(Path.of("..", "sketch", n));
    }

    private static Map<String, TypeDefinition> resolvedEntries() throws Exception {
        Map<String, String> lib = Map.of(
                "https://tson.io/2026/32/ltr8/http/http-api-1.tn", sketch("http-api-1.tn"),
                TsonProblemSchema.ID, TsonProblemSchema.source(),
                "https://schemas.example.com/2026/32/app/order-1.tn", sketch("order-1.tn"),
                "https://schemas.example.com/2026/32/app/orders-errors-1.tn", sketch("orders-errors-1.tn"));
        return Tson.builder().schemaSource(lib::get).build()
                .resolve(sketch("orders-api-3.tn")).schema().entries();
    }

    private static Map<String, RecordField> fields(TypeDefinition entry) {
        Map<String, RecordField> byName = new LinkedHashMap<>();
        ((RecordBody) entry.body()).fields().forEach(f -> byName.put(f.name(), f));
        return byName;
    }

    /**
     * One response, from the name of its entry. <b>Hop 1</b>: the entry is a {@link Reference}, because
     * {@code order_created => response<order, 201>} is an application. <b>Hop 2</b>: its target is the
     * materialised instantiation, under a generated name. Only then are the status and body readable.
     */
    private static Response response(Map<String, TypeDefinition> entries, String name) {
        TypeDefinition entry = entries.get(name);
        if (entry.body() instanceof Reference reference) {
            entry = entries.get(reference.target().name());
        }
        Map<String, RecordField> byName = fields(entry);
        return new Response(byName.get("status").value().orElseThrow().text(),
                byName.get("body").type().name());
    }

    /**
     * An operation's responses. <b>The branch is the point</b>: an operation with several responses has a
     * synthetic {@code choice_…} entry to look up and walk, and one with a single response has a direct
     * reference instead — two structurally different resolved forms for the same idea, so a consumer needs
     * both paths.
     */
    private static List<Response> responses(Map<String, TypeDefinition> entries, RecordField field) {
        TypeDefinition target = entries.get(field.type().name());
        if (target != null && target.body() instanceof ChoiceBody choice) {
            List<Response> all = new ArrayList<>();
            choice.variants().forEach(variant -> all.add(response(entries, variant.name())));
            return all;
        }
        return List.of(response(entries, field.type().name()));
    }

    private static Operation operation(Map<String, TypeDefinition> entries, TypeDefinition entry) {
        Map<String, RecordField> byName = fields(entry);
        return new Operation(
                byName.get("method").value().orElseThrow().text(),
                byName.get("path").value().orElseThrow().text(),
                Optional.ofNullable(byName.get("request")).map(f -> f.type().name()),
                responses(entries, byName.get("response")));
    }

    @Test
    void theWholeApiIsRecoverableButOnlyByWalkingTypeStructure() throws Exception {
        Map<String, TypeDefinition> entries = resolvedEntries();

        // An operation is found by its supertype -- there is no other marker.
        Map<String, Operation> api = new LinkedHashMap<>();
        entries.forEach((name, entry) -> {
            if (entry.supertypes().contains("operation")) {
                api.put(name, operation(entries, entry));
            }
        });

        assertEquals(List.of("create_order", "list_orders", "get_schema"), List.copyOf(api.keySet()));

        assertEquals(new Operation("POST", "/orders", Optional.of("order"),
                        List.of(new Response("201", "order"),
                                new Response("400", "problem"),
                                new Response("404", "sku_not_found"))),
                api.get("create_order"));

        // The single-response case, which took the other branch.
        Operation list = api.get("list_orders");
        assertEquals("GET", list.method());
        assertEquals(1, list.responses().size());
        assertEquals("200", list.responses().getFirst().status());
        // Its body is the synthetic entry page<order> materialised to.
        org.junit.jupiter.api.Assertions.assertTrue(
                list.responses().getFirst().body().contains("page"), list.responses().getFirst().body());
    }
}
