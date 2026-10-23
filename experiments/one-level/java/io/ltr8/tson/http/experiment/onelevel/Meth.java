package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method in {@link NamespaceTemplateProbe}: a plain record, the value type of the namespace. */
@Typename(name = "meth")
public record Meth(TypeRef request) {
}
