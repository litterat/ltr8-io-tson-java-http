package io.ltr8.tson.http.probe;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A minimal operation shape, bound by {@code UpstreamGapsTest}'s probes so they can drive a namespace type of their
 * own without depending on the shipped meta layer's vocabulary, whose {@code operation} is a different record.
 *
 * <p>The class must be named {@code Operation}: {@code DefaultDataNameBinder} mangles the schema type name
 * to PascalCase and calls {@code Class.forName}, so {@link Typename} names the schema side but does not make
 * the class discoverable under a different name.
 */
@Typename(name = "operation")
public record Operation(String method, String path, List<TypeRef> responses) {
}
