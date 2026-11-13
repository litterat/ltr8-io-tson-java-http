package io.ltr8.tson.http.api;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/**
 * An http projection, the value of an entry applying {@code service.http}: its resources keyed by path template, and
 * what it claims about the protocols behind it.
 *
 * <p>{@code binds} names the protocols it binds and {@code notBound} the exchanges of those it does not, each with a
 * reason. Both are fields, so every name in them is a reference the resolver judged at schema load, and each {@link
 * ValueRef} in {@code binds} is delivered the {@link Protocol} it names once the description links. That a claim
 * holds -- every exchange of a bound protocol bound or exempted -- is {@link TsonApiDescription}'s check.
 *
 * <p>{@code security} is the projection's default, the schemes every endpoint requires unless it writes its own
 * {@code security}, which replaces it; each reference is delivered its {@link SecurityScheme}. An absent list is
 * empty, and an empty default leaves an endpoint that writes none open.
 */
@Typename(name = "service.http")
public record Http(List<ValueRef<Protocol>> binds, @Field("not_bound") List<Exemption> notBound,
                   List<ValueRef<SecurityScheme>> security, Namespace<Resource> resources) {

    public Http {
        binds = binds == null ? List.of() : List.copyOf(binds);
        notBound = notBound == null ? List.of() : List.copyOf(notBound);
        security = security == null ? List.of() : List.copyOf(security);
    }
}
