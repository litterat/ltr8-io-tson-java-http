package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * The sketch's {@code exemption}: why a claimed method has no endpoint in this api. {@code method} is a reference
 * the resolver judged, held as the qualified name written, so an exemption covers exactly the method it names.
 */
@Typename(name = "exemption")
public record Exemption(TypeRef method, String reason) {
}
