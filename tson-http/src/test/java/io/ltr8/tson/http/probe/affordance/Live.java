package io.ltr8.tson.http.probe.affordance;

/**
 * An object a running deployment serves at a concrete path: the base of the instance layer's object kinds, bound with
 * no bare form, so an untagged object is refused naming them.
 */
public sealed interface Live permits ServedDocument, ServedExchange, ServedProperty, ServedInterface, EventSource {
}
