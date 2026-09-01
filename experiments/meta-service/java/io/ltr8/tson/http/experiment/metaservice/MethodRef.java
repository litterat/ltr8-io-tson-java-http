package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/** Probe-only: a method named on an interface, the interface stated only when two implemented ones share the name. */
@Typename(name = "method_ref")
public record MethodRef(String name, @io.ltr8.annotation.Field("interface") Optional<String> owner) {
}
