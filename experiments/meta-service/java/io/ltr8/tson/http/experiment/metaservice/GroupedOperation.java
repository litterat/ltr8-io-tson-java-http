package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Probe-only: an operation whose signature comes ONE of two ways, said by a field group rather than by a reader. */
@Typename(name = "grouped_operation")
public record GroupedOperation(Optional<MethodRef> method, Optional<UnarySignature> signature,
                               List<String> query, Map<String, String> headers, Optional<String> body,
                               int status, Optional<String> summary) implements Data {
    public GroupedOperation {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>();
        signature.ifPresent(s -> {
            s.request().ifPresent(all::add);
            s.response().ifPresent(all::add);
            all.addAll(s.errors());
        });
        return all;
    }
}
