package io.ltr8.tson.http.probe.field;

import io.ltr8.annotation.Typename;

import java.util.Map;

/** An interface keeping its methods in a namespace-typed field, so a reference to one passes a record field. */
@Typename(name = "iface")
public record Iface(Map<String, Method> methods) {
}
