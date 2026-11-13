package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.tson.schema.meta.Namespace;

/** An http projection: its resources keyed by path template. */
public record Http(Namespace<Resource> resources) {
}
