package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** An endpoint reading or writing a property, by the verb it sits under. */
public record State(Optional<Integer> status, @Field("media_types") List<String> mediaTypes,
                    ValueRef<Property> property) implements Endpoint {
}
