package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An endpoint with its signature inline: the one-declaration form, for an api with no interface behind it. */
@Typename(name = "operation")
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        List<String> query, Map<String, String> headers, Optional<String> body, int status,
                        List<Encoding> encodings) implements Endpoint {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        encodings = encodings == null ? List.of() : List.copyOf(encodings);
    }
}
