package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How to separate method names from {@code type_name}: declare a naming ROLE for the borrowed namespace, as the
 * kernel declares {@code type_name}, {@code field_name} and {@code param_name} -- all {@code => identifier}.
 *
 * <p>Measured: a role declared in the meta layer ({@code method_name => identifier}) enforces the identifier
 * grammar at a map key and <em>names itself</em> in the refusal ("'method_name': 'place order': U+0020 …"), where
 * a {@code text} key accepts anything. And a limit: [TSON-DATA] §8.2's name hygiene does not reach map keys under
 * any role, {@code type_name} included -- confusable and mixed-script method names are admitted. An interface's
 * method map is a naming scope in every sense §8.2 means, and nothing checks it today; recorded in the README.
 * The hygiene assertions are written to fail the day that changes.
 */
class NameRoleProbe {

    static final String META_ID = "https://tson.io/2026/34/ltr8/http/meta-probe-n.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/34/app/probe-n-1.tn";

    static final String META = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta-kernel.tn"
        !!import:"https://tson.io/2026/34/m/meta.tn"
        {
          signature   => { request: type_ref?  response: type_ref?  errors: [type_ref]? }
          method      => ~data & signature
          method_name => identifier
          by_type_name   => ~data & { methods: {type_name => method} }
          by_method_name => ~data & { methods: {method_name => method} }
          by_text        => ~data & { methods: {text => method} }
        }""".formatted(META_ID);

    static List<Diagnostic> problems(String entries) {
        String doc = """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text }
            %s
            }""".formatted(DOC_ID, META_ID, entries);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, META);
        lib.put(DOC_ID, doc);
        Tson tson = Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
        List<Diagnostic> meta = tson.validateSchema(META);
        assertEquals(List.of(), meta, () -> "the probe meta itself: " + meta);
        return tson.validateSchema(doc);
    }

    static String only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    /** A role of its own enforces the grammar and says which namespace refused the name. */
    @Test
    void aMethodNameRoleEnforcesTheIdentifierGrammarUnderItsOwnName() {
        String refused = only(problems("  x => !by_method_name { \"place order\" => { request: order } }"));
        assertTrue(refused.contains("'method_name': 'place order'") && refused.contains("cannot appear in an identifier"),
                refused);

        String digit = only(problems("  x => !by_method_name { \"1st\" => { request: order } }"));
        assertTrue(digit.contains("'method_name': '1st'") && digit.contains("cannot start an identifier"), digit);
    }

    /** {@code type_name} enforces the same grammar -- and misnames the namespace doing it. */
    @Test
    void typeNameEnforcesTheGrammarButNamesTheWrongNamespace() {
        String refused = only(problems("  x => !by_type_name { \"place order\" => { request: order } }"));
        assertTrue(refused.contains("'type_name': 'place order'"), refused);
    }

    /** A {@code text} key is not a name: anything goes. */
    @Test
    void aTextKeyAcceptsAnything() {
        assertEquals(List.of(), problems(
                "  x => !by_text { \"place order\" => { request: order }  \"1st\" => { request: order } }"));
    }

    /**
     * <b>Open:</b> §8.2's hygiene does not reach map keys under any role. Two confusable method names in one
     * interface, or a mixed-script one, are admitted -- where the same names as two fields of one record, or two
     * declarations of one schema, would be refused under the default identifier policy. Asserted as it is, so
     * this fails the day the implementation or the spec extends the naming-scope rule to identifier-keyed maps.
     */
    @Test
    void nameHygieneDoesNotReachMapKeysYet() {
        for (String ctor : List.of("by_type_name", "by_method_name")) {
            assertEquals(List.of(),
                    problems("  x => !" + ctor + " { admin => { request: order }  аdmin => { request: order } }"),
                    ctor + " confusables");
            assertEquals(List.of(), problems("  x => !" + ctor + " { pаy => { request: order } }"),
                    ctor + " mixed script");
        }
    }
}
