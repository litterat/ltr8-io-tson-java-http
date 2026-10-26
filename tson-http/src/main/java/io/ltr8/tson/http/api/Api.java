package io.ltr8.tson.http.api;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A web service: resources keyed by path template, the interfaces it claims to implement (references, held as the
 * qualified names written), and the methods it exempts from that claim.
 */
@Typename(name = "api")
public record Api(@Field("implements") List<TypeRef> implemented,
                  @Field("not_bound") List<Exemption> notBound,
                  AnnotatedMap<String, Resource> resources) {

    public Api {
        implemented = implemented == null ? List.of() : List.copyOf(implemented);
        notBound = notBound == null ? List.of() : List.copyOf(notBound);
        resources = resources == null ? new AnnotatedMap<>() : resources;
    }
}
