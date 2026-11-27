package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** An endpoint composing the endpoint base with the protocol schema's {@code service.signature}, across the import. */
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        List<String> query, int status, @Field("media_types") List<String> mediaTypes,
                        Optional<List<ValueRef<SecurityScheme>>> security) implements Endpoint {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
    }
}
