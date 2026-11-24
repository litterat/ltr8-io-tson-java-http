package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.ValueRef;

/** A path answering an exchange, referring back to the exchange in the description. */
public record ServedExchange(ValueRef<Exchange> exchange) implements Live {
}
