package io.ltr8.tson.service.http;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.service.protocol.Exchange;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An endpoint whose signature is an exchange's. {@code exchange} is a {@code meta.ref<service.exchange>} the resolver
 * judged: a {@link ValueRef} holding the qualified name written, the protocol's entry, its {@code exchanges} field and
 * the exchange's key ({@code orders.exchanges.place_order}), and delivered the {@link Exchange} it names once the
 * description links. {@link TsonServiceDescription} reads the exchange from the referent, and which protocol it belongs
 * to from the name's first segment.
 *
 * <p>An absent list or map is empty, but an absent {@code security} is {@link Optional#empty()}, which inherits the
 * projection's, and {@code security: []} is a present empty list, which says the endpoint is open.
 */
@Typename(name = "service.binding")
public record Binding(List<String> query, Map<String, String> headers, Optional<String> body, int status,
                      @Field("media_types") List<String> mediaTypes, Optional<List<ValueRef<SecurityScheme>>> security,
                      @Field("response_headers") Map<String, String> responseHeaders,
                      ValueRef<Exchange> exchange) implements Endpoint {

    public Binding {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        security = security == null ? Optional.empty() : security.map(List::copyOf);
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }
}
