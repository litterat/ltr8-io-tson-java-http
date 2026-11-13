package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.ValueRef;

/** A path a client subscribes to for an event's occurrences. */
public record EventSource(ValueRef<Event> event) implements Live {
}
