package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** An endpoint whose body is no TSON value, labelled by its media types. */
public record Document(int status, @Field("media_types") List<String> mediaTypes, Optional<TypeRef> request)
        implements Endpoint {

    public Document {
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
    }
}
