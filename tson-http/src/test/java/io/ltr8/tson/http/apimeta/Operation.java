package io.ltr8.tson.http.apimeta;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Java side of {@code sketch/meta-http-1.tn}'s {@code operation} constructor.
 *
 * <p>Three things register it and there is nothing else: this class carrying {@link Typename} naming the
 * constructor, implementing {@link Data} so the resolver knows the entry is not a type, and a
 * {@code DataNameBinder} that can find it. No reader family, no factory — the ordinary record reader binds an
 * {@code !operation { … }} payload straight into this record.
 *
 * <p><b>{@link #references()} is what makes the type names real.</b> The linker follows what it returns, so
 * naming a type nothing declares is an author error at schema load — which is the whole reason to describe an
 * API at the schema layer rather than beside it.
 */
@Typename(name = "operation")
public record Operation(HttpMethod method, String path, List<Parameter> parameters,
                        Optional<TypeRef> request, List<Response> responses) implements Data {

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
}
