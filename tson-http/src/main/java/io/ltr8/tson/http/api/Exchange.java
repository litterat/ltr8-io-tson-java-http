package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/**
 * An exchange: one request-and-reply, a signature held under its name in an entry applying {@code
 * service.protocol}. Its documentation and the facts it states about itself ({@code @service.safe} and
 * {@code @service.idempotent}) are annotations on that key, read from the entry's {@code Namespace}.
 */
@Typename(name = "service.exchange")
public record Exchange(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {

    public Exchange {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
