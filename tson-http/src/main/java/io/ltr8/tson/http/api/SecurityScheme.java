package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * How a client proves who it is to an endpoint: an {@link HttpAuth} or an {@link ApiKey}. The schema declares {@code
 * service.security_scheme} {@code abstract} and fieldless, so the resolver refuses an untagged scheme naming the
 * subtypes; this sealed interface is the Java side of the same fact, the binder's target for a scheme-typed slot.
 *
 * <p>A description declares a scheme as a value entry at its root, {@code bearer => !service.http_auth { scheme:
 * Bearer }}, and names it in {@link Http#security} or an endpoint's {@code security}; {@link Route#security()} holds
 * the schemes in force for one endpoint. Nothing in this library enforces a scheme: the description states it and
 * the reader reads it.
 */
@Typename(name = "service.security_scheme")
public sealed interface SecurityScheme permits HttpAuth, ApiKey {
}
