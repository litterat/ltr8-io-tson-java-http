package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** An endpoint whose body is no TSON value, labelled by its media types; it may still take a request record. */
public record Document(Optional<Integer> status, @Field("media_types") List<String> mediaTypes,
                       Optional<TypeRef> request) implements Endpoint {
}
