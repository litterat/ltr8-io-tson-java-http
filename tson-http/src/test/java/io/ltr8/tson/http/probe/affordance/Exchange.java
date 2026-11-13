package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/** An exchange of the probe's layer: a request and a response, each a type reference. */
public record Exchange(Optional<TypeRef> request, Optional<TypeRef> response) {
}
