package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.ValueRef;

/** Why a claimed exchange has no endpoint: a reference into a protocol the protocol schema types, and a reason. */
public record Exemption(ValueRef<Exchange> exchange, String reason) {
}
