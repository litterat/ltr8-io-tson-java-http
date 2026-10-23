package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** The probe's one record held by a namespace type: a step naming the type it takes. */
@Typename(name = "step")
public record Step(TypeRef takes) {
}
