package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/** An exchange of the probe's layer: a signature and nothing more. */
public record Exchange(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) {
}
