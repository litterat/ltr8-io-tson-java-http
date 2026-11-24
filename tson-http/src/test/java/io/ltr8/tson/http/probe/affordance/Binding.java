package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** An endpoint naming an exchange. */
public record Binding(Optional<Integer> status, @Field("media_types") List<String> mediaTypes,
                      ValueRef<Exchange> exchange) implements Endpoint {
}
