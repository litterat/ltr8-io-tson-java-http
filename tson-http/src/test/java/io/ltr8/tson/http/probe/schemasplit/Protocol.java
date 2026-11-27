package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.Namespace;

/** The protocol schema's {@code service.protocol}: one namespace-typed field holding its exchanges by name. */
public record Protocol(Namespace<Exchange> exchanges) {
}
