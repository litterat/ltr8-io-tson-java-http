package io.ltr8.tson.http.probe.typeref;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A binding holding a value's name in a {@code TypeRef}, the host a value's name refuses: {@code UpstreamGapsTest}
 * measures the {@code BIND_MISMATCH} it is.
 */
@Typename(name = "binding")
public record Binding(TypeRef method) {
}
