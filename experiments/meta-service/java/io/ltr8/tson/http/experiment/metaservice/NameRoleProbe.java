package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
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
 * a {@code text} key accepts anything. [TSON-DATA] §8.2's name hygiene reaches an identifier-keyed map in a data
 * document and in a schema governed by a meta layer alike, under any role, {@code type_name} included -- the second
 * being where an interface's methods are written.
 */
class NameRoleProbe {

    static final String META_ID = "https://tson.io/2026/37/io/ltr8/http/meta-probe-n.tn";
    static final String DOC_ID = "https://schemas.example.com/2026/37/app/probe-n-1.tn";

    static final String META = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/37/m/meta-kernel.tn"
        !!import:"https://tson.io/2026/37/m/meta.tn"
        {
          signature   => { request?: type_ref  response?: type_ref  errors?: [type_ref] }
          method      => data & signature
          method_name => identifier
          by_type_name   => data & { methods: {type_name => method} }
          by_method_name => data & { methods: {method_name => method} }
          by_text        => data & { methods: {text => method} }
        }""".formatted(META_ID);

    static List<Diagnostic> problems(String entries) {
        String doc = """
            !!id:"%s"
            !!meta:"%s"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              order => { sku: text }
            %s
            }""".formatted(DOC_ID, META_ID, entries);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META_ID, META);
        lib.put(DOC_ID, doc);
        Tson tson = Tson.of(Experiment.bindVocabulary(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib)))));
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
     * In a <em>data</em> document, an identifier-keyed map's keys are names: each meets the per-name rules and
     * the key set is one look-alike scope ([TSON-SCHEMA] §11.4), under a role over {@code identifier} as under
     * {@code identifier} itself. The interface below is data, so a method map written this way is checked.
     */
    @Test
    void aDataDocumentsIdentifierKeysAreNames() {
        String schemaId = "https://schemas.example.com/2026/37/app/probe-n-data-1.tn";
        Map<String, String> lib = Map.of(schemaId, """
            !!id:"%s"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              identifier  => !identifier_type { continue_add: "-" }
              method_name => identifier
              iface       => { methods: {method_name => text} }
            }""".formatted(schemaId));
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(lib))));
        String head = "!!schema:\"" + schemaId + "\"\n";

        // The set rule needs two names the per-name rules admit, or the script rule refuses the second first:
        // `раѕѕ` is wholly Cyrillic, so it is single-script and reads alike with the Latin `pass`.
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                tson.validate(head + "!iface { methods: { pass => a  раѕѕ => b } }").stream()
                        .map(Diagnostic::code).toList());
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                tson.validate(head + "!iface { methods: { pаy => a } }").stream().map(Diagnostic::code).toList());
    }

    /**
     * In a <em>schema</em> governed by a meta layer, the same map's keys are names too -- which is where an
     * interface's methods are written, in a schema governed by the meta layer that declares {@code interface}. Two
     * confusable method names, or a mixed-script one, are refused here as they are as two fields of one record, two
     * declarations of one schema, or two keys of the same map in a data document (above).
     */
    @Test
    void aGovernedSchemasIdentifierKeysAreNamesToo() {
        for (String ctor : List.of("by_type_name", "by_method_name")) {
            assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                    problems("  x => !" + ctor + " { pass => { request: order }  раѕѕ => { request: order } }")
                            .stream().map(Diagnostic::code).toList(),
                    ctor + " confusables");
            assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                    problems("  x => !" + ctor + " { pаy => { request: order } }").stream().map(Diagnostic::code)
                            .toList(),
                    ctor + " mixed script");
        }
    }
}
