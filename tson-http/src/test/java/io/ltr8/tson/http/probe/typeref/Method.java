package io.ltr8.tson.http.probe.typeref;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method, bound beside {@link Binding} so that its mismatch is the only one. */
@Typename(name = "method")
public record Method(TypeRef request) {
}
