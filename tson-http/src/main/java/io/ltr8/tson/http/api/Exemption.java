package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * Why a method an api claims through {@code implements} has no endpoint in it. {@code method} is a reference the
 * resolver judged, held as the qualified name written, so an exemption covers exactly the method it names.
 */
@Typename(name = "exemption")
public record Exemption(TypeRef method, String reason) {
}
