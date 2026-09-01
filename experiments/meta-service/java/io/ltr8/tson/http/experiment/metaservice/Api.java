package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The sketch's {@code api}: resources keyed by path template, and the interfaces it claims to implement. */
@Typename(name = "api")
public record Api(@Field("implements") List<String> implemented, @Field("not_bound") Map<String, String> notBound,
                  AnnotatedMap<String, Resource> resources) implements Data {

    public Api {
        implemented = implemented == null ? List.of() : List.copyOf(implemented);
        notBound = notBound == null ? Map.of() : Map.copyOf(notBound);
        resources = resources == null ? new AnnotatedMap<>() : resources;
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        resources.values().forEach(r -> all.addAll(r.references()));
        return all;
    }
}
