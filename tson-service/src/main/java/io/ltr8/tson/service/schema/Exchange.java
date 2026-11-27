package io.ltr8.tson.service.schema;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/**
 * An exchange: one request-and-reply, a signature held under its name in a {@link Protocol}'s {@code exchanges}. Its
 * documentation and the facts it states about itself ({@code @service.safe}, {@code @service.idempotent} and {@code
 * @service.destructive}) are annotations on that key, read from {@link Protocol#exchanges} and resolved onto each
 * {@code TsonRoute} that binds it.
 */
@Typename(name = "service.exchange")
public record Exchange(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {

    public Exchange {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
