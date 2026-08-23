package io.ltr8.tson.http.apimeta;

import io.ltr8.annotation.Typename;

@Typename(name = "parameter_location")
public enum ParameterLocation {
    PATH, QUERY, HEADER
}
