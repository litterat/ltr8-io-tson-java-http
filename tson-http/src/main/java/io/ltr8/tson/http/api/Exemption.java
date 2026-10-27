package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * Why a method an api claims through {@code @implements} has no endpoint in it: one element of the annotation {@code
 * not_bound} on the api's entry. {@code method} is the qualified name written ({@code orders.refund}). It sits in an
 * annotation's value, where the resolver does not judge a reference, so {@link TsonApiDescription} checks it names a
 * method of an interface the api implements.
 */
@Typename(name = "exemption")
public record Exemption(TypeRef method, String reason) {
}
