package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/**
 * A second projection's record, standing in for a gRPC service definition: the protocol it projects and the
 * exchanges it carries, each a reference into a protocol the protocol schema types.
 */
public record RpcService(ValueRef<Protocol> protocol, List<ValueRef<Exchange>> methods) {
}
