package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The sketch's {@code binding}: an HTTP binding for a method declared elsewhere, named by identifier. */
@Typename(name = "binding")
public record Binding(String method, HttpVerb verb, String path, List<Parameter> parameters, int status,
                      Optional<String> summary) implements Data {

    public Binding {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** {@code method} is a {@code type_name}, not a reference -- it is deliberately not handed to the linker. */
    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        parameters.forEach(p -> all.add(p.type()));
        return all;
    }
}
