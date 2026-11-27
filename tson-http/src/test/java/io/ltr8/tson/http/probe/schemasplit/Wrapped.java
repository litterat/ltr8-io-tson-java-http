package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/**
 * An endpoint of the http schema holding the protocol schema's {@code service.signature} as a field rather than
 * composing it, so its {@code signature} component is a class bound to the other schema's type.
 */
public record Wrapped(List<String> query, int status, @Field("media_types") List<String> mediaTypes,
                      Optional<List<ValueRef<SecurityScheme>>> security, Signature signature) implements Endpoint {

    public Wrapped {
        query = query == null ? List.of() : List.copyOf(query);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
    }
}
