package io.ltr8.tson.http.probe.namespacefield;

import io.ltr8.tson.schema.meta.ValueRef;

import java.util.Optional;

/** An endpoint naming an exchange by a reference the resolver judges, walked through a protocol's field. */
public record Binding(ValueRef<Exchange> exchange, Optional<Integer> status) {
}
