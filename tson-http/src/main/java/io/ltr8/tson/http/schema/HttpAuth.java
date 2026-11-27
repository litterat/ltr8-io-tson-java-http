package io.ltr8.tson.http.schema;

import io.ltr8.annotation.Typename;

/**
 * An HTTP authentication scheme (RFC 9110 §11): the client sends {@code Authorization: <scheme> <credentials>}.
 *
 * @param scheme the scheme's registered name, held folded as RFC 9110 §11.1 compares it: {@code bearer}, {@code
 *               basic}
 */
@Typename(name = "http.auth")
public record HttpAuth(String scheme) implements SecurityScheme {
}
