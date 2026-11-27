package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/**
 * The http schema's projection record: its claims on protocols the protocol schema types, its default security
 * schemes and its resources keyed by path template.
 */
public record Http(List<ValueRef<Protocol>> binds, @Field("not_bound") List<Exemption> notBound,
                   List<ValueRef<SecurityScheme>> security, AnnotatedMap<String, Resource> resources) {

    public Http {
        binds = binds == null ? List.of() : List.copyOf(binds);
        notBound = notBound == null ? List.of() : List.copyOf(notBound);
        security = security == null ? List.of() : List.copyOf(security);
    }
}
