package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/** An http projection: the protocols it binds and its resources. */
public record Http(List<ValueRef<Protocol>> binds, Namespace<Resource> resources) {
}
