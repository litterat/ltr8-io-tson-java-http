package io.ltr8.tson.http.probe.field;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method, the value a reference reaches, bound for {@code UpstreamGapsTest}'s field-step probe. */
@Typename(name = "method")
public record Method(TypeRef request) {
}
