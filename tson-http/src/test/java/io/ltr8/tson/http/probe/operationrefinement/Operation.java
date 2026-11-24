package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** An endpoint with its signature inline; an absent list arrives as {@code null} and is normalised. */
public record Operation(int status, @Field("media_types") List<String> mediaTypes, Optional<TypeRef> request,
                        Optional<TypeRef> response, List<TypeRef> errors) implements Endpoint {

    public Operation {
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
