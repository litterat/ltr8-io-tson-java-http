package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanism {@code !binding} rides on: a base constructor {@code endpoint} that {@code operation} and
 * {@code binding} derive from at constructor level (§4.2's level discipline admits {@code ~endpoint & { … }}),
 * and a verb map typed {@code {http_verb => endpoint}} -- measured on the sketch itself.
 *
 * <p>An instance of a derived constructor is admitted at a slot typed by its base and binds to the derived
 * record; the base is <em>abstract</em> for free -- an untagged value, or a bare {@code !endpoint}, is refused
 * with <em>"'endpoint' has no data of its own to bind -- provide an explicit type annotation naming one of its
 * subtypes [operation, binding]"</em>, the resolver knowing the subtypes; a wrong shape under a tag is the
 * closed-record rule; and the base's vocabulary ({@code status: status_code ~ 200}) is inherited and enforced
 * on the derived constructors. On the Java side the base binds to a sealed interface, found by name, permitting
 * the derived records. A choice {@code (operation | binding)} was measured to work as well and gives the weaker
 * guarantee: the tag is mandatory only because two records cannot be told apart, and shared vocabulary would
 * be composed twice.
 */
class SupertypeProbe {

    static final String DOC_ID = "https://schemas.example.com/2026/34/app/probe-s-1.tn";

    static List<Diagnostic> problems(String entries, Object[] bodyOut) {
        String doc = """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text }
            %s
            }""".formatted(DOC_ID, Experiment.META_ID, entries);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(DOC_ID, doc);
        Tson tson = Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
        List<Diagnostic> problems = tson.validateSchema(doc);
        if (problems.isEmpty()) {
            bodyOut[0] = tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("r").body();
        }
        return problems;
    }

    static String only(String entries) {
        List<Diagnostic> problems = problems(entries, new Object[1]);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    @Test
    void aDerivedConstructorsInstanceIsAdmittedAtItsBaseTypedSlot() {
        Object[] body = new Object[1];
        assertEquals(List.of(), problems("  r => !resource { POST => !binding { method: place_order }"
                + "  GET => !operation { request: order } }", body));
        Resource resource = assertInstanceOf(Resource.class, body[0]);
        assertEquals("place_order", assertInstanceOf(Binding.class, resource.endpoints().get("POST")).method());
        assertEquals("order", assertInstanceOf(Operation.class, resource.endpoints().get("GET"))
                .request().orElseThrow().name());
    }

    /** The base is abstract for free, and the resolver names the subtypes to choose from. */
    @Test
    void theBaseCannotBeInstantiatedAndTheTagIsMandatory() {
        String untagged = only("  r => !resource { POST => { method: place_order } }");
        assertTrue(untagged.contains("has no data of its own to bind") && untagged.contains("[operation, binding]"),
                untagged);

        String bare = only("  r => !resource { POST => !endpoint { status: 204 } }");
        assertTrue(bare.contains("has no data of its own to bind"), bare);
    }

    @Test
    void aWrongShapeUnderATagIsRefused() {
        String refused = only("  r => !resource { POST => !binding { request: order } }");
        assertTrue(refused.contains("unknown field 'request' on 'binding'"), refused);
    }

    /** The base's vocabulary is inherited and enforced on the derived constructors. */
    @Test
    void theBasesConstraintsReachTheDerivedConstructors() {
        String refused = only("  r => !resource { POST => !binding { method: place_order  status: 999 } }");
        assertTrue(refused.contains("'999' is greater than the maximum 599"), refused);
    }
}
