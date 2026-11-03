package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An endpoint with its signature inline: the one-declaration form, for a projection with no protocol behind it. */
@Typename(name = "operation")
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        List<String> query, Map<String, String> headers, Optional<String> body, int status,
                        @Field("media_types") List<String> mediaTypes) implements Endpoint {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }
}
