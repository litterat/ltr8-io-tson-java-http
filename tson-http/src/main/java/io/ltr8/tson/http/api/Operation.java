package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One HTTP operation, written by a schema governed by {@code meta-http-1.tn} and read back from that
 * schema's resolved entries.
 *
 * <p><b>{@link #references()} is what makes the payload types real.</b> The linker follows what it returns,
 * so naming a type nothing declares is an author error at schema load — the one thing a description written
 * as data cannot have, since a data document can name a schema but cannot hold a reference to a type.
 *
 * <p>There is no {@code description} component: an operation is a schema <em>entry</em>, so {@code @doc}
 * carries its long form and {@link TsonApiDescription#doc} reads it back from the entry's annotations.
 */
@Typename(name = "operation")
public record Operation(HttpMethod method, String path, Optional<String> summary,
                        Optional<Boolean> deprecated, List<Parameter> parameters,
                        Optional<TypeRef> request, List<Response> responses) implements Data {

    /**
     * Normalises an omitted {@code parameters} to empty.
     *
     * <p><b>A bound class guards its own optional lists</b> — the convention this library follows for
     * {@code RecordBody}'s {@code groups}/{@code supertypes}, and for {@code TypeDefinition} and
     * {@code TypeRef}. An optional field the document omits reaches the constructor as {@code null}, and
     * absent and {@code []} are the same value, so the guard belongs here rather than being a rule the
     * binder guesses at.
     *
     * <p>Worth doing rather than leaving: {@link #references()} walks {@code parameters}, and it runs inside
     * schema resolution — so an unguarded null is an NPE out of {@code Tson.resolve}, which reads as a
     * library fault when it is a document legitimately omitting an optional field.
     *
     * <p>{@code responses} is REQUIRED and deliberately not guarded: a required field never reaches a
     * constructor, because the reader reports {@code FIELD_REQUIRED} and abandons the construction first.
     * Guarding it would mask a real violation rather than absorb a legitimate absence.
     */
    public Operation {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        request.ifPresent(all::add);
        parameters.forEach(parameter -> all.add(parameter.type()));
        responses.forEach(response -> response.body().ifPresent(all::add));
        return List.copyOf(all);
    }

    /** This operation's declared response for {@code status}, or empty if it declares none. */
    public Optional<Response> responseFor(int status) {
        return responses.stream().filter(response -> response.status() == status).findFirst();
    }

    /** Whether this operation is marked deprecated; absent means no. */
    public boolean isDeprecated() {
        return deprecated.orElse(false);
    }
}
