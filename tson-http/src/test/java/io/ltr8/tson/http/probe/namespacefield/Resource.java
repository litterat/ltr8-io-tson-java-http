package io.ltr8.tson.http.probe.namespacefield;

import java.util.Map;

/** One path's endpoints, keyed by verb; a map's keys bind as {@code String} whatever the key type. */
public record Resource(Map<String, Binding> endpoints) {
}
