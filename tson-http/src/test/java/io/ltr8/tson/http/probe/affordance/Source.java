package io.ltr8.tson.http.probe.affordance;

/**
 * What a streaming endpoint names: the empty base of {@link Property} and {@link Event}, so one {@code meta.ref<source>}
 * slot reaches either.
 */
public sealed interface Source permits Property, Event {
}
