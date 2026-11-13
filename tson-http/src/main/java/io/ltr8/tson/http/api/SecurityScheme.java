package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * How a client proves who it is to an endpoint: an {@link HttpAuth} or an {@link ApiKey}. A sealed interface, so the
 * binder has a target for a {@code service.security_scheme}-typed slot and an untagged scheme has nothing to
 * construct -- the base record declares no field, and this binding is what makes it abstract.
 *
 * <p>A description declares a scheme as a value entry at its root, {@code bearer => !service.http_auth { scheme:
 * Bearer }}, and names it in {@link Http#security} or an endpoint's {@code security}; {@link Route#security()} holds
 * the schemes in force for one endpoint. Nothing in this library enforces a scheme: the description states it and
 * the reader reads it.
 */
@Typename(name = "service.security_scheme")
public sealed interface SecurityScheme permits HttpAuth, ApiKey {
}
