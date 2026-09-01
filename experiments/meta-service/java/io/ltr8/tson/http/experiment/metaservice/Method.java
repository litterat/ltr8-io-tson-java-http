package io.ltr8.tson.http.experiment.metaservice;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
@Typename(name = "method")
public record Method(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                     boolean safe, boolean idempotent, boolean request_stream, boolean response_stream) implements Data {
    public Method { errors = errors == null ? List.of() : List.copyOf(errors); }
    @Override public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>(errors);
        request.ifPresent(all::add);
        response.ifPresent(all::add);
        return all;
    }
}
