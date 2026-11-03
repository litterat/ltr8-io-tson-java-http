package io.ltr8.tson.http.probe.annotation;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** An exchange, held under a key of a protocol, for {@code UpstreamGapsTest}'s annotation-reference probe. */
@Typename(name = "exchange")
public record Exchange(TypeRef request) {
}
