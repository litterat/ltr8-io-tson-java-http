package io.ltr8.tson.http;

import io.ltr8.tson.http.schema.Binding;
import io.ltr8.tson.http.schema.Document;
import io.ltr8.tson.http.schema.HttpVerb;
import io.ltr8.tson.http.schema.Operation;
import io.ltr8.tson.http.schema.SecurityScheme;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.service.ExchangeName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * One endpoint of a description, resolved: the verb and the path it sits under, its signature -- inline for an
 * {@link Operation}, the exchange's for a {@link Binding} -- the facts it states about itself, the media types it
 * speaks, and everything {@link TsonServiceDescription} checked about it.
 *
 * @param exchange    the exchange a binding borrows; empty for an operation or a document
 * @param errors      each business error, by the status its type fixes
 * @param safe        whether the exchange is marked {@code @service.safe}: the exchange's own key for a binding, the
 *                    verb key for an operation or a document
 * @param idempotent  whether it is marked {@code @service.idempotent}, or safe, which implies it
 * @param destructive whether it is marked {@code @service.destructive}: its effect cannot be undone, and a caller
 *                    should confirm before invoking it. Never true with {@code safe}
 * @param document    whether this is a {@link Document}, its success body bytes labelled by a media type rather than
 *                    a TSON value
 * @param mediaTypes  every media type the endpoint speaks, in both directions, folded to lower case:
 *                    {@code application/tson} first and always, then those listed, for an endpoint answering a TSON
 *                    value; exactly those listed for a document
 * @param summary     the short documentation: the verb key's {@code @service.summary}, else the exchange's
 * @param doc         the long documentation: the verb key's {@code @doc}, else the exchange's
 * @param security    the security schemes in force: the endpoint's {@code security} when it writes one, else its
 *                    projection's; empty when the endpoint is open. Read, not enforced
 * @param responseHeaders the headers a success response carries, each folded name to its declared value: an RFC
 *                    6570 level-1 template whose every variable is a scalar field of {@link #response()}, or a
 *                    literal; {@link #expandResponseHeaders} expands them
 */
public record Route(HttpVerb verb, String path, Optional<ExchangeName> exchange, Optional<TypeRef> request,
                    Optional<TypeRef> response, Map<Integer, TypeRef> errors, int status, boolean safe,
                    boolean idempotent, boolean destructive, boolean document, Set<String> mediaTypes,
                    RequestPlacement placement, Optional<String> summary, Optional<String> doc,
                    List<SecurityScheme> security, Map<String, String> responseHeaders) {

    public Route {
        errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        mediaTypes = Collections.unmodifiableSet(new LinkedHashSet<>(mediaTypes));
        security = List.copyOf(security);
        responseHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders));
    }

    /**
     * Every response header this endpoint declares, its template expanded (RFC 6570 §3.2.2) against the response
     * value's fields: {@code fields} answers a field's value as text for a name, or {@code null} for an absent one,
     * which expands to nothing. A literal is its own expansion, so a server sets the result on a success reply
     * whatever the endpoint declares; {@code fields} is asked only for the variables a template names, each checked
     * at startup to be a scalar field of {@link #response()}.
     */
    public Map<String, String> expandResponseHeaders(Function<String, String> fields) {
        Map<String, String> expanded = new LinkedHashMap<>();
        responseHeaders.forEach((name, template) -> expanded.put(name, HeaderTemplate.expand(template, fields)));
        return expanded;
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
