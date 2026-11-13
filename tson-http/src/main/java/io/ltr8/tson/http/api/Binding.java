package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose signature is an exchange's. {@code exchange} is a {@code ref<exchange>} the resolver judged: a
 * {@link ValueRef} holding the qualified name written, the protocol's entry and then the exchange's key ({@code
 * orders.place_order}), and delivered the {@link Exchange} it names once the description links. {@link
 * TsonApiDescription} reads the name.
 */
@Typename(name = "binding")
public record Binding(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                      @Field("media_types") List<String> mediaTypes, ValueRef<Exchange> exchange) implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }
}
