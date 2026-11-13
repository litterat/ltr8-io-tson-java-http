package io.ltr8.tson.http.api;

import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One endpoint of a description, resolved: the verb and the path it sits under, its signature -- inline for an
 * {@link Operation}, the exchange's for a {@link Binding} -- the media types it speaks, and everything {@link
 * TsonApiDescription} checked about it.
 *
 * @param exchange    the exchange a binding borrows; empty for an operation or a document
 * @param errors      each business error, by the status its type fixes
 * @param document    whether this is a {@link Document}, its success body bytes labelled by a media type rather than
 *                    a TSON value
 * @param mediaTypes  every media type the endpoint speaks, in both directions, folded to lower case:
 *                    {@code application/tson} first and always, then those listed, for an endpoint answering a TSON
 *                    value; exactly those listed for a document
 * @param summary     the short documentation: the verb key's {@code @service.summary}, else the exchange's
 * @param doc         the long documentation: the verb key's {@code @doc}, else the exchange's
 */
public record Route(HttpVerb verb, String path, Optional<ExchangeName> exchange, Optional<TypeRef> request,
                    Optional<TypeRef> response, Map<Integer, TypeRef> errors, int status, boolean document,
                    Set<String> mediaTypes, RequestPlacement placement, Optional<String> summary,
                    Optional<String> doc) {

    public Route {
        errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        mediaTypes = Collections.unmodifiableSet(new LinkedHashSet<>(mediaTypes));
    }

    /**
     * Whether this endpoint speaks TSON's JSON encoding under either of its labels, {@code application/json} or
     * {@code application/tson+json}: admits a JSON body, and answers in JSON when asked.
     */
    public boolean speaksJson() {
        return mediaTypes.contains(TsonMediaType.APPLICATION_JSON.toString())
                || mediaTypes.contains(TsonMediaType.APPLICATION_TSON_JSON.toString());
    }

    /** The business error this endpoint declares at {@code status}, if it declares one. */
    public Optional<TypeRef> errorFor(int status) {
        return Optional.ofNullable(errors.get(status));
    }

    /** How a server claims this endpoint and how a message names it: {@code POST /orders}. */
    public String key() {
        return key(verb, path);
    }

    static String key(HttpVerb verb, String path) {
        return verb + " " + path;
    }

    /** Every type this endpoint names: its request, its response, then its errors. */
    List<TypeRef> payloadTypes() {
        List<TypeRef> all = new ArrayList<>();
        request.ifPresent(all::add);
        response.ifPresent(all::add);
        all.addAll(errors.values());
        return all;
    }
}
