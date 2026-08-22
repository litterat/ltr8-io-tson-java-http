package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        TsonProblem problem = TsonProblem.of(400, "Invalid TSON document", "the request body has 1 problem",
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
        TsonProblem problem = TsonProblem.of(400, "Invalid TSON document", "the request body has 1 problem",
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
        TsonProblem problem = TsonProblem.of(500, "Internal error", null, List.of());
        assertEquals(Optional.empty(), problem.detail());

        TsonProblem readBack = codec.readObjectAs(new ByteArrayInputStream(codec.writeProblem(problem)),
                "application/tson", TsonProblemSchema.ID, "problem", TsonProblem.class);
        assertEquals(Optional.empty(), readBack.detail());
    }
}
