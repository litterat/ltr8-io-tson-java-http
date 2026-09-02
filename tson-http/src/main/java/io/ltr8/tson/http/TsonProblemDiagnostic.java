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
 * for {@code schemaId}/{@code expected}/{@code actual}, and now says so once itself --
 * {@link Diagnostic#schemaIdIfKnown()}, {@link Diagnostic#expectedIfStated()},
 * {@link Diagnostic#actualIfStated()} -- so this narrows through those rather than repeating the convention.
 * The two RFC 6901 pointers need no narrowing: they are already {@link Optional} at the source, because for a
 * pointer {@code ""} is not absence but the root, which a document-level schema problem genuinely carries.
 *
 * <p><b>Every member is a location or the problem itself.</b> There is no payload here meaningful to one code:
 * why a schema could not be obtained is carried by the code, one per reason ({@code SCHEMA_NOT_PERMITTED},
 * {@code SCHEMA_NOT_FOUND}, {@code SCHEMA_UNREACHABLE}, {@code SCHEMA_TIMEOUT}, {@code SCHEMA_TOO_LARGE}),
 * which is what a client routes on. A field beside the code would be a second carrier for one fact, free to
 * disagree with the first -- and on the wire it would let {@code code} and the reason contradict each other in
 * a body this schema still called valid.
 *
 * <p><b>A §8.2 refusal carries its code and nothing about the policy that judged it.</b> Which rule fired is
 * the code, one per rule ({@code CONFUSABLE_NAMES}, {@code RESTRICTED_CHARACTER}, {@code RESTRICTED_SCRIPT}).
 * The level, the unit and the Unicode data version are properties of the processor, constant for its life,
 * and are stated once rather than on every refusal -- {@code Tson.processorPolicy()} in process, and the
 * deployment's acceptance profile at {@code /.well-known/tson-deployment} over the wire -- because a sender
 * needs them before it writes, not after it is refused.
 *
 * <p>Not held to {@code tson-cli}'s {@code CliDiagnostic}, which it began as a copy of: a CLI reports on files
 * and a server reports on requests, so the two are free to diverge. What it must stay in step with is
 * {@link Diagnostic} itself, and {@code TsonProblemSchemaTest} is what checks that.
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
                diagnostic.schemaIdIfKnown(),
                diagnostic.code(), diagnostic.message(),
                diagnostic.expectedIfStated(), diagnostic.actualIfStated(),
                diagnostic.dataPosition().map(TsonProblemDiagnostic::render),
                diagnostic.schemaPosition().map(TsonProblemDiagnostic::render));
    }

    /** The position format {@code problem-1.tn} states for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
