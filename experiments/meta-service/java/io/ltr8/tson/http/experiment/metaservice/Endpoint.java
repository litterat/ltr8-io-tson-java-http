package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sketch's {@code endpoint}: the base of {@link Operation} and {@link Binding}, what sits under a verb in a
 * resource. Bound to a sealed interface, found by name, so the binder has a target for an {@code endpoint}-typed
 * slot and a bare {@code !endpoint} has nothing to construct -- which is what the schema says too.
 */
@Typename(name = "endpoint")
public sealed interface Endpoint extends Data permits Operation, Binding {

    List<String> query();

    Map<String, String> headers();

    Optional<String> body();

    int status();

    Optional<String> summary();
}
