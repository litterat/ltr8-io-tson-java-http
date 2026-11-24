package io.ltr8.tson.http.probe.namespacefield;

import io.ltr8.tson.schema.meta.Namespace;

import java.util.Optional;

/**
 * A protocol as a record: a declared field beside the namespace-typed {@code exchanges}, bound to the library's
 * {@link Namespace}, which keeps the order the keys were written in and each key's annotations.
 */
public record Protocol(Optional<String> version, Namespace<Exchange> exchanges) {
}
