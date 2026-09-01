package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/** Probe-only ({@code NameRoleProbe}): a map of methods keyed by a particular naming role. */
@Typename(name = "by_text")
public record ByText(AnnotatedMap<String, Method> methods) implements Data {
    public ByText {
        methods = methods == null ? new AnnotatedMap<>() : methods;
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        methods.values().forEach(m -> all.addAll(m.references()));
        return all;
    }
}
