package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose success body is not a TSON value -- a schema document served as the bytes it was published as,
 * a page -- labelled by the media types it lists where an operation names a response type. {@code application/tson}
 * is not implied for a document, so {@link TsonApiDescription} refuses one listing none.
 */
@Typename(name = "service.document")
public record Document(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                       @Field("media_types") List<String> mediaTypes, Optional<TypeRef> request,
                       List<TypeRef> errors) implements Endpoint {

    public Document {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
