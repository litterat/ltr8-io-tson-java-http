package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonProblemSchemaTest {

    @Test
    void theSchemaDeclaresTheIdThisPackageServesItAt() {
        assertTrue(TsonProblemSchema.source().contains("!!id:\"" + TsonProblemSchema.ID + "\""),
                "problem-1.tn's own !!id must match the constant a server serves it at");
    }

    /** On a clean instance -- {@link TsonProblemSchema#tson()} has already registered it, and registering twice is an error. */
    @Test
    void theSchemaResolves() {
        assertEquals(List.of(), Tson.builder().build().validateSchema(TsonProblemSchema.source()));
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
                List.of(Diagnostic.ofSchemaError("https://example.com/2026/32/app/order-1.tn", "order",
                        "missing required field 'sku'", Optional.empty())));

        byte[] written = codec.writeProblem(problem);
        assertTrue(new String(written, StandardCharsets.UTF_8).contains("SCHEMA_ERROR"));

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(written), "application/tson",
                TsonProblemSchema.ID, "problem", TsonProblem.class);
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
                List.of(Diagnostic.ofSchemaError("https://example.com/2026/32/app/order-1.tn", "order",
                        "missing required field 'sku'", Optional.empty())));

        String written = new String(codec.writeProblem(problem), StandardCharsets.UTF_8);
        assertTrue(written.startsWith("!!schema:\"" + TsonProblemSchema.ID + "\""), written);
        assertTrue(written.contains("!problem"), "and a root type-ref, or a reader cannot select a type: " + written);

        // read, not readObjectAs: no schema URI, no type name, no prior knowledge of either.
        TsonProblem readBack = codec.readObject(
                new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8)), "application/tson",
                TsonProblem.class);
        assertEquals(problem, readBack);
    }

    /** errors is a list because a 415 or a 406 produces no diagnostic at all, and must still be a valid body. */
    @Test
    void aProblemWithNoDiagnosticsIsStillValid() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonHttpException.unsupportedMediaType("this endpoint reads application/tson")
                .problem();
        assertEquals(List.of(), problem.errors());

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
        assertEquals(problem, readBack);
        assertEquals(415, readBack.status());
    }

    /** An absent detail must survive as an absence, not become an empty string. */
    @Test
    void anAbsentDetailStaysAbsent() {
        TsonHttpCodec codec = new TsonHttpCodec(TsonProblemSchema.tson());
        TsonProblem problem = TsonProblem.of(TsonHttpException.TYPES + "internal-error", 500, "Internal error", null, List.of());
        assertEquals(Optional.empty(), problem.detail());

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
        assertEquals(Optional.empty(), readBack.detail());
    }

    // ── the enum this schema copies ──────────────────────────────────────

    /**
     * Read through the real pipeline rather than by matching text: these are the members a reader will
     * actually enforce, which is the property that matters.
     */
    private static List<String> declaredCodes() {
        TypeDefinition entry = TsonProblemSchema.compiled().schema().entries().get("diagnostic_code");
        return assertInstanceOf(EnumBody.class, entry.body(), "diagnostic_code is an enum").members();
    }

    /**
     * {@code problem-4.tn}'s {@code diagnostic_code} is a hand-written copy of {@link Diagnostic.Code}, and
     * nothing else checks that the copy is current. Add a member upstream and forget this schema, and an error
     * body emits a code its own schema rejects -- which no other test here would catch, because no fixture has
     * ever produced a code that is new.
     *
     * <p><b>The Java enum is the source of truth</b>, which is why this asserts against it rather than against
     * tson-cli's schema. Two schemas checked against each other would only prove they drifted together, and
     * since {@code UPSTREAM.md} #5 they are free to diverge everywhere else.
     */
    @Test
    void everyDiagnosticCodeIsDeclaredInTheSchema() {
        List<String> declared = declaredCodes();
        for (Diagnostic.Code code : Diagnostic.Code.values()) {
            assertTrue(declared.contains(code.name()),
                    () -> "Diagnostic.Code." + code + " is missing from problem-4.tn's diagnostic_code: "
                            + declared + " -- add it there under a new schema version (\u00a710)");
        }
    }

    @Test
    void theSchemaDeclaresNoCodeTheEnumDoesNotHave() {
        List<String> known = Arrays.stream(Diagnostic.Code.values()).map(Enum::name).toList();
        for (String declared : declaredCodes()) {
            assertTrue(known.contains(declared),
                    () -> "problem-4.tn declares '" + declared + "', which is not a Diagnostic.Code: " + known
                            + " -- a value no reader can ever produce");
        }
    }
}
