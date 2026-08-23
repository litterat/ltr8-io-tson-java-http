package io.ltr8.tson.http.apimeta;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A parameter carried outside the body. */
@Typename(name = "parameter")
public record Parameter(String name, ParameterLocation in, TypeRef type, boolean required) {
}
