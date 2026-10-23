package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** {@code bind => { m: ref<meth> }}, with the host shape a reference is delivered into. */
@Typename(name = "bind")
public record Bind(TypeRef m) {
}
