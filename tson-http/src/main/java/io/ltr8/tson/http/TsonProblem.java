package io.ltr8.tson.http;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.Diagnostic;

import java.util.List;
import java.util.Optional;

/**
 * The body of an error response: the status, a short stable title for the class of failure, optional prose about
 * this occurrence, and every diagnostic that produced it. Written as {@code application/tson} against
 * {@code problem-1.tn}.
 *
 * <p><b>Every diagnostic goes in one body, not just the first.</b> The reader can collect rather than fail fast
 * (a {@code TsonDiagnosticsCollector}), and the target consumer of a 4xx here is a generate-validate-retry loop:
 * a client that gets told about one error per round trip needs one round trip per error. {@link TsonHttpCodec}
 * therefore reads with a collector and reports the whole list.
 *
 * <p>{@code status} is restated in the body so a problem that is stored, logged or forwarded is still
 * self-describing once separated from its response.
 */
@Typename(name = "problem")
public record TsonProblem(int status, String title, Optional<String> detail, List<TsonProblemDiagnostic> errors) {

    public TsonProblem {
        errors = List.copyOf(errors);
    }

    /** A problem carrying diagnostics -- the ordinary 4xx shape. */
    public static TsonProblem of(int status, String title, String detail, List<Diagnostic> diagnostics) {
        return new TsonProblem(status, title, Optional.ofNullable(detail).filter(text -> !text.isBlank()),
                diagnostics.stream().map(TsonProblemDiagnostic::from).toList());
    }
}
