package io.ltr8.tson.http.probe.subtype;

import io.ltr8.annotation.Typename;

import java.util.Map;

/** A record holding the family's values in a plain map, so a subtype sits behind a record field. */
@Typename(name = "resource")
public record Resource(Map<String, Endpoint> endpoints) {
}
