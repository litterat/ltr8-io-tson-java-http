package io.ltr8.tson.http.probe.annotation;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method, held under a key of an interface, for {@code UpstreamGapsTest}'s annotation-reference probe. */
@Typename(name = "method")
public record Method(TypeRef request) {
}
