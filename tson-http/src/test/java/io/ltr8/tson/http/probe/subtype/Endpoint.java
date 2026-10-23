package io.ltr8.tson.http.probe.subtype;

import io.ltr8.annotation.Typename;

/** A record family base with no field of its own, bound for {@code UpstreamGapsTest}'s subtype probe. */
@Typename(name = "endpoint")
public sealed interface Endpoint permits Op {
}
