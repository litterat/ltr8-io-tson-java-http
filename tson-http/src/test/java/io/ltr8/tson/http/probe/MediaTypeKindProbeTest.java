package io.ltr8.tson.http.probe;

import io.ltr8.annotation.Field;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.net.MediaType;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a layer importing the kernel -- the shape of {@code service.tn} -- can type a record field as a media
 * type value. Every test pins Revision 39's measured outcome; one failing after a library change means the answer has
 * moved.
 *
 * <p>The answer measured: yes. One namespace per schema lets a layer import {@code net.tn} and either name its {@code
 * media_type} or apply {@code !net.media_type_type} itself, and the value binds to a {@link String} or a {@link
 * MediaType} alike. What a layer cannot do is declare a {@code media_type} of its own beside {@code net.tn}'s, or have
 * a schema importing core and it: two declarations of one name in one closure collide. A kind slot over {@code
 * net.media_type_type} resolves too, and holds a type's name, never a value. Revision 38 answered no to each, a layer
 * reaching {@code meta.tn}'s constructors and {@code net.tn}'s types through neither its meta nor an import.
 */
class MediaTypeKindProbeTest {

    static final String META = "https://tson.io/2026/40/m/meta.tn";
    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String NET = "https://tson.io/2026/40/m/net.tn";
    static final String LAYER_ID = "https://schemas.example.com/2026/40/media/layer-1.tn";
    static final String ID = "https://schemas.example.com/2026/40/media/s-1.tn";

    /** RFC 9110's token, twice: the pattern {@code service.tn}'s own {@code media_type} used. */
    static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

    /** The namespace type a schema importing the layer applies to hold {@code ep} values, as with {@code api}. */
    static final String EPS = "eps => !meta.namespace { key_type: meta.identifier  value_type: ep }";

    /** The bound form of {@code ep} when its slot holds atoms, read as text. */
    public record Ep(@Field("media_types") List<String> mediaTypes) {
    }

    /** The bound form of {@code ep} when its slot holds atoms, read as the library's media type. */
    public record MediaEp(@Field("media_types") List<MediaType> mediaTypes) {
    }

    /** The bound form of {@code ep} when its slot is a kind, which holds type names. */
    public record KindEp(@Field("media_types") List<TypeRef> mediaTypes) {
    }

    static String head(String id, String... imports) {
        StringBuilder head = new StringBuilder("!!id:\"" + id + "\"\n");
        for (String imported : imports) {
            head.append("!!import:\"").append(imported).append("\"\n");
        }
        return head.toString();
    }

    /** A layer importing {@code imports} beside the kernel, declaring {@code declarations} and {@link #EPS}. */
    static String layer(String declarations, String... imports) {
        String[] all = new String[imports.length + 1];
        all[0] = META;
        System.arraycopy(imports, 0, all, 1, imports.length);
        return head(LAYER_ID, all) + "!schema { " + declarations + "  " + EPS + " }";
    }

    /** A schema importing the layer, with one entry holding a value of {@code eps} with a single {@code ep}. */
    static String governed(String value, String... imports) {
        String[] all = new String[imports.length + 1];
        all[0] = LAYER_ID;
        System.arraycopy(imports, 0, all, 1, imports.length);
        return head(ID, all) + "!schema { order => { sku: text }  x => !eps { one => { media_types: [" + value
                + "] } } }";
    }

    static Tson tson(String layer, String schema, Class<?> ep) {
        Map<String, String> documents = new LinkedHashMap<>();
        if (layer != null) {
            documents.put(LAYER_ID, layer);
        }
        documents.put(ID, schema);
        ProcessorConfig config = ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(documents)));
        if (ep != null) {
            config = config.withDataBindContext(
                    AtomContext.defaultContext().toBuilder().nameBinder(DataNameBinder.ofMap(Map.of("ep", ep))).build());
        }
        return Tson.of(config);
    }

    static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> "" + problems);
        return problems.getFirst();
    }

    /** The one {@code ep} the governed schema's {@code x} entry holds, read back from its resolved entries. */
    static Object heldEp(Tson tson) {
        var entries = tson.schemaRegistry().get(ID).orElseThrow().schema().entries();
        return ((Namespace<?>) ((ValueEntry) entries.get("x")).value()).get("one");
    }

    /** A tree read of {@code data}; null when it was refused, with the refusal in {@code problems}. */
    static TsonValue treeRead(Tson tson, String data, DiagnosticsReceiver problems) {
        return tson.treeReader().withDiagnostics(problems).read(data);
    }

    // ── case 1: the layer applies net.tn's constructor ──────────────────────────────────────────────────────────

    /**
     * <b>A layer applies {@code !net.media_type_type} once it imports {@code net.tn}</b>, which declares that member of
     * the {@code net} map; the kernel alone does not, and the head is refused as naming no entry. A layer's own type
     * cannot be called {@code media_type}: {@code net.tn}'s root already holds the name.
     */
    @Test
    void aLayerAppliesNetsMediaTypeConstructorOnceItImportsNet() {
        String metaOnly = layer("mt => !net.media_type_type {}  ep => { media_types?: [mt] }");
        assertEquals("'mt': '!net.media_type_type' names no entry of this schema's namespace -- its own entries and "
                        + "every entry its !!imports bring in (§3.3.1)",
                only(tson(metaOnly, governed("\"application/json\"", CORE), Ep.class).validateSchema(metaOnly)).message());

        String applying = layer("mt => !net.media_type_type {}  ep => { media_types?: [mt] }", NET);
        Tson tson = tson(applying, governed("\"application/json\"", CORE), Ep.class);
        assertEquals(List.of(), tson.validateSchema(applying));
        assertEquals(List.of(), tson.validateSchema(governed("\"application/json\"", CORE)));
        assertEquals(List.of("application/json"), assertInstanceOf(Ep.class, heldEp(tson)).mediaTypes());

        String ownName = layer("media_type => !net.media_type_type {}  ep => { media_types?: [media_type] }", NET);
        assertEquals("'media_type' collides with an entry of the same name brought in by !!import",
                only(tson(ownName, governed("\"application/json\"", CORE), Ep.class).validateSchema(ownName)).message());
    }

    // ── case 2: the layer imports net.tn's media_type ───────────────────────────────────────────────────────────

    /**
     * <b>A layer names {@code net.tn}'s {@code media_type} by importing it</b>, as {@code service.tn} does. The
     * value binds to a {@link String} or a {@link MediaType}, and the type's own facets judge it: a string with no
     * {@code /} is refused, and so is one carrying parameters.
     */
    @Test
    void aLayerImportsNetsMediaType() {
        String importing = layer("ep => { media_types?: [media_type] }", NET);
        Tson tson = tson(importing, governed("\"application/json\"", CORE), Ep.class);
        assertEquals(List.of(), tson.validateSchema(importing));
        assertEquals(List.of(), tson.validateSchema(governed("\"application/json\"", CORE)));
        Ep ep = assertInstanceOf(Ep.class, heldEp(tson));
        assertEquals(List.of("application/json"), ep.mediaTypes());

        Tson typed = tson(importing, governed("\"application/json\"", CORE), MediaEp.class);
        assertEquals(List.of(), typed.validateSchema(governed("\"application/json\"", CORE)));
        assertEquals("application/json",
                assertInstanceOf(MediaEp.class, heldEp(typed)).mediaTypes().getFirst().toString());

        String noSlash = governed("\"not a media type\"", CORE);
        assertTrue(only(tson(importing, noSlash, Ep.class).validateSchema(noSlash)).message()
                .endsWith("'media_type': 'not a media type' is not a media type: it has no '/', and a media type is a "
                        + "type and a subtype"));
        String parameters = governed("\"text/html; charset=utf-8\"", CORE);
        assertTrue(only(tson(importing, parameters, Ep.class).validateSchema(parameters)).message()
                .endsWith("'media_type': 'text/html; charset=utf-8' carries parameters, and this type admits a media "
                        + "type without them (allow_parameters: false)"));
    }

    // ── case 3: the layer types the slot by meta.ref<net.media_type_type> ──────────────────────────────────────

    static final String KIND_LAYER = layer("ep => { media_types?: [meta.ref<net.media_type_type>] }", NET);

    /**
     * <b>{@code meta.ref<net.media_type_type>} resolves at a layer importing {@code net.tn}, and holds a type's
     * name.</b> {@code media_type} is admitted, {@code host} is refused as built by another constructor, and the
     * literal {@code "application/json"} is refused as no type name. Revision 38 refused the kind at the layer, its
     * {@code of} naming no constructor of the governing meta.
     */
    @Test
    void kindOfMediaTypeTypeHoldsATypeName() {
        Tson tson = tson(KIND_LAYER, governed("media_type", CORE), KindEp.class);
        assertEquals(List.of(), tson.validateSchema(KIND_LAYER));
        assertEquals(List.of(), tson.validateSchema(governed("media_type", CORE)));
        KindEp ep = assertInstanceOf(KindEp.class, heldEp(tson));
        assertEquals(List.of("media_type"), ep.mediaTypes().stream().map(TypeRef::name).toList());

        String host = governed("host", CORE);
        assertEquals("'x' at /one/media_types/0 wants the name of a type whose value is of 'net.media_type_type' (a "
                        + "member of 'meta.ref<net.media_type_type>'), and 'host' is an entry whose body is built by "
                        + "'net.host_type', which is no 'net.media_type_type'",
                only(tson(KIND_LAYER, host, KindEp.class).validateSchema(host)).message());

        String literal = governed("\"application/json\"", CORE);
        assertTrue(only(tson(KIND_LAYER, literal, KindEp.class).validateSchema(literal)).message()
                .contains("expected a type's name, found TokenEvent[text=application/json"));
    }

    /**
     * <b>An application written as a tagged {@code meta.apply} at a kind slot is refused through a stream event's
     * {@code toString}.</b> {@code !meta.apply { head: order  arguments: [order] }} is no type's name, and the refusal
     * says so as {@code found TypeRef[name=meta.apply, position=Position[line=0, …]]}: the record rendering of {@code
     * io.ltr8.tson.compiler.stream.TypeRef}, the tag's event, with a zero position, as the quoted token's refusal in
     * {@link #kindOfMediaTypeTypeHoldsATypeName} renders its {@code TokenEvent}. {@code UPSTREAM.md} #13; the fragment
     * asserted here flips when the tag is named as written.
     */
    @Test
    void aTaggedApplicationAtAKindSlotIsRefusedThroughTheTagEventsToString() {
        String tagged = governed("!meta.apply { head: order  arguments: [order] }", CORE);
        Diagnostic problem = only(tson(KIND_LAYER, tagged, KindEp.class).validateSchema(tagged));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code());
        assertTrue(problem.message().contains("at /one/media_types/0: expected a type's name, found TypeRef[name="
                + "meta.apply, position=Position[line=0, column=0, byteOffset=0]]"), problem.message());
    }

    static final String ATOM_KIND_LAYER = layer("ep => { media_types?: [meta.ref<meta.atom>] }");

    /**
     * <b>A kind over the kernel's {@code meta.atom} holds a type's name, never a value.</b> {@code media_type} reads to
     * the {@link TypeRef} {@code media_type}, and so does {@code host}: the kind cannot narrow to media types. A
     * record's name is refused as no atom, a name the closure does not declare as no type, and the literal {@code
     * "application/json"} as no type name.
     *
     * <p>The literal's refusal names the token through the parser event's {@code toString}, a Java record's rendering
     * carrying a zero position ({@code TokenEvent[text=application/json, …, position=Position[line=0, …]]}), rather
     * than as the token written. Asserted here by the fragments either side of it, so a fix to that rendering does not
     * fail this test.
     */
    @Test
    void aKernelKindHoldsATypeNameAndRefusesAMediaTypeValue() {
        for (String name : List.of("media_type", "host")) {
            Tson tson = tson(ATOM_KIND_LAYER, governed(name, CORE), KindEp.class);
            assertEquals(List.of(), tson.validateSchema(ATOM_KIND_LAYER));
            assertEquals(List.of(), tson.validateSchema(governed(name, CORE)));
            KindEp ep = assertInstanceOf(KindEp.class, heldEp(tson));
            assertEquals(List.of(name), ep.mediaTypes().stream().map(TypeRef::name).toList());
        }

        String record = governed("order", CORE);
        assertEquals("'x' at /one/media_types/0 wants the name of a type whose value is of 'meta.atom' (a member of "
                        + "'meta.ref<meta.atom>'), and 'order' is an entry whose body is built by 'meta.record', which "
                        + "is no 'meta.atom'",
                only(tson(ATOM_KIND_LAYER, record, KindEp.class).validateSchema(record)).message());

        String unimported = governed("media_type");
        assertEquals("'x' at /one/media_types/0 names 'media_type', and 'media_type' is no type declared in this "
                        + "schema or its imports",
                only(tson(ATOM_KIND_LAYER, unimported, KindEp.class).validateSchema(unimported)).message());

        String literal = governed("\"application/json\"", CORE);
        Diagnostic problem = only(tson(ATOM_KIND_LAYER, literal, KindEp.class).validateSchema(literal));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code());
        assertTrue(problem.message().startsWith("'x': the body is no value of the type 'eps' -- at /one/media_types/0: "
                + "expected a type's name, found "), problem.message());
        assertTrue(problem.message().endsWith("-- a position typed by a kind holds the name of a type, one bare "
                + "token"), problem.message());
    }

    /** A schema importing core, typing one field by the kind and one by the type. */
    static final String ORDINARY = head(ID, CORE)
            + "!schema { pick => { m: [meta.ref<net.media_type_type>] }  value => { m: [media_type] } }";

    static String pick(String root, String value) {
        return "!!import:\"" + ID + "\"\n!" + root + " { m: [" + value + "] }";
    }

    /**
     * <b>In a data document the kind is satisfied by the name, and the type holds the value.</b> A data document's
     * {@code media_type} is admitted as the name (a {@link TypeRef} in the tree), {@code host} and {@code text} are
     * refused as non-members, and {@code "application/json"} is refused as no name. The value itself is held by the
     * type {@code media_type}, which reads to {@link MediaType}.
     */
    @Test
    void inADataDocumentTheKindIsSatisfiedByTheNameAndTheTypeHoldsTheValue() {
        Tson tson = tson(null, ORDINARY, null);
        assertEquals(List.of(), tson.validateSchema(ORDINARY));

        var problems = DiagnosticsReceiver.collecting();
        TsonValue named = treeRead(tson, pick("pick", "media_type"), problems);
        assertEquals(List.of(), problems.diagnostics());
        TsonAtom name = assertInstanceOf(TsonAtom.class, named.at("/m/0"));
        assertEquals("media_type", assertInstanceOf(TypeRef.class, name.value()).name());

        for (String other : List.of("host", "text")) {
            var refused = DiagnosticsReceiver.collecting();
            assertNull(treeRead(tson, pick("pick", other), refused));
            Diagnostic problem = only(refused.diagnostics());
            assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problem.code());
            assertEquals("'meta.ref<net.media_type_type>': '" + other + "' is not a member of this kind -- expected "
                    + "the name of a type whose value IS-A 'net.media_type_type', one of [media_type]", problem.message());
        }

        var literal = DiagnosticsReceiver.collecting();
        assertNull(treeRead(tson, pick("pick", "\"application/json\""), literal));
        Diagnostic problem = only(literal.diagnostics());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problem.code());
        assertEquals("'meta.ref<net.media_type_type>': 'application/json' is no reference -- 'application/json' is "
                + "no name: 'application/json': U+002F at index 11 cannot appear in an identifier", problem.message());

        var valued = DiagnosticsReceiver.collecting();
        TsonValue value = treeRead(tson, pick("value", "\"application/json\""), valued);
        assertEquals(List.of(), valued.diagnostics());
        TsonAtom mediaType = assertInstanceOf(TsonAtom.class, value.at("/m/0"));
        assertEquals("application/json", assertInstanceOf(MediaType.class, mediaType.value()).toString());
    }

    // ── case 4: the Revision 38 compromise, a text_type with a pattern ──────────────────────────────────────────

    static final String TEXT_LAYER = layer("media_type => !unicode.text_type { pattern: \"" + TOKEN + "/" + TOKEN
            + "\" }  ep => { media_types?: [media_type] }");

    /**
     * <b>The {@code text_type} compromise still reads, and the value is a {@link String}</b> -- but only where nothing
     * else in the closure declares {@code media_type}: a schema importing it and core meets the collision. Bound
     * through the schema's {@code eps} entry and read as a tree under the layer alike, and a record value standing as
     * an entry of its own ({@code x => !ep { … }}) is a value entry. Revision 38 refused that entry, a record not
     * being IS-A {@code top}.
     */
    @Test
    void theTextTypeCompromiseReadsAsAStringAndCollidesWithCore() {
        String schema = governed("\"application/json\"");
        Tson tson = tson(TEXT_LAYER, schema, Ep.class);
        assertEquals(List.of(), tson.validateSchema(TEXT_LAYER));
        assertEquals(List.of(), tson.validateSchema(schema));
        Ep ep = assertInstanceOf(Ep.class, heldEp(tson));
        assertEquals(List.of("application/json"), ep.mediaTypes());
        assertInstanceOf(String.class, ep.mediaTypes().getFirst());

        var problems = DiagnosticsReceiver.collecting();
        TsonValue tree = treeRead(tson, "!!import:\"" + LAYER_ID + "\"\n!ep { media_types: [\"application/json\"] }",
                problems);
        assertEquals(List.of(), problems.diagnostics());
        TsonAtom atom = assertInstanceOf(TsonAtom.class, tree.at("/media_types/0"));
        assertEquals("application/json", assertInstanceOf(String.class, atom.value()));

        String withCore = governed("\"application/json\"", CORE);
        assertTrue(only(tson(TEXT_LAYER, withCore, Ep.class).validateSchema(withCore)).message()
                .startsWith("'media_type' is declared by two different schemas reached through !!import"));

        String direct = head(ID, LAYER_ID) + "!schema { x => !ep { media_types: [\"application/json\"] } }";
        Tson directly = tson(TEXT_LAYER, direct, Ep.class);
        assertEquals(List.of(), directly.validateSchema(direct));
        ValueEntry entry = assertInstanceOf(ValueEntry.class,
                directly.schemaRegistry().get(ID).orElseThrow().schema().entries().get("x"));
        assertEquals("ep", entry.type());
        assertEquals(new Ep(List.of("application/json")), entry.value());
    }
}
