package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sketch's {@code endpoint}: the base record of {@link Operation} and {@link Binding}, what sits under a verb
 * in a resource. Bound to a sealed interface, found by name, so the binder has a target for an
 * {@code endpoint}-typed slot and a bare {@code !endpoint} has nothing to construct: the schema alone would admit
 * one, and it is this binding that makes the base abstract.
 */
@Typename(name = "endpoint")
public sealed interface Endpoint permits Operation, Binding {

    /** The type references this endpoint's signature carries, handed to the linker by the owning api. */
    List<TypeRef> references();

    List<String> query();

    Map<String, String> headers();

    Optional<String> body();

    int status();
}
