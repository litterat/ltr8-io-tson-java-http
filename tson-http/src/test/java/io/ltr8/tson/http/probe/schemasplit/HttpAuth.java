package io.ltr8.tson.http.probe.schemasplit;

/** An HTTP authentication scheme, by its registered name. */
public record HttpAuth(String scheme) implements SecurityScheme {
}
