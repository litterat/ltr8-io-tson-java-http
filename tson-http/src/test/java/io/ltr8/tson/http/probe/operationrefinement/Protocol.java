package io.ltr8.tson.http.probe.operationrefinement;

import io.ltr8.tson.schema.meta.Namespace;

/** A protocol: the namespace of its exchanges. */
public record Protocol(Namespace<Exchange> exchanges) {
}
