package io.ltr8.tson.service.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint with its signature inline: the one-declaration form, for a projection with no protocol behind it.
 *
 * <p>An absent list or map is empty, but an absent {@code security} is {@link Optional#empty()}, which inherits the
 * projection's, and {@code security: []} is a present empty list, which says the endpoint is open.
 */
@Typename(name = "service.operation")
public record Operation(Optional<TypeRef> request, Optional<TypeRef> response, List<TypeRef> errors,
                        List<String> query, Map<String, String> headers, Optional<String> body, int status,
                        @Field("media_types") List<String> mediaTypes,
                        Optional<List<ValueRef<SecurityScheme>>> security,
                        @Field("response_headers") Map<String, String> responseHeaders) implements Endpoint {

    public Operation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }
}
