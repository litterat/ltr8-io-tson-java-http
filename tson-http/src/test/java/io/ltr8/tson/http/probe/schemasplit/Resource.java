package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.AnnotatedMap;

/** One path's endpoints keyed by verb, each verb key keeping the annotations written before it. */
public record Resource(AnnotatedMap<String, Endpoint> endpoints) {
}
