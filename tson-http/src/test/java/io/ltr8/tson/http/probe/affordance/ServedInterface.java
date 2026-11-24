package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.ValueRef;

/** A path taking call documents for a whole protocol. */
public record ServedInterface(ValueRef<Protocol> protocol) implements Live {
}
