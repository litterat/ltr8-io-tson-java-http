package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/** Probe-only ({@code InterfaceMapProbe}): an interface whose map's value type is the {@code ~data} constructor. */
@Typename(name = "interface_of_data_methods")
public record InterfaceOfDataMethods(AnnotatedMap<String, DataMethod> methods) implements Data {
    public InterfaceOfDataMethods {
        methods = methods == null ? new AnnotatedMap<>() : methods;
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        methods.values().forEach(m -> all.addAll(m.references()));
        return all;
    }
}
