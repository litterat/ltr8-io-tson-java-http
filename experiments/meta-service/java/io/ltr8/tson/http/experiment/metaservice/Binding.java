package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sketch's {@code binding}: an endpoint whose signature is a method's, named by a reference the resolver
 * judged -- held as the qualified name written ({@code orders.orders.methods.place_order}).
 */
@Typename(name = "binding")
public record Binding(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                      TypeRef method) implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Nothing here is a type reference: {@code method} names a value, and the read judged it. */
    public List<TypeRef> references() {
        return List.of();
    }
}
