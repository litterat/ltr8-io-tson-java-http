package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/** One route taking call documents for a whole protocol. */
public record Interface(Optional<Integer> status, @Field("media_types") List<String> mediaTypes,
                        ValueRef<Protocol> protocol) implements Endpoint {
}
