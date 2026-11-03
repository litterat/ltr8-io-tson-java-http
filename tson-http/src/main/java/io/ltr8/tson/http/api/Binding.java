package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose signature is an exchange's, named by a reference the resolver judged -- held as the qualified
 * name written, the protocol's entry and then the exchange's key ({@code orders.place_order}).
 */
@Typename(name = "binding")
public record Binding(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                      @Field("media_types") List<String> mediaTypes, TypeRef exchange) implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }
}
