package io.ltr8.tson.http.probe.field;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A binding naming a method by reference, the host shape a reference is delivered into. */
@Typename(name = "binding")
public record Binding(TypeRef method) {
}
