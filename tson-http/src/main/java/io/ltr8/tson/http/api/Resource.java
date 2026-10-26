package io.ltr8.tson.http.api;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Typename;

/**
 * One path's endpoints, keyed by verb.
 *
 * <p>The keys are {@code String}, not {@link HttpVerb}, although the schema types them {@code http_verb}: the
 * binder hands a map's keys back as text whatever the key type, so a {@code Map<HttpVerb, …>} component would hold
 * strings and lie about it. {@link TsonApiDescription} converts.
 */
@Typename(name = "resource")
public record Resource(AnnotatedMap<String, Endpoint> endpoints) {

    public Resource {
        endpoints = endpoints == null ? new AnnotatedMap<>() : endpoints;
    }
}
