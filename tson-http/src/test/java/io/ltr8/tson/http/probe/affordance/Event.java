package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.TypeRef;

/** Output the server initiates: the type of what it pushes. */
public record Event(TypeRef payload) implements Source {
}
