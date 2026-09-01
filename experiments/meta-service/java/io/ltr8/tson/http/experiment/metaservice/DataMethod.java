package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Probe-only ({@code InterfaceMapProbe}): a method as a {@code ~data} constructor, the shape the sketch no longer uses. */
@Typename(name = "data_method")
public record DataMethod(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors) implements Data {
    public DataMethod {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>(errors);
        request.ifPresent(all::add);
        response.ifPresent(all::add);
        return all;
    }
}
