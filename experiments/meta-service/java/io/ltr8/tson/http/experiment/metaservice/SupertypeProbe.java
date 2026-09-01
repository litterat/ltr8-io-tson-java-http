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
 * The mechanism {@code !binding} rides on: a base record {@code endpoint} that {@code operation} and
 * {@code binding} compose ({@code operation => endpoint & signature}), and a verb map typed
 * {@code {http_verb => endpoint}} -- measured on the sketch itself.
 *
 * <p>A subtype's value is admitted at a slot typed by its base (§7.2) and binds to the subtype's record; an
 * untagged value, or a bare {@code !endpoint}, is refused with <em>"'endpoint' has no data of its own to bind --
 * provide an explicit type annotation naming one of its subtypes [operation, binding]"</em>, the resolver knowing
 * the subtypes; a wrong shape under a tag is the closed-record rule; and the base's vocabulary
 * ({@code status: status_code ~ 200}) is inherited and enforced on the subtypes. The abstractness is the
 * binder's: the base binds to a sealed interface, found by name, permitting the two records, and the schema
 * alone would admit a bare endpoint. The same held when the inner types were {@code ~data} constructors deriving
 * at constructor level; a choice {@code (operation | binding)} was measured to work too and gives the weaker
 * guarantee -- the tag mandatory only because two records cannot be told apart, and shared vocabulary composed
 * twice.
 *
 * <p>And what a record buys at the level above: a {@code resource} needs no tag under its path key --
 * {@code "/o" => { POST => … }} -- the slot's type supplying it, where a constructor would have needed
 * {@code !resource}.
 */
class SupertypeProbe {

    static final String DOC_ID = "https://schemas.example.com/2026/34/app/probe-s-1.tn";

    /** Resolves {@code r => !api { "/o" => <resource> }} and hands back the resource, or the problems. */
    static List<Diagnostic> problems(String resource, Object[] resourceOut) {
        String doc = """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text }
              r => !api { "/o" => %s }
            }""".formatted(DOC_ID, Experiment.META_ID, resource);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(DOC_ID, doc);
        Tson tson = Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
        List<Diagnostic> problems = tson.validateSchema(doc);
        if (problems.isEmpty()) {
            Api api = (Api) tson.schemaRegistry().get(DOC_ID).orElseThrow().schema().entries().get("r").body();
            resourceOut[0] = api.resources().get("/o");
        }
        return problems;
    }

    static String only(String resource) {
        List<Diagnostic> problems = problems(resource, new Object[1]);
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    @Test
    void aSubtypesValueIsAdmittedAtItsBaseTypedSlot() {
        Object[] out = new Object[1];
        assertEquals(List.of(), problems("!resource { POST => !binding { method: place_order }"
                + "  GET => !operation { request: order } }", out));
        Resource resource = assertInstanceOf(Resource.class, out[0]);
        assertEquals("place_order", assertInstanceOf(Binding.class, resource.endpoints().get("POST")).method());
        assertEquals("order", assertInstanceOf(Operation.class, resource.endpoints().get("GET"))
                .request().orElseThrow().name());
    }

    /** A resource is a record, so its tag is optional under the path key. */
    @Test
    void aResourceNeedsNoTagUnderItsPathKey() {
        Object[] out = new Object[1];
        assertEquals(List.of(), problems("{ POST => !binding { method: place_order } }", out));
        assertInstanceOf(Binding.class, assertInstanceOf(Resource.class, out[0]).endpoints().get("POST"));
    }

    /** The base cannot be instantiated, and the resolver names the subtypes to choose from. */
    @Test
    void theBaseCannotBeInstantiatedAndTheTagIsMandatory() {
        String untagged = only("!resource { POST => { method: place_order } }");
        assertTrue(untagged.contains("has no data of its own to bind") && untagged.contains("[operation, binding]"),
                untagged);

        String bare = only("!resource { POST => !endpoint { status: 204 } }");
        assertTrue(bare.contains("has no data of its own to bind"), bare);
    }

    @Test
    void aWrongShapeUnderATagIsRefused() {
        String refused = only("!resource { POST => !binding { request: order } }");
        assertTrue(refused.contains("unknown field 'request' on 'binding'"), refused);
    }

    /** The base's vocabulary is inherited and enforced on the subtypes. */
    @Test
    void theBasesConstraintsReachTheSubtypes() {
        String refused = only("!resource { POST => !binding { method: place_order  status: 999 } }");
        assertTrue(refused.contains("'999' is greater than the maximum 599"), refused);
    }
}
