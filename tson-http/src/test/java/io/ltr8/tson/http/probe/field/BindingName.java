package io.ltr8.tson.http.probe.field;

import io.ltr8.annotation.Typename;

/** The same reference slot as {@link Binding}, bound to a {@code String}: it receives the qualified name as text. */
@Typename(name = "binding_name")
public record BindingName(String method) {
}
