package io.ltr8.tson.http.probe.affordance;

import java.util.Map;

/** One path's endpoints, keyed by verb. */
public record Resource(Map<String, Endpoint> endpoints) {
}
