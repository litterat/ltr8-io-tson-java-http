package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.Namespace;

/**
 * A protocol with its exchanges and two optional sibling namespaces, properties and events. An absent namespace
 * arrives as {@code null}.
 */
public record Protocol(Namespace<Exchange> exchanges, Namespace<Property> properties, Namespace<Event> events) {
}
