package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** An endpoint whose body is no TSON value, declared wholly in the http schema. */
public record Document(List<String> query, int status, @Field("media_types") List<String> mediaTypes,
                       Optional<List<ValueRef<SecurityScheme>>> security, Optional<TypeRef> request)
        implements Endpoint {

    public Document {
        query = query == null ? List.of() : List.copyOf(query);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
    }
}
