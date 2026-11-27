package io.ltr8.tson.http.probe.schemasplit;

import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Optional;

/**
 * The http schema's abstract endpoint base, bound to a type with no bare form. Each subtype is declared in the http
 * schema; {@link Operation} composes the protocol schema's signature and {@link Wrapped} holds one as a field.
 */
public sealed interface Endpoint permits Operation, Binding, Document, Wrapped {

    /** Request fields carried in the query string. */
    List<String> query();

    /** The success status, 200 unless written. */
    int status();

    /** The media types listed, as written. */
    List<String> mediaTypes();

    /** The security schemes the endpoint names: empty when it writes none, present and empty when it writes {@code []}. */
    Optional<List<ValueRef<SecurityScheme>>> security();
}
