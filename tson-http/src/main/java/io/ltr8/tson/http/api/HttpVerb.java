package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * A request method (RFC 9110 §9), the ones a service description has a use for, called a verb so that {@code method}
 * names nothing in this vocabulary.
 */
@Typename(name = "http_verb")
public enum HttpVerb {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}
