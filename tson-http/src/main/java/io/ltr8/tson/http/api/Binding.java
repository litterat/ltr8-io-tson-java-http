package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose signature is a method's, named by a reference the resolver judged -- held as the qualified name
 * written, the interface's entry and then the method's key ({@code orders.place_order}).
 */
@Typename(name = "binding")
public record Binding(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                      List<Encoding> encodings, TypeRef method) implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        encodings = encodings == null ? List.of() : List.copyOf(encodings);
    }
}
