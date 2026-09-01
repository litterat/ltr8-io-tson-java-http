package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sketch's {@code operation}: a value under its verb, under its path, in an api. It names a {@link #method}
 * on an implemented interface or carries its signature inline; {@link Routes} enforces one-or-the-other.
 */
@Typename(name = "operation")
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        boolean safe, boolean idempotent, boolean request_stream, boolean response_stream,
                        List<String> query, Map<String, String> headers, Optional<String> body,
                        Optional<String> method, @Field("interface") Optional<String> owner,
                        int status, Optional<String> summary) implements Data {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** True when the signature is written here rather than borrowed from a method. */
    public boolean isInline() {
        return request.isPresent() || response.isPresent() || !errors.isEmpty();
    }

    @Override
    public List<TypeRef> references() {
        List<TypeRef> all = new ArrayList<>(errors);
        request.ifPresent(all::add);
        response.ifPresent(all::add);
        return all;
    }
}
