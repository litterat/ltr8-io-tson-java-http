package io.ltr8.tson.http.probe.operationrefinement;

import java.util.Map;

/** An http projection: its resources keyed by path template. */
public record Http(Map<String, Resource> resources) {
}
