package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * A key the client carries in a request header or a query parameter.
 *
 * @param in   where the key travels
 * @param name the header's or the query parameter's name, as written
 */
@Typename(name = "service.api_key")
public record ApiKey(ApiKeyIn in, String name) implements SecurityScheme {
}
