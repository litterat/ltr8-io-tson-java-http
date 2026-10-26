package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One endpoint of a description, resolved: the verb and the path it sits under, its signature -- inline for an
 * {@link Operation}, the method's for a {@link Binding} -- or the media type of a {@link Document}, and everything
 * {@link TsonApiDescription} checked about it.
 *
 * @param method      the method a binding borrows; empty for an operation or a document
 * @param errors      each business error, by the status its type fixes
 * @param mediaType   a document's media type; empty for an endpoint answering a TSON value
 * @param encodings   every encoding spoken, TSON always among them
 * @param summary     the short documentation: the verb key's {@code @summary}, else the method's
 * @param doc         the long documentation: the verb key's {@code @doc}, else the method's
 */
public record Route(HttpVerb verb, String path, Optional<InterfaceMethod> method, Optional<TypeRef> request,
                    Optional<TypeRef> response, Map<Integer, TypeRef> errors, int status,
                    Optional<String> mediaType, Set<Encoding> encodings, RequestPlacement placement,
                    Optional<String> summary, Optional<String> doc) {

    public Route {
        errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        encodings = Set.copyOf(encodings);
    }

    /** Whether this endpoint also speaks TSON's JSON encoding: admits a JSON body, and answers in JSON when asked. */
    public boolean speaksJson() {
        return encodings.contains(Encoding.JSON);
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
