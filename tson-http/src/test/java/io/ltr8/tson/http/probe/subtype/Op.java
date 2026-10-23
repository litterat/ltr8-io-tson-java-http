package io.ltr8.tson.http.probe.subtype;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** The family's one subtype, declaring the kind-typed slot its base lacks. */
@Typename(name = "op")
public record Op(TypeRef request) implements Endpoint {
}
