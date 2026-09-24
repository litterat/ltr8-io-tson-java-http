package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link TsonHttpCodec#acceptingJson()} admits, and that it reads it with [TSON-JSON]'s own reader.
 *
 * <p>TSON is not a JSON superset ([TSON-DATA] §6), so reading JSON with the TSON reader was neither all of JSON
 * nor JSON's meaning. {@link #theJsonReaderReadsJsonAsJson} is the four places the two used to differ, each now
 * read the way JSON means it -- the assertions that used to pin the divergence, flipped.
 */
class TsonHttpCodecJsonTest {

    private static final String SCHEMA_ID = "https://s.example.com/2026/36/app/note-1.tn";
    private static final String SCHEMA = """
            !!id:"https://s.example.com/2026/36/app/note-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
                note => { title: text  body?: text  subtitle?: text?  count: int32 }
            }""";

    public record Note(String title, String body, String subtitle, int count) {
    }

    private final TsonHttpCodec codec = codec(ProcessorConfig.defaults()).acceptingJson();

    private static TsonHttpCodec codec(ProcessorConfig config) {
        Tson tson = Tson.of(config
                .withSchemaAccess(SchemaAccess.of(uri -> SCHEMA))
                .withDataBindContext(TsonBindings.of(Map.of("note", Note.class))));
        tson.resolve(SCHEMA);
        return new TsonHttpCodec(tson);
    }

    /** The four places the TSON reader misread JSON, each read as JSON now. */
    @Test
    void theJsonReaderReadsJsonAsJson() {
        // 1. `null` is JSON's null -- a tree keeps which spelling of absence arrived -- not the string "null".
        assertInstanceOf(JsonNull.class, tree("{\"a\": null}").get("a"));

        // 2. A key that is not an identifier is a key: a schemaless object is a map of strings.
        assertEquals(1, tree("{\"first name\": 1}").get("first name").asInt());
        assertEquals(1, tree("{\"a.b\": 1}").get("a.b").asInt());

        // 3. A surrogate-pair escape -- how JSON must write a non-BMP character -- denotes that character.
        assertEquals("😀", tree("{\"a\": \"\\uD83D\\uDE00\"}").get("a").asString());

        // 4. RFC 8259's `\/`.
        assertEquals("x/y", tree("{\"a\": \"x\\/y\"}").get("a").asString());
    }

    /**
     * <b>Against a schema, JSON's {@code null} is the absent sentinel</b> -- what used to bind the four-character
     * string. So it is read on [TSON-SCHEMA] §5.2's terms: absence at a voidable field ({@code subtitle?: text?}),
     * and refused at one whose type admits no {@code _} ({@code body?: text}), which may be omitted instead.
     */
    @Test
    void aJsonNullIsTheAbsentSentinel() {
        assertEquals(new Note("t", null, null, 2), codec.readObjectAs(
                json("{\"title\": \"t\", \"subtitle\": null, \"count\": 2}"),
                "application/tson+json", SCHEMA_ID, "note", Note.class));

        assertEquals(TsonHttpException.BAD_REQUEST, assertThrows(TsonHttpException.class, () -> codec.readObjectAs(
                json("{\"title\": \"t\", \"body\": null, \"count\": 2}"),
                "application/tson+json", SCHEMA_ID, "note", Note.class)).status());
    }

    /** Bind mode validates against the schema and reports every problem at once, as a TSON body's read does. */
    @Test
    void aJsonBodyIsValidatedInFullAgainstTheStatedSchema() {
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> codec.readObjectAs(
                json("{\"count\": \"many\"}"), "application/tson+json", SCHEMA_ID, "note", Note.class));

        assertEquals(TsonHttpException.BAD_REQUEST, refused.status());
        List<Diagnostic.Code> codes = refused.diagnostics().stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains(Diagnostic.Code.FIELD_REQUIRED), () -> "title is missing: " + codes);
        assertTrue(codes.size() >= 2, () -> "and count is not an int32: " + codes);
    }

    /** The JSON tree mode against a schema: validated, and handed back as the JSON that arrived. */
    @Test
    void aJsonTreeReadAgainstASchemaIsValidated() {
        JsonValue read = codec.readJsonTreeAs(json("{\"title\": \"t\", \"count\": 2}"), "application/tson+json",
                SCHEMA_ID, "note");
        assertEquals("t", read.get("title").asString());

        assertEquals(TsonHttpException.BAD_REQUEST, assertThrows(TsonHttpException.class,
                () -> codec.readJsonTreeAs(json("{\"count\": 2}"), "application/tson+json", SCHEMA_ID, "note"))
                .status());
    }

    /** Every JSON media type reads alike: Part 3's own, plain JSON, and a {@code +json} suffix. */
    @Test
    void everyJsonMediaTypeIsReadByTheJsonReader() {
        for (String type : List.of("application/tson+json", "application/json", "application/problem+json",
                "application/json; charset=utf-8")) {
            assertEquals(new Note("t", null, null, 2), codec.readObjectAs(json("{\"title\": \"t\", \"count\": 2}"),
                    type, SCHEMA_ID, "note", Note.class), type);
        }
    }

    /**
     * <b>One configuration, both encodings.</b> The JSON reader is built from the codec's own {@code Tson}, so
     * §9.1's nesting bound set there refuses a JSON body as it would a TSON one -- a 413, not a JSON reader
     * running on defaults nobody configured.
     */
    @Test
    void theJsonReaderIsJudgedByTheTsonPolicy() {
        TsonHttpCodec shallow = codec(ProcessorConfig.defaults().withLimits(new LimitsPolicy(3))).acceptingJson();

        TsonHttpException refused = assertThrows(TsonHttpException.class,
                () -> shallow.readJsonTree(json("[[[[[1]]]]]"), "application/json"));
        assertEquals(413, refused.status());
    }

    /** A body an endpoint that did not opt in never sees: the gate is still a gate, for every JSON type. */
    @Test
    void aJsonBodyIsA415WithoutTheOptIn() {
        TsonHttpCodec tsonOnly = codec(ProcessorConfig.defaults());

        for (String type : List.of("application/json", "application/tson+json")) {
            assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE, assertThrows(TsonHttpException.class,
                    () -> tsonOnly.readObjectAs(json("{\"title\": \"t\", \"count\": 2}"), type, SCHEMA_ID,
                            "note", Note.class)).status(), type);
        }
    }

    /** JSON is UTF-8 ([TSON-JSON] §3.1), so a charset naming anything else is refused as it is for TSON. */
    @Test
    void aJsonBodyClaimingAnotherCharsetIsA415() {
        assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE, assertThrows(TsonHttpException.class,
                () -> codec.readJsonTree(json("{}"), "application/json; charset=latin1")).status());
    }

    /**
     * <b>A tree is the encoding's own model.</b> A {@code TsonValue} is the TSON reader's, so a JSON body handed to
     * a {@code TsonValue} read is a fault in the route -- the handler chose the wrong read -- and fails as one, 500
     * through the boundary, rather than a 415 blaming a client that sent what the endpoint admits.
     */
    @Test
    void aJsonBodyIsNeverReadIntoATsonValue() {
        assertThrows(IllegalStateException.class, () -> codec.readTree(json("{}"), "application/json"));
        assertThrows(IllegalStateException.class,
                () -> codec.readTreeAs(json("{}"), "application/json", SCHEMA_ID, "note"));
    }

    /** A JSON tree read of a TSON body is the client's mismatch: the route reads JSON. */
    @Test
    void aJsonTreeReadOfATsonBodyIsA415() {
        assertEquals(TsonHttpException.UNSUPPORTED_MEDIA_TYPE, assertThrows(TsonHttpException.class,
                () -> codec.readJsonTree(json("{ a: 1 }"), "application/tson")).status());
    }

    /** Bind mode reads a TSON body on the same codec exactly as before. */
    @Test
    void aTsonBodyIsStillReadByTheTsonReader() {
        assertEquals(new Note("t", null, null, 2), codec.readObjectAs(json("{ title: t  count: 2 }"),
                "application/tson", SCHEMA_ID, "note", Note.class));
    }

    // ── the write side ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Negotiation, as a table.</b> Each row is an {@code Accept} and what it gets: the response's media type,
     * the problem's, and whether TSON remains acceptable for a response only TSON can carry. A tie goes to TSON,
     * then to {@code application/tson+json}; {@code application/problem+json} labels a problem only where the
     * client accepts it.
     */
    @Test
    void negotiationPicksTheHighestQualityAndBreaksTiesTowardTson() {
        record Row(String accept, String media, String problem, boolean tson) {
        }
        for (Row row : List.of(
                new Row(null, "application/tson", "application/tson", true),
                new Row("*/*", "application/tson", "application/tson", true),
                new Row("application/*", "application/tson", "application/tson", true),
                new Row("application/tson+json", "application/tson+json", "application/tson+json", false),
                new Row("application/json", "application/json", "application/json", false),
                new Row("application/json, application/tson+json", "application/tson+json",
                        "application/tson+json", false),
                new Row("application/json, application/problem+json", "application/json",
                        "application/problem+json", false),
                new Row("application/json, */*;q=0.1", "application/json", "application/problem+json", true),
                new Row("application/tson;q=0.5, application/json", "application/json", "application/json",
                        true))) {
            TsonHttpCodec.Representation chosen = codec.negotiate(row.accept());
            assertEquals(row.media(), chosen.mediaType().toString(), row.accept());
            assertEquals(row.problem(), chosen.problemMediaType().toString(), row.accept());
            assertEquals(row.tson(), chosen.tsonAcceptable(), row.accept());
        }

        assertEquals(TsonHttpException.NOT_ACCEPTABLE, assertThrows(TsonHttpException.class,
                () -> codec.negotiate("text/html")).status());
    }

    /** A codec that did not opt in produces TSON only, and negotiates exactly as it always checked. */
    @Test
    void aCodecWithoutTheOptInNegotiatesTsonOnly() {
        TsonHttpCodec tsonOnly = codec(ProcessorConfig.defaults());
        assertEquals(TsonHttpCodec.Representation.TSON, tsonOnly.negotiate("*/*"));
        assertEquals(TsonHttpException.NOT_ACCEPTABLE, assertThrows(TsonHttpException.class,
                () -> tsonOnly.negotiate("application/json")).status());
    }

    /**
     * <b>A JSON response is the JSON encoding of the same value</b>, through the same bindings, and names no schema
     * in band -- JSON has no directive syntax, so the caller's {@code TSON-Schema} header is the only channel.
     */
    @Test
    void aDescribedJsonResponseIsBareJsonThatReadsBack() {
        TsonHttpCodec.Representation json = codec.negotiate("application/tson+json");
        Note note = new Note("t", "b", null, 2);

        var out = new java.io.ByteArrayOutputStream();
        codec.writeTo(note, SCHEMA_ID, "note", json, out);
        String written = out.toString(StandardCharsets.UTF_8);

        assertFalse(written.contains("$schema") || written.contains("!!schema"), written);
        assertEquals(note, codec.readObjectAs(json(written), "application/tson+json", SCHEMA_ID, "note", Note.class));
    }

    /**
     * <b>A problem written as JSON is an RFC 9457 body</b>: its five members at the top level, absent ones left
     * out rather than written {@code null}, and {@code errors} as an extension member.
     */
    @Test
    void aProblemWrittenAsJsonIsRfc9457() {
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> codec.readObjectAs(
                json("{}"), "application/json", SCHEMA_ID, "note", Note.class));

        JsonValue problem = Json.parse(new String(
                codec.writeProblem(refused.problem(), codec.negotiate("application/json, application/problem+json")),
                StandardCharsets.UTF_8));

        assertEquals(400, problem.get("status").asInt());
        assertTrue(problem.get("type").asString().endsWith("/invalid-document"), problem.toString());
        assertTrue(problem.tryGet("instance").isEmpty(), "absent is omitted, not null: " + problem);
        assertEquals("FIELD_REQUIRED", problem.get("errors").get(0).get("code").asString());
    }

    private JsonValue tree(String document) {
        return codec.readJsonTree(json(document), "application/json");
    }

    private static InputStream json(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
