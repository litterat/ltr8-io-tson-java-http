package io.ltr8.tson.http.probe.namespacefield;

import io.ltr8.annotation.AnnotatedMap;

import java.util.Optional;

/** The same protocol with its namespace-typed {@code exchanges} bound to an {@link AnnotatedMap}. */
public record AnnotatedMapProtocol(Optional<String> version, AnnotatedMap<String, Exchange> exchanges) {
}
