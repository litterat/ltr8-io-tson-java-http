package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Reproducer for a downstream report: a governed schema referring to its META layer's declarations.
 *
 * <p>Three of the four cases agree — a meta-layer declaration is not in the governed schema's type namespace,
 * and the reference path says so with "unresolved reference". The fourth, an <em>application</em>, reports
 * that the arguments are missing when they are written.
 */
class MetaLayerLookupTest {

    private static final String META = "https://example.test/meta-x.tn";
    private static final String GOVERNED = "https://example.test/g-1.tn";

    private static final String META_SOURCE = """
            !!id:"https://example.test/meta-x.tn"
            !!meta:"https://tson.io/2026/33/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/33/m/meta.tn"
            {
              scalar => !integer ^ { min: 100  max: 599 }
              plain  => { a: text }
              tmpl   => <T> { v: T }
              ctor   => ~data & { a: text }
            }
            """;

    private static String governed(String declaration) {
        return """
                !!id:"https://example.test/g-1.tn"
                !!meta:"https://example.test/meta-x.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                %s
                }
                """.formatted(declaration);
    }

    private static void show(String label, String declaration) {
        String doc = governed(declaration);
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(META, META_SOURCE);
        lib.put(GOVERNED, doc);
        TsonSchemaSource source = lib::get;
        try {
            Tson.builder().schemaSource(source).build().resolve(doc);
            System.out.println(label + "  ->  RESOLVES");
        } catch (RuntimeException e) {
            String m = e.getMessage();
            System.out.println(label + "  ->  " + (m == null ? e.getClass().getSimpleName() : m));
        }
    }

    @Test
    void whatAGovernedSchemaSeesOfItsMetaLayer() {
        show("atom, referenced      ", "  x => { s: scalar }");
        show("record, referenced    ", "  x => { s: plain }");
        show("constructor, as a type", "  x => { s: ctor }");
        show("template, unapplied   ", "  x => { s: tmpl }");
        show("template, APPLIED     ", "  x => tmpl<text>");
        show("control: local template", "  local => <T> { v: T }\n  x => local<text>");
    }
}
