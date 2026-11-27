package io.ltr8.tson.http.schema;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.service.schema.Exchange;

/**
 * Why an exchange an http projection claims through {@code binds} has no endpoint in it: one element of {@link
 * HttpProjection#notBound}. {@code exchange} is a {@code meta.ref<service.exchange>} the resolver judged, a {@link ValueRef}
 * holding the qualified name written ({@code orders_v2.exchanges.refund}); {@code TsonServiceDescription} checks that it
 * names an exchange of a protocol the projection binds.
 */
@Typename(name = "http.exemption")
public record Exemption(ValueRef<Exchange> exchange, String reason) {
}
