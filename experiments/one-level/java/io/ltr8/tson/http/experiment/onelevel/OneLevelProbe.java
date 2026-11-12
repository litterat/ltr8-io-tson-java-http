package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the resolver does at each place the levels used to show, so {@code experiments/one-level/README.md} states
 * measurements rather than readings of the spec. Every test pins Revision 39's outcome; one failing after a library
 * change means the ground the note stands on has moved.
 *
 * <p>Two halves. Within schemas: who may declare a constructor, who may apply one, and whether a schema can import
 * itself. Across the data/schema line: whether a data document can hold a type's name or a value's name, and whether
 * either is judged. Revision 39 removed the ladder (one namespace per schema, {@code !!import} the one directive), so
 * most of the first half now pins that a Revision 38 refusal is gone.
 */
class OneLevelProbe {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String ID = "https://schemas.example.com/2026/40/one-level/s-1.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/one-level/layer-1.tn";

    /** The probe's vocabulary: a record, and the namespace type a schema importing it applies to hold it. */
    static final String STEPS = "step => { takes: meta.type_ref }  "
            + "steps => !meta.namespace { key_type: meta.identifier  value_type: step }";

    /** A layer declaring the probe's one namespace type, imported by the schema that holds its values. */
    static final String LAYER = head(LAYER_ID, META) + "!schema { " + STEPS + " }";

    static String head(String id, String... imports) {
        StringBuilder head = new StringBuilder("!!id:\"" + id + "\"\n");
        for (String imported : imports) {
            head.append("!!import:\"").append(imported).append("\"\n");
        }
        return head.toString();
    }

    static Tson tson(Map<String, String> documents) {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withDataBindContext(
                    AtomContext.defaultContext().toBuilder().nameBinder(new DataNameBinder.DefaultDataNameBinder(
                        Set.of("io.ltr8.tson.http.experiment.onelevel"), Map.of())).build()));
    }

    /** Resolves {@code schema} (served at {@link #ID}) beside {@link #LAYER} and returns what was reported. */
    static List<Diagnostic> resolve(String schema) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        documents.put(ID, schema);
        return tson(documents).validateSchema(schema);
    }

    static String onlyMessage(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst().message();
    }

    // ── within schemas ──────────────────────────────────────────────────────────────────────────────────────────

    /** The two-document shape: the namespace type in a layer, its value in a schema importing it. */
    @Test
    void aConstructorDeclaredInALayerIsAppliedOneDocumentDown() {
        assertEquals(List.of(), resolve(LAYER));
        assertEquals(List.of(), resolve(head(ID, LAYER_ID, CORE)
                + "!schema { order => { sku: text }  s => !steps { one => { takes: order } } }"));
    }

    /**
     * A schema declaring a namespace type holds a value of it beside the declaration: the value waits on the type
     * being ready. Revision 38 refused it, a schema's {@code !} targets coming from the meta governing it.
     */
    @Test
    void aSchemaAppliesANamespaceTypeItDeclaresItself() {
        assertEquals(List.of(), resolve(head(ID, META)
                + "!schema { order => { sku: text }  STEPS  s => !steps { one => { takes: order } } }"
                .replace("STEPS", STEPS)));
    }

    /**
     * A schema importing core sees the kernel's machinery and declares a namespace type with it: the kernel reaches
     * it through core's own imports. Revision 38 refused it, the kernel's names being out of an ordinary schema's
     * reach.
     */
    @Test
    void aSchemaImportingCoreDeclaresANamespaceType() {
        assertEquals(List.of(), resolve(head(ID, CORE) + "!schema { " + STEPS + " }"));
    }

    /**
     * Any schema imports the kernel, with core or without: an import crosses no level. Revision 38 refused both, an
     * import standing at the importer's governing level.
     */
    @Test
    void anySchemaImportsTheKernel() {
        for (String schema : List.of(head(ID, CORE, META) + "!schema { order => { sku: text } }",
                head(ID, META) + "!schema { " + STEPS + " }")) {
            assertEquals(List.of(), resolve(schema), schema);
        }
    }

    /**
     * A schema importing itself is an import cycle, even one declaring only types. The kernel imports nothing, so
     * no self-reference is left to close by pre-loading.
     */
    @Test
    void aSchemaImportingItselfIsAnImportCycle() {
        String message = onlyMessage(resolve(head(ID, ID, CORE) + "!schema { order => { sku: text } }"));
        assertTrue(message.contains("is part of an import cycle"), message);
    }

    /** An instance names a type declared beside it, and a kind slot refuses the instance. */
    @Test
    void aKindSlotRefusesAnInstanceDeclaredBesideIt() {
        String message = onlyMessage(resolve(head(ID, LAYER_ID, CORE)
                + "!schema { order => { sku: text }  s => !steps { one => { takes: order } }"
                + "  t => !steps { one => { takes: s } } }"));
        assertTrue(message.contains("'s' is a value of the type 'steps' -- a value and not a type, so nothing can be "
                + "typed by it"), message);
    }

    // ── across the data/schema line ─────────────────────────────────────────────────────────────────────────────

    /** A schema types a field by a kind through the kernel's {@code meta.ref}. */
    static final String NAMING = head(ID, CORE)
            + "!schema { order => { sku: text }  pick => { t: meta.ref<meta.type> } }";

    /** The same, typing a field by a reference, the kernel's {@code meta.ref}. */
    static final String REFERRING = head(ID, CORE)
            + "!schema { order => { sku: text }  rpick => { m: meta.ref<order> } }";

    static List<Diagnostic> readUnderNaming(String body) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(ID, NAMING);
        Tson tson = tson(documents);
        assertEquals(List.of(), tson.validateSchema(NAMING));
        return tson.validate("!!import:\"" + ID + "\"\n" + body);
    }

    /**
     * <b>A data document can hold a type's name, and it is judged.</b> At a kind-typed field the name must be a member
     * of the kind over the reading document's closure: a declared type is admitted, and an undeclared name is
     * refused. A constructor is admitted too, its declaration being a record and so a type: Revision 38 refused
     * {@code record} there. A data document still declares nothing, but it does hold a checked name for a type.
     */
    @Test
    void aDataDocumentCanHoldACheckedTypeName() {
        assertEquals(List.of(), readUnderNaming("!pick { t: order }"));

        String undeclared = onlyMessage(readUnderNaming("!pick { t: no_such }"));
        assertTrue(undeclared.contains("'no_such' is not a member of this kind"), undeclared);

        assertEquals(List.of(), readUnderNaming("!pick { t: meta.record }"));
    }

    /**
     * <b>Any schema declares a reference.</b> The kernel's {@code meta.ref} reaches a schema importing core, so
     * {@code meta.ref<order>} resolves where Revision 38 left {@code ref<order>} an unresolved name outside a
     * kernel-governed schema.
     */
    @Test
    void anySchemaDeclaresAReference() {
        assertEquals(List.of(), tson(new LinkedHashMap<>(Map.of(ID, REFERRING))).validateSchema(REFERRING));
    }

    /**
     * <b>A type definition read as data is checked for membership as well as shape.</b> Imported from {@code
     * meta.tn}, {@code !meta.record { … }} is an ordinary value: a misspelt field and a malformed name are refused,
     * and so is a name the reading closure does not declare, a kind in a data document being judged by membership
     * whichever schema it is read under ({@link #aDataDocumentCanHoldACheckedTypeName}). A field's {@code type} is a
     * {@code meta.type_expr}, a choice of a name or a type written in place, so {@code 42} is a type mismatch, fitting
     * neither.
     */
    @Test
    void aTypeDefinitionReadAsDataIsJudgedForMembership() {
        Tson tson = tson(new LinkedHashMap<>());
        String data = "!!import:\"" + META + "\"\n";

        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                tson.validate(data + "!meta.record { fields: { sku => { type: no_such } } }").stream()
                        .map(Diagnostic::code).toList());
        assertEquals(List.of(), tson.validate(data + "!meta.record { fields: { sku => { type: text } } }"));
        assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH),
                tson.validate(data + "!meta.record { fields: { sku => { type: 42 } } }").stream()
                        .map(Diagnostic::code).toList());
        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD, Diagnostic.Code.FIELD_REQUIRED),
                tson.validate(data + "!meta.record { fieldz: { sku => { type: text } } }").stream()
                        .map(Diagnostic::code).toList());
    }

    // ── resolved forms ──────────────────────────────────────────────────────────────────────────────────────────

    /** The resolved form of a schema's entries, under its source's imports. */
    static final String RESOLVED_ENTRIES = """
            !schema {
              order => !meta.record { chain: { supertypes: [meta.product meta.top meta.type] }  fields: {
                sku => !meta.record_field { type: text }
              } }
              s => !steps { one => !step { takes: order } }
            }""";

    /** A namespace type's resolved body and its record's, as {@code meta-resolved.tn} writes the kernel's. */
    static final String RESOLVED_STEP = """
              step => !meta.record { chain: { supertypes: [meta.product meta.top meta.type] }  fields: {
                takes => !meta.record_field { type: meta.type_ref }
              } }
              steps => !meta.namespace { key_type: meta.identifier  value_type: step }
            """;

    static List<Diagnostic> readResolved(String root, String... imports) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        Tson tson = tson(documents);
        assertEquals(List.of(), tson.validateSchema(LAYER));
        StringBuilder head = new StringBuilder();
        for (String imported : imports) {
            head.append("!!import:\"").append(imported).append("\"\n");
        }
        var problems = DiagnosticsReceiver.collecting();
        tson.treeReader().withImports(List.of(imports)).withDiagnostics(problems)
                .readAs(tson.begin(head + root), "meta.schema");
        return problems.diagnostics();
    }

    /**
     * <b>A resolved form is read under its source's imports</b>, as data whose root is told to be a {@code
     * meta.schema}: a resolved form is a schema document by its root's tag, so only a read asked for that type takes
     * it. The layer supplies {@code steps}, so the fixture's {@code !steps} value reads.
     */
    @Test
    void aResolvedFormIsReadUnderItsSourcesImports() {
        assertEquals(List.of(), readResolved(RESOLVED_ENTRIES, LAYER_ID, META));
    }

    /**
     * <b>The one-level form does not read as data.</b> The fixture carries {@code steps}'s own resolved body beside
     * its value and imports the kernel alone. The namespace type's entry reads as an ordinary {@code !meta.namespace}
     * value; its value is refused, since {@code meta.declared} resolves a tag in the reading closure and nothing
     * compiles a reader from an entry of the document being read. A schema resolving it would wait on the type
     * declared beside the value; a data read does not.
     */
    @Test
    void aResolvedFormCarryingItsOwnConstructorDoesNotReadUnderTheKernel() {
        String root = RESOLVED_ENTRIES.replace("!schema {\n", "!schema {\n" + RESOLVED_STEP);
        List<Diagnostic> problems = readResolved(root, META);
        assertEquals(List.of("/s"), problems.stream().map(d -> d.path().orElse("<none>")).toList(), () -> "" + problems);
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
        assertTrue(onlyMessage(problems).contains("'steps' is not a type this schema declares or imports"),
                onlyMessage(problems));
    }
}
