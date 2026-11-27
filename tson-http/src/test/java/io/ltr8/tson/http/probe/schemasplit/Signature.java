package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** The protocol schema's {@code service.signature}: a request, a response and business errors, each a type reference. */
public record Signature(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {

    public Signature {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
