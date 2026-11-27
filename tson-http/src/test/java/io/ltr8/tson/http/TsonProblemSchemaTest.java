package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.compiler.TsonDiagnostics;
import io.ltr8.tson.http.schema.problem.TsonProblem;
import io.ltr8.tson.http.schema.problem.TsonProblemDiagnostic;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonProblemSchemaTest {

    @Test
    void theSchemaDeclaresTheIdThisPackageServesItAt() {
        assertTrue(TsonProblemSchema.source().contains("!!id:\"" + TsonProblemSchema.ID + "\""),
                "problem.tn's own !!id must match the constant a server serves it at");
    }

    /** On a clean instance -- {@link TsonProblemSchema#tson()} has already registered it, and registering twice is an error. */
    @Test
    void theSchemaResolves() {
        assertEquals(List.of(), Tson.of(ProcessorConfig.defaults()).validateSchema(TsonProblemSchema.source()));
    }

    /**
     * The claim that matters: an error body is not merely shaped like TSON, it validates against a real schema.
     * Writing with a plain object writer and reading back through the compiled schema is the only thing that
     * proves the two agree -- a hand-checked string would pass while binding silently dropped a field.
     */
    @Test
    void whatWriteProblemEmitsValidatesAgainstProblem1AndRoundTrips() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "invalid-document", 400, "Invalid TSON document", "the request body has 1 problem",
                List.of(TsonDiagnostics.ofSchemaError("https://example.com/2026/40/app/order-1.tn", "order",
                        "missing required field 'sku'", Optional.empty())));

        byte[] written = codec.writeProblem(problem);
        assertTrue(new String(written, StandardCharsets.UTF_8).contains("SCHEMA_ERROR"));

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(written), "application/tson",
                TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(problem, readBack);
    }

    /**
     * The JSON encoding of an error body is the same type. A JSON body names no root of its own, so the reader is
     * told {@code problem.details}; what {@code writeProblem} labels {@code application/problem+json} reads back
     * through {@code problem.tn} at that type, unchanged.
     */
    @Test
    void whatWriteProblemEmitsAsJsonValidatesAgainstProblemDetailsAndRoundTrips() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson()).acceptingJson();
        TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "invalid-document", 400,
                "Invalid TSON document", "the request body has 1 problem",
                List.of(TsonDiagnostics.ofSchemaError("https://example.com/2026/40/app/order-1.tn", "order",
                        "missing required field 'sku'", Optional.empty())));
        TsonHttpCodec.Representation json = codec.negotiate("application/json, application/problem+json");
        assertEquals(TsonMediaType.APPLICATION_PROBLEM_JSON, json.problemMediaType());

        byte[] written = codec.writeProblem(problem, json);
        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(written), "application/problem+json",
                TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(problem, readBack);
    }

    /**
     * The point of writing an error body through a {@code describing} writer: it names its own schema and root
     * type, so a client reads it with {@code readTree}/{@code readObject} and no arguments -- nothing told out
     * of band. The URL it names is one this project's own schema handler publishes, so it resolves too.
     */
    @Test
    void anErrorBodySaysWhatGovernsItAndReadsBackWithNothingToldOutOfBand() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "invalid-document", 400, "Invalid TSON document", "the request body has 1 problem",
                List.of(TsonDiagnostics.ofSchemaError("https://example.com/2026/40/app/order-1.tn", "order",
                        "missing required field 'sku'", Optional.empty())));

        String written = new String(codec.writeProblem(problem), StandardCharsets.UTF_8);
        assertTrue(written.startsWith("!!import:\"" + TsonProblemSchema.ID + "\""), written);
        assertTrue(written.contains("!problem.details"),
                "and a root type-ref, or a reader cannot select a type: " + written);

        // read, not readObjectAs: no schema URI, no type name, no prior knowledge of either.
        TsonProblem readBack = codec.readObject(
                new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8)), "application/tson",
                TsonProblem.class);
        assertEquals(problem, readBack);
    }

    /**
     * A 415 or a 406 produces no diagnostic at all, and the boundary still writes {@code errors}, an empty list,
     * which is a valid body and reads back as one.
     */
    @Test
    void aProblemWithNoDiagnosticsIsStillValid() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonHttpException.unsupportedMediaType("this endpoint reads application/tson")
                .problem();
        assertEquals(List.of(), problem.errors());

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(problem, readBack);
        assertEquals(415, readBack.status());
    }

    /**
     * {@code errors} is optional, and a business error omits it: a body without it, in either encoding, reads as an
     * empty list, since the binder hands {@link TsonProblem}'s constructor nothing for an omitted list.
     */
    @Test
    void aBodyOmittingErrorsReadsAsAnEmptyList() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson()).acceptingJson();
        String tson = "!!import:\"" + TsonProblemSchema.ID + "\"\n!problem.details { title: \"No such SKU\"  status: 404 }";
        TsonProblem read = codec.readObject(new ByteArrayInputStream(tson.getBytes(StandardCharsets.UTF_8)),
                "application/tson", TsonProblem.class);
        assertEquals(new TsonProblem(Optional.empty(), "No such SKU", 404, Optional.empty(), Optional.empty(),
                List.of()), read);

        String json = "{\"title\": \"No such SKU\", \"status\": 404}";
        assertEquals(read, codec.readObjectAs(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "application/problem+json", TsonProblemSchema.ID, "problem.details", TsonProblem.class));
    }

    /** {@code status} is a {@code problem.status}, an HTTP status code, so 42 is refused where an int32 took it. */
    @Test
    void aStatusOutsideHttpsRangeIsRefused() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        String body = "!!import:\"" + TsonProblemSchema.ID + "\"\n!problem.details { title: x  status: 42 }";
        TsonHttpException refused = assertThrows(TsonHttpException.class, () -> codec.readObject(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "application/tson", TsonProblem.class));
        assertEquals(400, refused.status());
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                refused.problem().errors().stream().map(TsonProblemDiagnostic::code).toList());
        assertEquals("/status", refused.problem().errors().getFirst().path().orElseThrow());
    }

    /** An absent detail must survive as an absence, not become an empty string. */
    @Test
    void anAbsentDetailStaysAbsent() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "internal-error", 500, "Internal error", null, List.of());
        assertEquals(Optional.empty(), problem.detail());

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(Optional.empty(), readBack.detail());
    }

    /**
     * RFC 9457 makes {@code type} (§3.1.1) and {@code instance} (§3.1.5) URI <em>references</em>, so both may be
     * relative, and {@link TsonProblem#at} is typically handed a request path. The schema has to say {@code
     * uri_reference}: core's {@code uri} requires a scheme, and a problem built through this package's own API
     * would otherwise fail the schema it names.
     */
    @Test
    void aRelativeTypeAndInstanceAreValid() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonProblem.of("/problems/out-of-stock", 409, "Out of stock", null, List.of())
                .at("/orders/123");

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem.details", TsonProblem.class);
        assertEquals(problem, readBack);
    }

    // ── the enum this schema copies ──────────────────────────────────────

    /**
     * Read through the real pipeline rather than by matching text: these are the members a reader will
     * actually enforce, which is the property that matters.
     */
    private static List<String> declaredCodes() {
        return membersOf("problem.diagnostic_code");
    }

    private static List<String> membersOf(String entryName) {
        Top entry = TsonProblemSchema.compiled().schema().entries().get(entryName);
        return assertInstanceOf(EnumBody.class, entry, entryName + " is an enum").members();
    }

    /**
     * {@code problem.tn}'s {@code problem.diagnostic_code} is a hand-written copy of {@link Diagnostic.Code}, and
     * nothing else checks that the copy is current. Add a member upstream and forget this schema, and an error
     * body emits a code its own schema rejects -- which no other test here would catch, because no fixture has
     * ever produced a code that is new.
     *
     * <p><b>The Java enum is the source of truth</b>, which is why this asserts against it rather than against
     * tson-cli's schema. Two schemas checked against each other would only prove they drifted together, and the
     * two are free to diverge everywhere else -- a CLI reports on files, a server reports on requests.
     */
    @Test
    void everyDiagnosticCodeIsDeclaredInTheSchema() {
        List<String> declared = declaredCodes();
        for (Diagnostic.Code code : Diagnostic.Code.values()) {
            assertTrue(declared.contains(code.name()),
                    () -> "Diagnostic.Code." + code + " is missing from problem.tn's problem.diagnostic_code: "
                            + declared + " -- add it there, in place while the schema is unpublished");
        }
    }

    /**
     * <b>The schema declares no reason enum beside the codes, and must not grow one.</b> Why a schema could
     * not be obtained is carried by the code, one per reason, so a body cannot state a code and a reason that
     * contradict each other -- a shape a second enum would make representable and this schema would call
     * valid. The five {@code SCHEMA_*} members of {@code problem.diagnostic_code} are what
     * {@link #everyDiagnosticCodeIsDeclaredInTheSchema} already holds to the Java enum.
     */
    @Test
    void theSchemaCarriesNoSecondCarrierForAFetchFailure() {
        assertNull(TsonProblemSchema.compiled().schema().entries().get("problem.fetch_reason"),
                "problem.tn declares a problem.fetch_reason enum: the reason is the code, and two carriers for one "
                        + "fact are free to disagree");

        Top diagnostic = TsonProblemSchema.compiled().schema().entries().get("problem.diagnostic");
        List<String> fields = List.copyOf(assertInstanceOf(RecordBody.class, diagnostic, "problem.diagnostic is a record")
                .fields().keySet());
        assertFalse(fields.contains("fetch_reason"),
                () -> "problem.diagnostic carries a fetch_reason field: every member is a location or the problem "
                        + "itself, and this one would be a payload for five codes out of " + fields);
    }

    @Test
    void theSchemaDeclaresNoCodeTheEnumDoesNotHave() {
        List<String> known = Arrays.stream(Diagnostic.Code.values()).map(Enum::name).toList();
        for (String declared : declaredCodes()) {
            assertTrue(known.contains(declared),
                    () -> "problem.tn declares '" + declared + "', which is not a Diagnostic.Code: " + known
                            + " -- a value no reader can ever produce");
        }
    }
}
