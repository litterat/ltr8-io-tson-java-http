package io.ltr8.tson.http.probe.namespacefield;

import io.ltr8.tson.schema.meta.TypeRef;

/** Why a claimed exchange has no endpoint, held in a field of {@link Http} rather than in an annotation. */
public record Exemption(TypeRef exchange, String reason) {
}
