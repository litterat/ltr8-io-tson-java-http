package io.ltr8.tson.http.probe;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.HttpSchemaSource;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.ExternExamples;
import io.ltr8.tson.http.OrdersExamples;
import io.ltr8.tson.http.PayloadType;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonHttpSchema;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.TsonServiceDescription;
import io.ltr8.tson.http.probe.extern.Envelope;
import io.ltr8.tson.http.probe.extern.OrderV1;
import io.ltr8.tson.http.probe.extern.OrderV2;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.service.TsonBindings;
import io.ltr8.tson.service.TsonServiceSchema;
import io.ltr8.tson.tree.TsonRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ltr8.tson.http.ExternExamples.API;
import static io.ltr8.tson.http.ExternExamples.BASE;
import static io.ltr8.tson.http.ExternExamples.CORE;
import static io.ltr8.tson.http.ExternExamples.DESCRIPTION;
import static io.ltr8.tson.http.ExternExamples.ERRORS;
import static io.ltr8.tson.http.ExternExamples.KEYS;
import static io.ltr8.tson.http.ExternExamples.ORDER_1;
import static io.ltr8.tson.http.ExternExamples.ORDER_1_SOURCE;
import static io.ltr8.tson.http.ExternExamples.ORDER_2;
import static io.ltr8.tson.http.ExternExamples.ORDER_2_SOURCE;
import static io.ltr8.tson.http.ExternExamples.description;
import static io.ltr8.tson.http.ExternExamples.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How [TSON-SCHEMA] §5.13's extern, one entry of another schema under a local name, fits this project: a service
 * description naming two versions of one schema, a business error reached through one, a request body holding a
 * foreign value read through the codec in both encodings, the fetch policy on an extern's schema, and two versions
 * bound to two classes through one {@code Tson} by {@code DataNameBinder.byNamespace}. Measured against
 * {@link ExternExamples} and documents written here, through {@code SchemaSource.ofMap}. Every test pins Revision
 * 40's outcome, and one failing after a library change means the answer has moved.
 *
 * <p>The answers measured. A description declaring {@code order_v1} and {@code order_v2} as externs of two schemas
 * that both declare {@code order} loads, and an exchange may name either as its response; a choice of the two is no
 * value at a {@code meta.type_ref} slot, where a named choice of them stands. An extern entry's body is an {@link
 * Extern}, never a {@link RecordBody}, and {@code TsonLinkedSchema.landing} reaches the target record; {@code
 * TsonServiceDescription} reads through it, so a business error through an extern fixes the status its target pins, a
 * response header's template expands over the target's fields, and {@code payloadTypes()} names each type by its
 * declaring schema and its own name. {@code referencedSchemas()} lists the externed schemas, walking {@code
 * dependencies()}. {@code TsonServiceDescriptionExternTest} holds the reader to the rest.
 *
 * <p>A foreign value reads through the codec in TSON and in JSON, bound and as a tree, the body's {@code !!import}
 * of the declaring schema alone agreeing with {@code TSON-Schema}; a JSON boundary {@code $type} is the enclosing
 * scope's name, and the foreign name is refused there. An extern's schema is fetched under the import's policy: a
 * refused host is the same {@code SCHEMA_NOT_PERMITTED}, and the same 400, as a refused import. A foreign schema of
 * another kernel is refused by identity before any fetch. One {@code Tson} binds {@code order-1.tn}'s and {@code
 * order-2.tn}'s {@code order} to two classes with no extern involved, in both encodings, the binder asked by the
 * canonical identity of the declaring schema; a name-only binder is asked the target's name, never the extern's.
 */
class ExternProbeTest {

    static final String ENVELOPE = BASE + "envelope-1.tn";
    static final String REFUSED_HOST = "https://evil.example.org/x.tn";

    /** A schema whose record has a field typed by an extern of {@code order-1.tn}'s {@code order}. */
    static final String ENVELOPE_SOURCE = "!!id:\"" + ENVELOPE + "\"\n!!import:\"" + CORE + "\"\n" + """
            !schema {
              order_v1 => !meta.extern { schema: "%s"  type: order }
              envelope => { id: text  body: order_v1 }
            }""".formatted(ORDER_1);

    static final String TAGGED = "!!import:\"" + ENVELOPE + "\"\n!envelope { id: \"e1\"  body: !order_v1 { sku: A"
            + "  quantity: 2 } }";
    static final String UNTAGGED = "!!import:\"" + ENVELOPE + "\"\n!envelope { id: \"e1\"  body: { sku: A  quantity: 2"
            + " } }";
    static final Envelope READ = new Envelope("e1", new OrderV1("A", 2));

    /** {@link ExternExamples#library} with the envelope schema beside it. */
    static Map<String, String> library(String description) {
        Map<String, String> library = ExternExamples.library(description);
        library.put(ENVELOPE, ENVELOPE_SOURCE);
        return library;
    }

    /** A codec over the library binding {@code bindings} by name alone, with the envelope schema resolved. */
    static TsonHttpCodec envelopeCodec(Map<String, Class<?>> bindings) {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library(DESCRIPTION))))
                .withDataBindContext(TsonBindings.of(bindings)));
        tson.resolve(ENVELOPE_SOURCE);
        return new TsonHttpCodec(tson).acceptingJson();
    }

    static final TsonHttpCodec CODEC = envelopeCodec(Map.of("envelope", Envelope.class, "order", OrderV1.class));

    static InputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    // ── 1. two versions in one description ──

    /**
     * Two externs of two schemas that both declare {@code order} sit in one namespace, which an import of both
     * cannot give. The entry holds what was written; the link lands each on its schema's record, and the externed
     * schemas are dependencies after the imports.
     */
    @Test
    void aDescriptionNamingTwoVersionsByExternLoads() {
        Tson tson = OrdersExamples.tson(library(DESCRIPTION));
        assertEquals(List.of(), tson.validateSchema(DESCRIPTION));

        TsonLinkedSchema linked = tson.loader().resolveLinked(API);
        assertEquals(new Extern(ORDER_1, "order"), linked.schema().entries().get("order_v1"));
        assertEquals(List.of(CanonicalIdentity.canonicalize(TsonHttpSchema.ID), CanonicalIdentity.canonicalize(CORE),
                CanonicalIdentity.canonicalize(ORDER_1), CanonicalIdentity.canonicalize(ORDER_2),
                CanonicalIdentity.canonicalize(ERRORS), CanonicalIdentity.canonicalize(KEYS)), linked.dependencies());
        ExternTarget.Landing v2 = linked.landing("order_v2").orElseThrow();
        assertEquals(CanonicalIdentity.canonicalize(ORDER_2), v2.declaringSchema());
        assertEquals("order", v2.name());
        assertEquals(List.of("sku", "quantity", "currency"),
                List.copyOf(assertInstanceOf(RecordBody.class, v2.definition()).fields().keySet()));
    }

    /**
     * A choice is a type, not a name, and a {@code meta.type_ref} slot in a value takes a name: {@code response:
     * (order_v1 | order_v2)} is a parse error, so a description serving both versions at one endpoint names a choice
     * ({@link #aNamedChoiceOfTwoExternsStandsAtATypeRefSlot}) or writes two exchanges.
     */
    @Test
    void aChoiceOfTwoExternsIsNoValueAtATypeRefSlot() {
        String choice = DESCRIPTION.replace("response: order_v2 }", "response: (order_v1 | order_v2) }");
        List<Diagnostic> problems = OrdersExamples.tson(library(choice)).validateSchema(choice);

        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals("expected a value (record, map, array, empty braces, the void sentinel '_', or a token), found "
                + "'('", problems.getFirst().message());
    }

    /**
     * A named choice of the two is a type with a name, so it stands at the slot: the description loads, and the reader
     * takes the choice as the response, never looking inside it. One endpoint serving both versions is spelt so.
     */
    @Test
    void aNamedChoiceOfTwoExternsStandsAtATypeRefSlot() {
        String named = DESCRIPTION.replace("order_ref    => { id: text }",
                "order_ref    => { id: text }\n  order_any    => (order_v1 | order_v2)")
                .replace("response: order_v2 }", "response: order_any }");
        assertEquals(List.of(), OrdersExamples.tson(library(named)).validateSchema(named));

        Tson tson = described(named);
        ChoiceBody choice = assertInstanceOf(ChoiceBody.class,
                tson.loader().resolveLinked(API).schema().entries().get("order_any"));
        assertEquals(List.of("order_v1", "order_v2"), choice.variants().stream()
                .map(variant -> assertInstanceOf(TypeRef.class, variant).name()).toList());
        assertEquals("order_any",
                TsonHttpSchema.describedBy(tson, API).routes().get(1).response().orElseThrow().name());
    }

    /**
     * {@code TsonServiceDescription} reads a description whose responses are externs: each route names the extern as
     * written, and {@code payloadTypes} each where it is declared, the declaring schema and the entry's own name, which
     * is what the binder is asked, so the two versions are two payload types named {@code order}. {@code referencedSchemas}
     * lists both versions, the error's schema with its own import and the request records' schema, walking {@code
     * dependencies()}.
     */
    @Test
    void theReaderNamesAnExternTypedResponseByWhereItLands() {
        TsonServiceDescription described = TsonHttpSchema.describedBy(described(DESCRIPTION), API);

        assertEquals(List.of("order_v1", "order_v2"),
                described.routes().stream().map(route -> route.response().orElseThrow().name()).toList());
        assertEquals(List.of(new PayloadType(API, "order_ref"), new PayloadType(ORDER_1, "order"),
                new PayloadType(ORDER_2, "order")), List.copyOf(described.payloadTypes()));
        assertEquals(List.of(API, TsonHttpSchema.ID, TsonServiceSchema.ID, ORDER_1, ORDER_2, ERRORS,
                        TsonProblemSchema.ID, KEYS),
                List.copyOf(described.referencedSchemas()));
    }

    /**
     * A response header's template variable is looked up among the fields of the record the response type lands
     * on, in the foreign schema: {@code sku} of {@code order-2.tn}'s {@code order} is a scalar, so the template
     * expands.
     */
    @Test
    void aHeaderTemplateOverAnExternTypedResponseExpandsOverTheForeignRecord() {
        String headed = description("", "  response_headers: { ETag => \"{sku}\" }");
        TsonServiceDescription described = TsonHttpSchema.describedBy(described(headed), API);

        assertEquals(Map.of("etag", "A-1"), described.routes().get(1)
                .expandResponseHeaders(field -> field.equals("sku") ? "A-1" : null));
    }

    // ── 2. a business error through an extern ──

    /**
     * The reader finds an error's status pin on the record the extern lands on, so an error composing {@code
     * problem.details} with {@code status: = 404}, reached through an extern, fixes 404: the route's {@code
     * errorFor(404)} is the extern as written, and the landing holds the pin and the composition.
     */
    @Test
    void aBusinessErrorThroughAnExternFixesItsStatusForTheReader() {
        String erring = description("  errors: [not_found_v1]", "");
        Tson tson = described(erring);
        TsonServiceDescription described = TsonHttpSchema.describedBy(tson, API);

        assertEquals("not_found_v1", described.routes().getFirst().errorFor(404).orElseThrow().name());
        RecordBody target = assertInstanceOf(RecordBody.class,
                tson.loader().resolveLinked(API).landing("not_found_v1").orElseThrow().definition());
        RecordField status = target.fields().get("status");
        assertEquals(FieldRole.FIXED, status.role());
        assertEquals("404", status.value().orElseThrow().text());
        assertEquals(List.of("problem.details"), target.chain().supertypes());
    }

    // ── 3. the codec ──

    /**
     * A foreign value reads as a tree, tagged or not, the boundary carrying the extern's name; the body imports the
     * declaring schema alone, so a route stating that schema agrees, and a {@code TSON-Schema} header naming it
     * agrees while one adding the foreign schema does not.
     */
    @Test
    void aForeignValueReadsAsATreeAndTheBodysImportsAgree() {
        for (String document : List.of(TAGGED, UNTAGGED)) {
            TsonRecord envelope = assertInstanceOf(TsonRecord.class,
                    CODEC.readTreeAs(body(document), "application/tson", ENVELOPE, "envelope"));
            assertEquals(Optional.of("order_v1"), assertInstanceOf(TsonRecord.class, envelope.get("body")).typeRef());
        }
        assertEquals(List.of(ENVELOPE),
                TsonSchemaHeader.resolve(CODEC.begin(body(TAGGED)), "\"" + ENVELOPE + "\"").schemas());
        TsonHttpException widened = assertThrows(TsonHttpException.class, () -> TsonSchemaHeader.resolve(
                CODEC.begin(body(TAGGED)), "\"" + ENVELOPE + "\", \"" + ORDER_1 + "\""));
        assertEquals(TsonHttpException.BAD_REQUEST, widened.status());
    }

    /**
     * Bound, in TSON and in JSON: a name-only binder asked {@code order} binds the foreign record. The boundary
     * {@code $type} is the enclosing scope's name ([TSON-JSON] §8.6), so {@code order_v1} reads and the foreign
     * schema's own {@code order} is refused there.
     */
    @Test
    void aForeignValueBindsInBothEncodings() {
        assertEquals(READ, CODEC.readObject(body(TAGGED), "application/tson", Envelope.class));
        assertEquals(READ, CODEC.readObjectAs(body(UNTAGGED), "application/tson", ENVELOPE, "envelope",
                Envelope.class));
        for (String json : List.of("{\"id\": \"e1\", \"body\": {\"sku\": \"A\", \"quantity\": 2}}",
                "{\"id\": \"e1\", \"body\": {\"$type\": \"order_v1\", \"sku\": \"A\", \"quantity\": 2}}")) {
            assertEquals(READ, CODEC.readObjectAs(body(json), "application/json", ENVELOPE, "envelope",
                    Envelope.class));
        }
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> CODEC.readObjectAs(body(
                "{\"id\": \"e1\", \"body\": {\"$type\": \"order\", \"sku\": \"A\", \"quantity\": 2}}"),
                "application/json", ENVELOPE, "envelope", Envelope.class));
        Diagnostic problem = only(refused.diagnostics());
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, problem.code());
        assertTrue(problem.message().startsWith("'order' names no type of the enclosing scope, and a tag at a "
                + "'order_v1' position, which is an extern of 'order' in '" + ORDER_1 + "', is read there"),
                problem.message());
    }

    /**
     * Written: a bound body is untagged at the boundary, which reads back as the target; a tree writes back the
     * extern's name it carries; a root typed by an extern, written through {@code describing(uri, root)}, is tagged
     * with the extern's name, and JSON writes the bare object.
     */
    @Test
    void aForeignValueWritesTheEnclosingScopesName() {
        assertEquals("!!import:\"" + ENVELOPE + "\"\n!envelope { id: \"e1\" body: { sku: \"A\" quantity: 2 } }",
                text(CODEC.write(READ, ENVELOPE, "envelope")));
        assertEquals("!!import:\"" + ENVELOPE + "\"\n!envelope { id: !text \"e1\" body: !order_v1 { sku: !text \"A\""
                + " quantity: !int32 2 } }",
                text(CODEC.writeTree(CODEC.readTree(body(TAGGED), "application/tson"), ENVELOPE)));
        assertEquals("!!import:\"" + ENVELOPE + "\"\n!order_v1 { sku: \"A\" quantity: 2 }",
                text(CODEC.write(new OrderV1("A", 2), ENVELOPE, "order_v1")));
        assertEquals(new OrderV1("A", 2), CODEC.readObject(body("!!import:\"" + ENVELOPE + "\"\n!order_v1 { sku: A"
                + "  quantity: 2 }"), "application/tson", OrderV1.class));
        assertEquals("{\"id\":\"e1\",\"body\":{\"sku\":\"A\",\"quantity\":2}}",
                text(CODEC.write(READ, CODEC.negotiate("application/json"))));
    }

    /**
     * {@code prepareToRead} of the declaring schema compiles the foreign reader in bind mode, so a class that
     * disagrees with the target is a startup failure.
     */
    @Test
    void aForeignClassThatDisagreesIsAStartupFailure() {
        TsonHttpCodec codec = envelopeCodec(Map.of("envelope", Envelope.class, "order", OrderV2.class));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> codec.prepareToRead(ENVELOPE));

        assertTrue(refused.getMessage().startsWith("cannot prepare '" + ENVELOPE + "' for reading: 'order' and "
                + OrderV2.class.getName() + " do not agree: component 'currency' is filled by no field"),
                refused.getMessage());
    }

    /**
     * The binder is asked the target's name in its own schema, never the extern's local name: a map keyed by {@code
     * order_v1}, the name a description writes, prepares clean and answers every read a 500, the missing binding
     * deferred to the read as any type nothing binds is. {@code TsonServiceDescription} refuses such a binding at startup
     * where a description names the extern ({@code TsonServiceDescriptionExternTest}); the codec alone cannot.
     */
    @Test
    void aNameOnlyBinderIsAskedTheTargetsNameNotTheExterns() {
        TsonHttpCodec codec = envelopeCodec(Map.of("envelope", Envelope.class, "order_v1", OrderV1.class));
        codec.prepareToRead(ENVELOPE);
        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> codec.readObject(body(TAGGED), "application/tson", Envelope.class));

        assertEquals(TsonHttpException.INTERNAL_SERVER_ERROR, refused.status());
        MissingBindingException cause = assertInstanceOf(MissingBindingException.class, refused.getCause());
        assertTrue(cause.getMessage().startsWith("no bound Java class for 'order':"), cause.getMessage());
    }

    // ── 4. fetch policy ──

    /**
     * An extern's schema is fetched on an import's terms: a host the source refuses is {@code SCHEMA_NOT_PERMITTED}
     * for the extern as for an import, its message prefixed with the extern; thrown, it is a {@code
     * SchemaFetchException} of reason {@code NOT_PERMITTED}; and a body importing the declaring schema is the same
     * 400 as one whose own import is refused.
     */
    @Test
    void anExternsSchemaIsRefusedAsAnImportIs() {
        String externing = "!!id:\"" + BASE + "externing-1.tn\"\n!!import:\"" + CORE + "\"\n!schema {\n  ext => "
                + "!meta.extern { schema: \"" + REFUSED_HOST + "\"  type: order }\n}";
        String importing = "!!id:\"" + BASE + "importing-1.tn\"\n!!import:\"" + REFUSED_HOST + "\"\n!schema {\n  y =>"
                + " { a: text }\n}";
        Map<String, String> library = library(DESCRIPTION);
        library.put(BASE + "externing-1.tn", externing);
        library.put(BASE + "importing-1.tn", importing);
        SchemaSource known = SchemaSource.ofMap(library);
        try (HttpSchemaSource denyAll = HttpSchemaSource.builder().build()) {
            SchemaSource policed = uri -> uri.startsWith("https://evil.example.org/") ? denyAll.fetch(uri)
                    : known.fetch(uri);
            Diagnostic viaExtern = only(tson(policed).validateSchema(externing));
            Diagnostic viaImport = only(tson(policed).validateSchema(importing));

            assertEquals(Diagnostic.Code.SCHEMA_NOT_PERMITTED, viaExtern.code());
            assertEquals(viaImport.code(), viaExtern.code());
            assertEquals("cannot fetch schema '" + REFUSED_HOST + "': 'ext' is an extern of 'order' in '"
                    + REFUSED_HOST + "', whose schema is not available: cannot fetch schema '" + REFUSED_HOST + "': no "
                    + "host is allowed by this source", viaExtern.message());

            SchemaFetchException thrown = assertThrows(SchemaFetchException.class,
                    () -> tson(policed).resolve(externing));
            assertEquals(SchemaFetchException.Reason.NOT_PERMITTED, thrown.reason());
            assertEquals(TsonHttpException.BAD_REQUEST, TsonHttpException.from(thrown).status());

            for (String declaring : List.of("externing-1.tn", "importing-1.tn")) {
                TsonHttpException refused = assertThrows(TsonHttpException.class, () -> new TsonHttpCodec(
                        tson(policed)).readTree(body("!!import:\"" + BASE + declaring + "\"\n_"), "application/tson"));
                assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
                assertEquals(Diagnostic.Code.SCHEMA_NOT_PERMITTED, only(refused.diagnostics()).code());
            }
        }
    }

    /**
     * A foreign schema holding another revision's kernel is refused by identity before anything fetches it, a
     * {@code SCHEMA_ERROR} naming both kernels, and a body importing the declaring schema is a 400.
     */
    @Test
    void anExternAcrossKernelRevisionsIsRefusedByIdentity() {
        String crossing = "!!id:\"" + BASE + "crossing-1.tn\"\n!!import:\"" + CORE + "\"\n!schema {\n  ext => "
                + "!meta.extern { schema: \"https://tson.io/2026/39/m/meta.tn\"  type: type_name }\n}";
        Map<String, String> library = library(DESCRIPTION);
        library.put(BASE + "crossing-1.tn", crossing);
        Diagnostic problem = only(tson(SchemaSource.ofMap(library)).validateSchema(crossing));

        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code());
        assertEquals("'ext' is an extern of 'type_name' in 'https://tson.io/2026/39/m/meta.tn', whose schema does not "
                + "load: its closure holds the kernel 'tson.io/2026/39/m/meta.tn', and the schema naming it holds the "
                + "kernel 'tson.io/2026/40/m/meta.tn' -- an extern's schema holds the importer's kernel, one kernel by "
                + "identity ([TSON-SCHEMA] §5.13)", problem.message());
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> new TsonHttpCodec(tson(
                SchemaSource.ofMap(library))).readTree(body("!!import:\"" + BASE + "crossing-1.tn\"\n_"),
                "application/tson"));
        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
    }

    static Tson tson(SchemaSource source) {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    // ── 5. two versions bound in one context ──

    /**
     * With no extern involved, one {@code Tson} binds {@code order-1.tn}'s {@code order} to {@link OrderV1} and
     * {@code order-2.tn}'s to {@link OrderV2}: the binder is asked each name within the canonical identity of the
     * schema declaring it, so {@code byNamespace} routes the two apart. Both read and write in TSON and in JSON
     * through one codec, both prepare, and a v2 body read into the v1 class is the same refusal {@code
     * TsonSchemaVersions} meets.
     */
    @Test
    void oneContextBindsTwoVersionsByTheDeclaringSchema() {
        DataNameBinder unrouted = name -> {
            throw new DataBindException("'" + name + "' is asked without its schema");
        };
        DataBindContext bySchema = DataBindContext.builder().nameBinder(DataNameBinder.byNamespace(Map.of(
                        CanonicalIdentity.canonicalize(ORDER_1), DataNameBinder.ofMap(Map.of("order", OrderV1.class)),
                        CanonicalIdentity.canonicalize(ORDER_2), DataNameBinder.ofMap(Map.of("order", OrderV2.class))),
                unrouted)).registerAtoms(AtomContext.hostTypes()).build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library(DESCRIPTION))))
                .withDataBindContext(bySchema));
        tson.resolve(ORDER_1_SOURCE);
        tson.resolve(ORDER_2_SOURCE);
        TsonHttpCodec codec = new TsonHttpCodec(tson).acceptingJson();
        codec.prepareToRead(ORDER_1, ORDER_2);
        codec.prepareToWrite(OrderV1.class, OrderV2.class);

        OrderV1 v1 = new OrderV1("A", 1);
        OrderV2 v2 = new OrderV2("A", 1, "EUR");
        String v2Text = "!!import:\"" + ORDER_2 + "\"\n!order { sku: \"A\" quantity: 1 currency: \"EUR\" }";
        assertEquals(v1, codec.readObject(body("!!import:\"" + ORDER_1 + "\"\n!order { sku: A  quantity: 1 }"),
                "application/tson", OrderV1.class));
        assertEquals(v2, codec.readObject(body(v2Text), "application/tson", OrderV2.class));
        assertEquals(v1, codec.readObjectAs(body("{\"sku\": \"A\", \"quantity\": 1}"), "application/json", ORDER_1,
                "order", OrderV1.class));
        assertEquals(v2, codec.readObjectAs(body("{\"sku\": \"A\", \"quantity\": 1, \"currency\": \"EUR\"}"),
                "application/json", ORDER_2, "order", OrderV2.class));
        assertEquals("!!import:\"" + ORDER_1 + "\"\n!order { sku: \"A\" quantity: 1 }",
                text(codec.write(v1, ORDER_1, "order")));
        assertEquals(v2Text, text(codec.write(v2, ORDER_2, "order")));
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        codec.writeTo(v2, ORDER_2, "order", codec.negotiate("application/json"), json);
        assertEquals("{\"sku\":\"A\",\"quantity\":1,\"currency\":\"EUR\"}", json.toString(StandardCharsets.UTF_8));

        TsonHttpException crossed = assertThrows(TsonHttpException.class,
                () -> codec.readObject(body(v2Text), "application/tson", OrderV1.class));
        assertEquals("the schema's root type `order` binds to " + OrderV2.class.getName() + ", which is not "
                + "assignable to the requested " + OrderV1.class.getName(), only(crossed.diagnostics()).message());
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
