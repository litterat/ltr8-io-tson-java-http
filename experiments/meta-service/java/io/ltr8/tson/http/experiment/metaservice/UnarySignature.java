package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** Probe-only: {@code signature ^ { request_stream: = false  response_stream: = false }}, bound under its own name. */
@Typename(name = "unary_signature")
public record UnarySignature(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                             boolean safe, boolean idempotent, boolean request_stream, boolean response_stream) {
    public UnarySignature {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
