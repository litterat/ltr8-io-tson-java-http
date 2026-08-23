package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * A parameter carried outside the body.
 *
 * <p>{@code type} names a scalar and nothing enforces that — a URL segment cannot carry a record, and the
 * type system has no way to say so. Stating the limit beats papering over it with expressiveness nothing can
 * honour.
 */
@Typename(name = "parameter")
public record Parameter(String name, ParameterLocation in, TypeRef type, boolean required,
                        Optional<String> description) {
}
