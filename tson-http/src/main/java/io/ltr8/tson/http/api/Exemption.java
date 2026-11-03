package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * Why an exchange an http projection claims through {@code @binds} has no endpoint in it: one element of the
 * annotation {@code not_bound} on the projection's entry. {@code exchange} is the qualified name written ({@code
 * orders.refund}). It sits in an annotation's value, where the resolver does not judge a reference, so {@link
 * TsonApiDescription} checks it names an exchange of a protocol the projection binds.
 */
@Typename(name = "exemption")
public record Exemption(TypeRef exchange, String reason) {
}
