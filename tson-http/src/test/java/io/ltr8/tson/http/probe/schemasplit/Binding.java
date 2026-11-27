package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** An endpoint naming an exchange the protocol schema types, by a reference the resolver judges. */
public record Binding(List<String> query, int status, @Field("media_types") List<String> mediaTypes,
                      Optional<List<ValueRef<SecurityScheme>>> security, ValueRef<Exchange> exchange)
        implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
    }
}
