package io.ltr8.tson.http.probe.affordance;

/** The endpoint base, bound to a type with no bare form, so an untagged endpoint is refused naming the subtypes. */
public sealed interface Endpoint permits Binding, Document, Stream, State, Interface {
}
