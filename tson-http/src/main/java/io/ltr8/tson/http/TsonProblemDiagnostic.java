package io.ltr8.tson.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
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
 * <p><b>{@code fetch_reason} is the one member that is not a location, and it is present for one code.</b>
 * A {@code SCHEMA_UNAVAILABLE} says a schema could not be obtained; the reason says whether that is the
 * document's doing or the world's, which is the difference between telling a sender to fix its reference and
 * telling it to try again. It is on the wire rather than consumed and dropped because the status alone
 * compresses it -- {@code NOT_PERMITTED} and {@code NOT_FOUND} share a 400, {@code TRANSPORT} and {@code
 * TOO_LARGE} a 502 -- and a client that wants to log or retry intelligently should not have to infer which.
 *
 * <p><b>{@code unicode_data_version} is the other, present for the three name-hygiene codes.</b> §8.2
 * requires a refusal to name the data it was computed against, and §8.3 is why: all three rules are unstable
 * across Unicode releases, so two conforming processors may legitimately disagree about one name and the
 * version is what explains it. Which rule fired needs no field — it is the code, one per rule.
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
                                    @Field("schema_position") Optional<String> schemaPosition,
                                    @Field("fetch_reason")
                                    Optional<TsonSchemaFetchException.Reason> fetchReason,
                                    @Field("unicode_data_version") Optional<String> unicodeDataVersion) {

    /** {@code diagnostic} as it goes on the wire. */
    public static TsonProblemDiagnostic from(Diagnostic diagnostic) {
        return new TsonProblemDiagnostic(diagnostic.path(), diagnostic.schemaPointer(),
                diagnostic.schemaIdIfKnown(),
                diagnostic.code(), diagnostic.message(),
                diagnostic.expectedIfStated(), diagnostic.actualIfStated(),
                diagnostic.dataPosition().map(TsonProblemDiagnostic::render),
                diagnostic.schemaPosition().map(TsonProblemDiagnostic::render),
                diagnostic.fetchReason(), diagnostic.unicodeDataVersion());
    }

    /** The position format {@code problem-1.tn} states for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
