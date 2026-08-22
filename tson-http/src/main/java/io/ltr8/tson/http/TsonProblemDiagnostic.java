package io.ltr8.tson.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * The on-the-wire shape of one {@link Diagnostic} inside a {@link TsonProblem} -- same fields, with
 * {@code dataPosition}/{@code schemaPosition} pre-rendered to {@code "line:column:byteOffset"} strings, matching
 * {@code problem-1.tn}'s own {@code text} fields.
 *
 * <p><b>An absent field is absent, not empty.</b> {@link Diagnostic} spells "nothing to say here" as {@code ""}
 * for {@code schemaId}/{@code expected}/{@code actual}; those are narrowed to {@link Optional} here. The two RFC
 * 6901 pointers need no narrowing -- they are already {@link Optional} at the source, because for a pointer
 * {@code ""} is not absence but the root, which a document-level schema problem genuinely carries.
 *
 * <p>Field-for-field identical to {@code tson-cli}'s own {@code CliDiagnostic}, deliberately: the CLI and this
 * server describe the same failure the same way. It is duplicated rather than shared because {@code tson-cli}
 * exports nothing (see {@code UPSTREAM.md} #5).
 */
@Typename(name = "diagnostic")
public record TsonProblemDiagnostic(Optional<String> path, @Field("schema_pointer") Optional<String> schemaPointer,
                                    @Field("schema_id") Optional<String> schemaId,
                                    Diagnostic.Code code, String message,
                                    Optional<String> expected, Optional<String> actual,
                                    @Field("data_position") Optional<String> dataPosition,
                                    @Field("schema_position") Optional<String> schemaPosition) {

    /** {@code diagnostic} as it goes on the wire. */
    public static TsonProblemDiagnostic from(Diagnostic diagnostic) {
        return new TsonProblemDiagnostic(diagnostic.path(), diagnostic.schemaPointer(),
                absentIfEmpty(diagnostic.schemaId()),
                diagnostic.code(), diagnostic.message(),
                absentIfEmpty(diagnostic.expected()), absentIfEmpty(diagnostic.actual()),
                diagnostic.dataPosition().map(TsonProblemDiagnostic::render),
                diagnostic.schemaPosition().map(TsonProblemDiagnostic::render));
    }

    /** {@link Diagnostic}'s "nothing to say here" for a string component, as an absence. */
    private static Optional<String> absentIfEmpty(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The position format {@code problem-1.tn} states for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
