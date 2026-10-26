package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/**
 * The transport-neutral contract of one callable thing: what {@link Method} and an inline {@link Operation}
 * share. Its slots are typed {@code type_kind}, so each name is a type the resolver judged; nothing here
 * declares that.
 */
@Typename(name = "signature")
public record Signature(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {

    public Signature {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
