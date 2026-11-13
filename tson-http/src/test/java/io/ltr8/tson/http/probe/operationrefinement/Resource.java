package io.ltr8.tson.http.probe.operationrefinement;

import java.util.Map;

/** One path's endpoints, keyed by verb, each typed by the base. */
public record Resource(Map<String, Endpoint> endpoints) {
}
