package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Namespace;

/**
 * A protocol: a named set of exchanges, the value of an entry applying {@code service.protocol}. {@code exchanges} is
 * the namespace a qualified name walks, {@code orders.exchanges.place_order}: the protocol's entry, this field, then
 * the exchange's key. A {@link Namespace} rather than a {@code Map}, because it keeps the order the exchanges were
 * written in and the annotations on each key, where an exchange's documentation and its facts ({@code
 * @service.safe}, {@code @service.idempotent}, {@code @service.destructive}) sit.
 */
@Typename(name = "service.protocol")
public record Protocol(Namespace<Exchange> exchanges) {
}
