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
 * <p><b>{@code description} is a component even though an operation is a schema entry</b>, where {@code @doc}
 * would be the natural home. {@code @doc} is dropped from resolved output — measured, and true of an ordinary
 * record and of this project's own {@code problem-1.tn} too ({@code UPSTREAM.md} #20) — so a consumer reading
 * a description back cannot see it. Locally declared annotations do survive, which is what makes that a gap
 * rather than a rule.
 */
@Typename(name = "operation")
public record Operation(HttpMethod method, String path, Optional<String> summary,
                        Optional<String> description, Optional<Boolean> deprecated,
                        List<Parameter> parameters, Optional<TypeRef> request,
                        List<Response> responses) implements Data {

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
