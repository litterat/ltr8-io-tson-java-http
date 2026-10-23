package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
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
 * What the resolver does today at each place the levels show, so {@code experiments/one-level/README.md} states
 * measurements rather than readings of the spec. Every test pins the current outcome; one failing after a library
 * change means the ground the note stands on has moved.
 *
 * <p>Two halves. Within schemas: who may declare a constructor, who may apply one, and whether a schema can be its
 * own meta. Across the data/schema line: whether a data document can hold a type's name or a value's name, and
 * whether either is judged.
 */
class OneLevelProbe {

    static final String KERNEL = "https://tson.io/2026/38/m/meta-kernel.tn";
    static final String META = "https://tson.io/2026/38/m/meta.tn";
    static final String CORE = "https://tson.io/2026/38/m/core.tn";
    static final String ID = "https://schemas.example.com/2026/38/one-level/s-1.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/38/one-level/layer-1.tn";

    /** A meta layer declaring the probe's one constructor, the two-document shape every governed schema has today. */
    /** The probe's vocabulary: a record, and the namespace type a governed schema applies to hold it. */
    static final String STEPS =
            "step => { takes: type_kind }  steps => namespace & { key_type: = identifier  value_type: = step }";

    static final String LAYER = head(LAYER_ID, KERNEL, META)
            + "{ " + STEPS + " }";

    static String head(String id, String meta, String... imports) {
        StringBuilder head = new StringBuilder("!!id:\"" + id + "\"\n!!meta:\"" + meta + "\"\n");
        for (String imported : imports) {
            head.append("!!import:\"").append(imported).append("\"\n");
        }
        return head.toString();
    }

    static Tson tson(Map<String, String> documents) {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)))
                .withMetaNameBinder(new DataNameBinder.DefaultDataNameBinder(
                        Set.of("io.ltr8.tson.http.experiment.onelevel"), Map.of())));
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

    /** Today's shape: the constructor in a meta layer, its instance one document down. */
    @Test
    void aConstructorDeclaredInALayerIsAppliedOneDocumentDown() {
        assertEquals(List.of(), resolve(LAYER));
        assertEquals(List.of(), resolve(head(ID, LAYER_ID, CORE)
                + "{ order => { sku: text }  s => !steps { one => { takes: order } } }"));
    }

    /**
     * A meta layer cannot apply its own constructor: a schema's {@code !} targets come from the meta that governs it
     * (§3.3.1), so {@code steps} serves the layer below and is invisible here.
     */
    @Test
    void aMetaLayerCannotApplyItsOwnConstructor() {
        String message = onlyMessage(resolve(head(ID, KERNEL, META)
                + "{ order => { sku: text }  STEPS  s => !steps { one => { takes: order } } }".replace("STEPS", STEPS)));
        assertTrue(message.contains("'!steps' does not resolve against the structure namespace"), message);
    }

    /**
     * An ordinary schema never reaches the placement rule: the kernel's names are not in its namespace, so {@code
     * namespace} is simply undeclared. The level is enforced by visibility first.
     */
    @Test
    void anOrdinarySchemaCannotSeeTheKernel() {
        String message = onlyMessage(resolve(head(ID, META, CORE) + "{ " + STEPS + " }"));
        assertTrue(message.contains("supertype 'namespace' names no type this schema declares or imports"), message);
    }

    /** Importing the kernel beside core is refused: both declare {@code void}. */
    @Test
    void theKernelAndCoreCannotBothBeImported() {
        String message = onlyMessage(resolve(head(ID, META, CORE, KERNEL) + "{ order => { sku: text } }"));
        assertTrue(message.contains("'void' is declared by two different schemas"), message);
    }

    /** With the kernel imported, the placement rule is what refuses the constructor (§2.2.2). */
    @Test
    void aSchemaImportingTheKernelStillCannotDeclareAConstructor() {
        String message = onlyMessage(resolve(head(ID, META, KERNEL) + "{ " + STEPS + " }"));
        assertTrue(message.contains("only a schema chaining to meta-kernel.tn directly may declare new constructors"),
                message);
    }

    /**
     * A schema that is its own meta is an import cycle, even one declaring only types. The kernel's self-reference
     * is closed by pre-loading, and no other document can close it.
     */
    @Test
    void aSchemaThatIsItsOwnMetaIsAnImportCycle() {
        String message = onlyMessage(resolve(head(ID, ID, META, CORE) + "{ order => { sku: text } }"));
        assertTrue(message.contains("is part of an import cycle"), message);
    }

    /** In one governed schema, an instance names a type declared beside it, and a kind slot refuses the instance. */
    @Test
    void aKindSlotRefusesAnInstanceDeclaredBesideIt() {
        String message = onlyMessage(resolve(head(ID, LAYER_ID, CORE)
                + "{ order => { sku: text }  s => !steps { one => { takes: order } }"
                + "  t => !steps { one => { takes: s } } }"));
        assertTrue(message.contains("'s' is a value of the namespace type 'steps', a map of names -- a value and not a "
                + "type"), message);
    }

    // ── across the data/schema line ─────────────────────────────────────────────────────────────────────────────

    /** A schema importing the kernel (without core) may type a field by a kind, and by a reference. */
    static final String NAMING = head(ID, META, KERNEL)
            + "{ order => { sku: text }  pick => { t: type_kind }  rpick => { m: ref<order> } }";

    static List<Diagnostic> readUnderNaming(String body) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(ID, NAMING);
        Tson tson = tson(documents);
        assertEquals(List.of(), tson.validateSchema(NAMING));
        return tson.validate("!!schema:\"" + ID + "\"\n" + body);
    }

    /**
     * <b>A data document can hold a type's name, and it is judged.</b> At a kind-typed field the name must be a member
     * of the kind over the governing schema's closure: a declared type is admitted, an undeclared name is refused, and
     * so is a constructor. A data document still declares nothing; what it can no longer be said to lack is a checked
     * name for a type.
     */
    @Test
    void aDataDocumentCanHoldACheckedTypeName() {
        assertEquals(List.of(), readUnderNaming("!pick { t: order }"));

        String undeclared = onlyMessage(readUnderNaming("!pick { t: no_such }"));
        assertTrue(undeclared.contains("'no_such' is not a member of this kind"), undeclared);

        String constructor = onlyMessage(readUnderNaming("!pick { t: record }"));
        assertTrue(constructor.contains("'record' is not a member of this kind"), constructor);
    }

    /** A value's name in a data document is declared and not yet read: a gap, reported as one. */
    @Test
    void aReferenceInADataDocumentIsNotYetRead() {
        List<Diagnostic> problems = readUnderNaming("!rpick { m: whatever }");
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), problems.stream().map(Diagnostic::code).toList());
        assertTrue(problems.getFirst().message().contains("a data document has no link"),
                problems.getFirst().message());
    }

    /**
     * <b>A type definition read as data is checked for shape and grammar, not membership.</b> Governed by
     * {@code meta.tn}, {@code !record { … }} is an ordinary value: a misspelt field and a malformed name are refused,
     * and a name no schema declares is admitted -- where the same kind under a user schema judges membership
     * ({@link #aDataDocumentCanHoldACheckedTypeName}).
     */
    @Test
    void aTypeDefinitionReadAsDataIsJudgedForShapeNotMembership() {
        Tson tson = tson(new LinkedHashMap<>());
        String data = "!!schema:\"" + META + "\"\n";

        assertEquals(List.of(), tson.validate(data + "!record { fields: { sku => { type: no_such } } }"));
        assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID),
                tson.validate(data + "!record { fields: { sku => { type: 42 } } }").stream().map(Diagnostic::code)
                        .toList());
        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD, Diagnostic.Code.FIELD_REQUIRED),
                tson.validate(data + "!record { fieldz: { sku => { type: text } } }").stream().map(Diagnostic::code)
                        .toList());
    }

    // ── resolved forms ──────────────────────────────────────────────────────────────────────────────────────────

    /** The resolved form of a governed schema's entries, written as a data document's root. */
    static final String RESOLVED_ENTRIES = """
            !schema {
              order => !record { chain: { supertypes: [product top type] }  fields: {
                sku => !record_field { type: text }
              } }
              s => !steps { one => !step { takes: order } }
            }""";

    /** A namespace type's resolved body and its record's, as {@code meta-kernel-resolved.tn} writes {@code schema}. */
    static final String RESOLVED_STEP = """
              step => !record { chain: { supertypes: [product top type] }  fields: {
                takes => !record_field { type: type_kind }
              } }
              steps => !record { chain: { supertypes: [namespace top] }  fields: {
                key_type   => !record_field { type: identifier_kind  role: FIXED  value: identifier }
                value_type => !record_field { type: type_kind  role: FIXED  value: step }
                ordered    => !record_field { type: boolean  optional: true  role: DEFAULT  value: false }
              } }
            """;

    static List<Diagnostic> readResolved(String governing, String root) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(LAYER_ID, LAYER);
        Tson tson = tson(documents);
        assertEquals(List.of(), tson.validateSchema(LAYER));
        return tson.validate("!!schema:\"" + governing + "\"\n" + root);
    }

    /**
     * <b>Today a resolved form is read under its source's meta.</b> The layer supplies {@code steps}'s reader, so the
     * fixture's {@code !steps} instance reads -- the ladder is what makes a resolved schema readable as data.
     */
    @Test
    void aResolvedFormIsReadUnderItsSourcesMeta() {
        assertEquals(List.of(), readResolved(LAYER_ID, RESOLVED_ENTRIES));
    }

    /**
     * <b>The one-level form does not read today.</b> The fixture carries {@code steps}'s own resolved body beside its
     * instance and is governed by the kernel alone. The constructor's entry reads as an ordinary {@code !record}; its
     * instance is refused, since the kernel's closure has no {@code steps} among {@code top}'s subtypes and nothing
     * compiles a reader from an entry of the document being read. Deferring an instance until its constructor is
     * resolved is what would read it.
     */
    @Test
    void aResolvedFormCarryingItsOwnConstructorDoesNotReadUnderTheKernel() {
        String root = RESOLVED_ENTRIES.replace("!schema {\n", "!schema {\n" + RESOLVED_STEP);
        List<Diagnostic> problems = readResolved(KERNEL, root);
        assertEquals(List.of("/s"), problems.stream().map(d -> d.path().orElse("<none>")).toList(), () -> "" + problems);
        assertTrue(onlyMessage(problems).contains("'steps' is not admissible at a 'top' position"), onlyMessage(problems));
    }
}
