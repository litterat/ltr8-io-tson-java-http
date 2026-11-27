package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** The protocol schema's {@code service.exchange}: a signature and nothing more, a type of its own. */
public record Exchange(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {

    public Exchange {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
