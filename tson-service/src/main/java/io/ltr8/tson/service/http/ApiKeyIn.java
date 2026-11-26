package io.ltr8.tson.service.http;

import io.ltr8.annotation.Typename;

/**
 * Where an {@link ApiKey} travels. The constants are spelt as the schema's members are, in lower case, because an
 * enum binds by its constants' names.
 */
@Typename(name = "service.api_key_in")
public enum ApiKeyIn {
    header, query
}
