package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each field of a request record lands in the HTTP request an endpoint describes -- the check the resolver
 * cannot make, because an endpoint names its fields by identifier and its path is a map key.
 *
 * <p>Precedence: path segments, then {@code query}, then {@code headers}, then {@code body}; whatever is left is
 * the remainder, which goes to the body unless the verb is GET or HEAD or {@code body} already named a field, in
 * which case it goes to the query. Every field lands exactly once, and a field in the path, the query or a
 * header must be a scalar. A name the request does not declare, a field placed twice, or a container where a
 * scalar is needed is refused with the field and the entry named. An entry with no request admits no placement
 * at all, so a path segment on one is refused too.
 *
 * <p><b>A dependent field ({@code payload: kind!}) travels in the body, as part of the remainder record, and its
 * sibling travels with it.</b> Its type is chosen per instance by the value of {@code kind} in the same record
 * ([TSON-SCHEMA] §5.2), so no type is known when the binding is read: the scalar check a path segment, a query
 * parameter or a header needs has nothing to check, and a body that is the field alone ({@code body: payload})
 * has no type to be read against. Only the remainder body is a record instance holding both fields, which is
 * where the reader judges one against the other. So a dependent field placed in the path, the query or a header,
 * named as {@code body}, or left to a remainder that goes to the query is refused, and so is its sibling placed
 * anywhere but the remainder body. The refusal names the field, its sibling and where it was placed.
 *
 * <p>A request that is not a record is refused where it is read rather than left to fail as an empty field set:
 * the whole mapping is a distribution of one record's fields, so {@code request: text} places nothing, and
 * silently placing nothing reads as a working endpoint that carries no data.
 */
public record RequestPlacement(Map<String, Location> fields) {

    /** Where one request field travels. */
    public enum Location { PATH, QUERY, HEADER, BODY }

    /** A template variable, the operator stripped: {@code {+schemaPath}} places {@code schemaPath} as one field. */
    private static final Pattern SEGMENT = Pattern.compile("\\{[+]?([^}]+)}");

    static RequestPlacement of(String name, HttpVerb verb, String path, List<String> query, Map<String, String> headers,
                        Optional<String> body, Optional<TypeRef> request,
                        Function<String, Top> entries) {
        Map<String, RecordField> declared = new LinkedHashMap<>();
        request.ifPresent(ref -> {
            Top type = entries.apply(ref.name());
            if (type == null || !(type instanceof RecordBody record)) {
                throw new IllegalArgumentException("'" + name + "' takes '" + ref.text() + "', which is not a record"
                        + " -- a request is the record whose fields an endpoint distributes over the HTTP request,"
                        + " so an exchange taking one value declares a one-field record for it");
            }
            declared.putAll(record.fields());
        });
        Map<String, Location> placed = new LinkedHashMap<>();
        Matcher segments = SEGMENT.matcher(path);
        while (segments.find()) {
            place(name, placed, declared, segments.group(1), Location.PATH, entries);
        }
        query.forEach(field -> place(name, placed, declared, field, Location.QUERY, entries));
        headers.keySet().forEach(field -> place(name, placed, declared, field, Location.HEADER, entries));
        body.ifPresent(field -> place(name, placed, declared, field, Location.BODY, entries));

        boolean remainderIsQuery = verb == HttpVerb.GET || verb == HttpVerb.HEAD || body.isPresent();
        // Walked in the request record's order, so the result reads as the record does; Map.copyOf would
        // forget it.
        Map<String, Location> inRecordOrder = new LinkedHashMap<>();
        for (String field : declared.keySet()) {
            Location where = placed.getOrDefault(field, remainderIsQuery ? Location.QUERY : Location.BODY);
            if (where == Location.QUERY && !placed.containsKey(field)) {
                requireScalar(name, field, declared.get(field), where, entries);
            }
            inRecordOrder.put(field, where);
        }
        boolean remainderIsBody = !remainderIsQuery;
        declared.forEach((field, declaration) -> declaration.sibling().ifPresent(sibling -> {
            String spelled = declaration.type().text();
            requireInRemainderBody(name, field, spelled, sibling, field, inRecordOrder.get(field), placed,
                    remainderIsBody);
            requireInRemainderBody(name, field, spelled, sibling, sibling, inRecordOrder.get(sibling), placed,
                    remainderIsBody);
        }));
        return new RequestPlacement(Collections.unmodifiableMap(inRecordOrder));
    }

    private static void place(String entry, Map<String, Location> placed, Map<String, RecordField> declared,
                              String field, Location where, Function<String, Top> entries) {
        if (!declared.containsKey(field)) {
            throw new IllegalArgumentException("'" + entry + "' places '" + field + "' in the " + where
                    + ", but the request declares no such field -- it declares " + declared.keySet());
        }
        Location already = placed.putIfAbsent(field, where);
        if (already != null) {
            throw new IllegalArgumentException("'" + entry + "' places '" + field + "' twice: in the " + already
                    + " and in the " + where);
        }
        if (where != Location.BODY) {
            requireScalar(entry, field, declared.get(field), where, entries);
        }
    }

    /**
     * Refuses {@code placedField} -- the dependent field {@code dependent}, typed {@code spelled}, or its sibling
     * {@code sibling} -- anywhere but the remainder body, the one place both arrive in one record instance.
     */
    private static void requireInRemainderBody(String entry, String dependent, String spelled, String sibling,
                                               String placedField, Location where, Map<String, Location> placed,
                                               boolean remainderIsBody) {
        boolean inRemainderBody = where == Location.BODY && remainderIsBody && !placed.containsKey(placedField);
        if (inRemainderBody) {
            return;
        }
        String how = where == Location.BODY ? "as the whole body"
                : placed.containsKey(placedField) ? "in the " + where : "in the " + where + " as the remainder";
        String which = placedField.equals(dependent) ? "'" + dependent + "'"
                : "'" + placedField + "', the sibling of '" + dependent + "',";
        throw new IllegalArgumentException("'" + entry + "' places " + which + " " + how + ", but '" + dependent
                + ": " + spelled + "' is a dependent field whose type its sibling names per instance -- it"
                + " travels in the remainder body with '" + sibling + "', where one record holds both");
    }

    /**
     * A URL segment carries exactly one scalar; a query parameter and a header may repeat, so either admits an
     * array of scalars as well -- {@code ?tag=a&tag=b} is ordinary HTTP, and refusing it would push a
     * multi-valued parameter into the body where no GET can carry it. A record, a map, a tuple, or an array of
     * any of those has no spelling in any of the three.
     */
    private static void requireScalar(String entry, String name, RecordField field, Location where,
                                      Function<String, Top> entries) {
        if (field.sibling().isPresent()) {
            return;                                     // a dependent field: requireInRemainderBody refuses it
        }
        Top type = body(field.type(), entries);
        if (type == null) {
            return;                                     // a built-in: text, int32, a date -- scalar by construction
        }
        if (type instanceof ArrayBody array) {
            if (where == Location.PATH) {
                refuse(entry, name, where, "an array, and a URL segment carries exactly one scalar");
            }
            if (isContainer(body(array.elementType(), entries))) {
                refuse(entry, name, where, "an array of " + array.elementType().text()
                        + ", and a repeated " + where.name().toLowerCase(Locale.ROOT) + " value carries a scalar");
            }
            return;
        }
        if (isContainer(type)) {
            refuse(entry, name, where, "a " + field.type().text() + ", which is not a scalar");
        }
    }

    /**
     * Why {@code field} is not one scalar, if it is not: what a URL segment carries, and what a response header's
     * template expands a variable from. A dependent field, an array, a record, a map and a tuple are not.
     */
    static Optional<String> notOneScalar(RecordField field, Function<String, Top> entries) {
        if (field.sibling().isPresent()) {
            return Optional.of("a dependent field, whose type its sibling names per instance");
        }
        Top type = body(field.type(), entries);
        if (type instanceof ArrayBody) {
            return Optional.of("an array");
        }
        return isContainer(type) ? Optional.of("a " + field.type().text() + ", which is not a scalar") : Optional.empty();
    }

    /** The type {@code type} stands for: its entry when it is named, its body when it is written in place. */
    private static Top body(TypeExpr type, Function<String, Top> entries) {
        return switch (type) {
            case TypeRef reference -> entries.apply(reference.name());
            case TypeInline inline -> inline.of();
        };
    }

    private static boolean isContainer(Top type) {
        return type != null && (type instanceof RecordBody || type instanceof ArrayBody
                || type instanceof MapBody || type instanceof TupleBody);
    }

    private static void refuse(String entry, String field, Location where, String because) {
        throw new IllegalArgumentException("'" + entry + "' places '" + field + "' in the " + where
                + ", but it is " + because);
    }

    /** The fields at {@code where}, in the request record's order. */
    public List<String> at(Location where) {
        return fields.entrySet().stream().filter(e -> e.getValue() == where).map(Map.Entry::getKey).toList();
    }
}
