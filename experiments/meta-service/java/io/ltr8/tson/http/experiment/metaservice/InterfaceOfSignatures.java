package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/** A probe-only shape: the interface map with a plain {@code signature} record as its value type. */
@Typename(name = "interface_of_signatures")
public record InterfaceOfSignatures(AnnotatedMap<String, Signature> methods) implements Data {
    public InterfaceOfSignatures {
        methods = methods == null ? new AnnotatedMap<>() : methods;
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        methods.values().forEach(s -> {
            s.request().ifPresent(all::add);
            s.response().ifPresent(all::add);
            all.addAll(s.errors());
        });
        return all;
    }
}
