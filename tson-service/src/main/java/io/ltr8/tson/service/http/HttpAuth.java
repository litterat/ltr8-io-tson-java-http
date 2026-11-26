package io.ltr8.tson.service.http;

import io.ltr8.annotation.Typename;

/**
 * An HTTP authentication scheme (RFC 9110 §11): the client sends {@code Authorization: <scheme> <credentials>}.
 *
 * @param scheme the scheme's registered name, held folded as RFC 9110 §11.1 compares it: {@code bearer}, {@code
 *               basic}
 */
@Typename(name = "service.http_auth")
public record HttpAuth(String scheme) implements SecurityScheme {
}
