package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * A request method (RFC 9110 §9), the ones an API description has a use for. A verb, not a method: in this
 * vocabulary a method is what an interface declares, and {@code { method: POST }} would say two things at once.
 */
@Typename(name = "http_verb")
public enum HttpVerb {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}
