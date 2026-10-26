package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose success body is not a TSON value -- a schema document served as the bytes it was published as,
 * a page -- labelled by its media type where an operation names a response type.
 */
@Typename(name = "document")
public record Document(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                       List<Encoding> encodings, Optional<TypeRef> request, List<TypeRef> errors,
                       @Field("media_type") String mediaType) implements Endpoint {

    public Document {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        encodings = encodings == null ? List.of() : List.copyOf(encodings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
