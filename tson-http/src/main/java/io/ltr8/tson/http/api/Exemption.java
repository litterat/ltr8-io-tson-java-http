package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;

/**
 * Why an exchange an http projection claims through {@code @service.binds} has no endpoint in it: one element of
 * the annotation {@code service.not_bound} on the projection's entry. {@code exchange} holds the qualified name
 * written ({@code orders.refund}) and is never delivered: it sits in an annotation's value, which the resolver neither
 * judges nor links, so {@link ValueRef#isResolved} is false and {@link TsonApiDescription} checks the name names an
 * exchange of a protocol the projection binds.
 */
@Typename(name = "service.exemption")
public record Exemption(ValueRef<Exchange> exchange, String reason) {
}
