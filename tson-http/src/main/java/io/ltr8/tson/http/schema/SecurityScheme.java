package io.ltr8.tson.http.schema;

import io.ltr8.annotation.Typename;

/**
 * How a client proves who it is to an endpoint: an {@link HttpAuth} or an {@link ApiKey}. The schema declares {@code
 * http.security_scheme} {@code abstract} and fieldless, so the resolver refuses an untagged scheme naming the
 * subtypes; this sealed interface is the Java side of the same fact, the binder's target for a scheme-typed slot.
 *
 * <p>A description declares a scheme as a value entry at its root, {@code bearer => !http.auth { scheme: Bearer }},
 * and names it in {@link HttpProjection#security} or an endpoint's {@code security}; {@code Route.security()} holds
 * the schemes in force for one endpoint. Nothing in this library enforces a scheme: the description states it and
 * the reader reads it.
 */
@Typename(name = "http.security_scheme")
public sealed interface SecurityScheme permits HttpAuth, ApiKey {
}
