package io.ltr8.tson.http.probe.annotation;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A record carrying a {@code ref<exchange>}, written inside an annotation's value by the same probe. */
@Typename(name = "exemption")
public record Exemption(TypeRef exchange, String reason) {
}
