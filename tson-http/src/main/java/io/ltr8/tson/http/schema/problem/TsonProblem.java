package io.ltr8.tson.http.schema.problem;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.Diagnostic;

import java.util.List;
import java.util.Optional;

/**
 * The body of an error response, written as {@code application/tson} against {@code problem.tn}'s
 * {@code problem.details}.
 *
 * <p><b>RFC 9457 (Problem Details for HTTP APIs), plus {@code errors}.</b> Following the standard means
 * ordinary HTTP tooling recognises the body, and {@code errors} is not an invention either -- §3.1 of that RFC
 * uses an {@code errors} extension for exactly this, a list of validation failures within one problem.
 *
 * <p><b>{@link #type()} is the member to match on.</b> It identifies the class of failure, is stable where
 * {@link #title()} is prose that may be reworded, and dereferences. Absent means RFC 9457's {@code about:blank}:
 * no semantics beyond the status code.
 *
 * <p><b>Every diagnostic goes in one body, not just the first.</b> The reader can collect rather than fail fast,
 * and the target consumer of a 4xx here is a generate-validate-retry loop: a client told about one error per
 * round trip needs one round trip per error.
 *
 * <p>{@code status} is restated in the body so a problem that is stored, logged or forwarded is still
 * self-describing once separated from its response.
 *
 * <p><b>{@code errors} is optional in the schema and never absent here.</b> This class writes the list on every
 * problem, empty where a failure produced no diagnostic, and a body that omits it, as a business error does, reads
 * as an empty list: the binder hands an omitted optional list to the constructor as {@code null}, and the
 * constructor normalises it.
 */
@Typename(name = "problem.details")
public record TsonProblem(Optional<String> type, String title, int status, Optional<String> detail,
                          Optional<String> instance, List<TsonProblemDiagnostic> errors) {

    public TsonProblem {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** A problem carrying diagnostics -- the ordinary 4xx shape. */
    public static TsonProblem of(String type, int status, String title, String detail,
                                 List<Diagnostic> diagnostics) {
        return new TsonProblem(present(type), title, status, present(detail), Optional.empty(),
                diagnostics.stream().map(TsonProblemDiagnostic::from).toList());
    }

    /** This problem, identified as having occurred at {@code instance} -- a request path, typically. */
    public TsonProblem at(String instance) {
        return new TsonProblem(type, title, status, detail, present(instance), errors);
    }

    private static Optional<String> present(String value) {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }
}
