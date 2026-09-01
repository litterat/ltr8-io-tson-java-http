package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * The sketch's {@code resource}: one path's endpoints, keyed by verb.
 *
 * <p>The keys are {@code String}, not {@link HttpVerb}, although the schema types them {@code http_verb}: the
 * binder hands a map's keys back as text whatever the key type, so a {@code Map<HttpVerb, …>} component would
 * hold strings and lie about it. {@link Routes} converts, and a key that is not a verb is refused there.
 */
@Typename(name = "resource")
public record Resource(AnnotatedMap<String, Endpoint> endpoints) {

    public Resource {
        endpoints = endpoints == null ? new AnnotatedMap<>() : endpoints;
    }

    /** Every endpoint's references, handed on to the owning api. */
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        endpoints.values().forEach(e -> all.addAll(e.references()));
        return all;
    }
}
