package io.ltr8.tson.http.probe.affordance;

import io.ltr8.tson.schema.meta.Namespace;

/** What one running deployment serves: a namespace of live objects keyed by concrete path. */
public record Instance(String name, Namespace<Live> objects) {
}
