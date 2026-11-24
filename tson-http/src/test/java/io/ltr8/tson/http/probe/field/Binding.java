package io.ltr8.tson.http.probe.field;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;

/** A binding naming a method by reference, held in a {@code ValueRef} that is delivered the {@link Method} named. */
@Typename(name = "binding")
public record Binding(ValueRef<Method> method) {
}
