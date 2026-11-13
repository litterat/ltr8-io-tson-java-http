package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.TypeRef;

/** State a client reads, writes or observes: its type alone; what may be done with it is annotation on its key. */
public record Property(TypeRef type) implements Source {
}
