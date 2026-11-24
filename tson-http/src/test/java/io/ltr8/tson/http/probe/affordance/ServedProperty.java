package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.ValueRef;

/** A path holding a property's current state. */
public record ServedProperty(ValueRef<Property> property) implements Live {
}
