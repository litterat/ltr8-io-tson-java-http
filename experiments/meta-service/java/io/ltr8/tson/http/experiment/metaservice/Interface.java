package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/** The sketch's {@code interface}: a named, documented map of methods, plus the interfaces it extends. */
@Typename(name = "interface")
public record Interface(@Field("extends") List<String> extended, AnnotatedMap<String, Method> methods)
        implements Data {

    public Interface {
        extended = extended == null ? List.of() : List.copyOf(extended);
        methods = methods == null ? new AnnotatedMap<>() : methods;
    }

    /** Every method's references, handed on -- the values are not entries, so nothing else links them. */
    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        methods.values().forEach(m -> all.addAll(m.references()));
        return all;
    }
}
