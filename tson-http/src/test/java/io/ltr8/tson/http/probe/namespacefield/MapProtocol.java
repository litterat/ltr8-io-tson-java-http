package io.ltr8.tson.http.probe.namespacefield;

import java.util.Map;
import java.util.Optional;

/** The same protocol with its namespace-typed {@code exchanges} bound to a plain {@link Map}. */
public record MapProtocol(Optional<String> version, Map<String, Exchange> exchanges) {
}
