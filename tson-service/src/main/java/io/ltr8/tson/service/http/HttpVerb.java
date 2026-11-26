package io.ltr8.tson.service.http;

import io.ltr8.annotation.Typename;

/**
 * A request method (RFC 9110 §9), the ones a service description has a use for, called a verb so that {@code method}
 * names nothing in this vocabulary.
 */
@Typename(name = "service.http_verb")
public enum HttpVerb {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}
