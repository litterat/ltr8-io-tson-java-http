package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** The sketch's {@code signature} record, bound so a map of them can be read back. */
@Typename(name = "signature")
public record Signature(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        boolean safe, boolean idempotent, boolean request_stream, boolean response_stream) {
    public Signature {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
