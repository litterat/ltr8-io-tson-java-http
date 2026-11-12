package io.ltr8.tson.http.probe.namespacefield;

import io.ltr8.annotation.Field;
import io.ltr8.net.Iri;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/**
 * An http projection as a record: its claims are declared fields the resolver judges, and its resources a
 * namespace-typed field. An absent list arrives as {@code null}, so a reader normalises it as the api records do.
 */
public record Http(List<TypeRef> binds, @Field("not_bound") List<Exemption> notBound, Optional<Iri> base,
                   Namespace<Resource> resources) {
}
