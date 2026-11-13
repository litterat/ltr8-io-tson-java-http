package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/** An endpoint whose signature is an exchange's, named by a reference the resolver judges. */
public record Binding(int status, @Field("media_types") List<String> mediaTypes, ValueRef<Exchange> exchange)
        implements Endpoint {

    public Binding {
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }
}
