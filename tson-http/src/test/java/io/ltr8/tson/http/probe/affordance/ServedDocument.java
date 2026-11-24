package io.ltr8.tson.http.probe.affordance;

import io.ltr8.annotation.Field;

import java.util.Optional;

/** A document at a path: its media type and, for a schema, the identity it is published under. */
public record ServedDocument(@Field("media_type") String mediaType, Optional<String> identity) implements Live {
}
