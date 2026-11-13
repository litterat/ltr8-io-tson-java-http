package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** A streaming endpoint: what it pushes is an event's payload or an observed property's value. */
public record Stream(Optional<Integer> status, @Field("media_types") List<String> mediaTypes,
                     ValueRef<Source> source) implements Endpoint {
}
