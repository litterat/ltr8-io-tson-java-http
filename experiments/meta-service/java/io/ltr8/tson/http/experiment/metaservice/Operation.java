package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The sketch's {@code operation}: an endpoint with its signature inline. */
@Typename(name = "operation")
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        boolean safe, boolean idempotent, boolean request_stream, boolean response_stream,
                        List<String> query, Map<String, String> headers, Optional<String> body,
                        int status, Optional<String> summary) implements Endpoint {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>(errors);
        request.ifPresent(all::add);
        response.ifPresent(all::add);
        return all;
    }
}
