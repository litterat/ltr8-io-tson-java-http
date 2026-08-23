package io.ltr8.tson.http.apimeta;

import io.ltr8.annotation.Typename;

@Typename(name = "http_method")
public enum HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}
