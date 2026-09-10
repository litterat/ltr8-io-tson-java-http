package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
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
 * Where each field of a request record lands in the HTTP request an operation describes -- the check the
 * resolver cannot make, because an operation names its fields by identifier and its path is a map key.
 *
 * <p>Precedence: path segments, then {@code query}, then {@code headers}, then {@code body}; whatever is left is
 * the remainder, which goes to the body unless the verb is GET or HEAD or {@code body} already named a field, in
 * which case it goes to the query. Every field lands exactly once, and a field in the path, the query or a
 * header must be a scalar. A name the request does not declare, a field placed twice, or a container where a
 * scalar is needed is refused with the field and the entry named. An entry with no request admits no placement
 * at all, so a path segment on one is refused too.
 *
 * <p>A request that is not a record is refused where it is read rather than left to fail as an empty field set:
 * the whole mapping is a distribution of one record's fields, so {@code request: text} places nothing, and
 * silently placing nothing reads as a working endpoint that carries no data.
 */
record Placement(Map<String, Location> fields) {

    enum Location { PATH, QUERY, HEADER, BODY }

    private static final Pattern SEGMENT = Pattern.compile("\\{([^}]+)}");

    static Placement of(String name, HttpVerb verb, String path, List<String> query, Map<String, String> headers,
                        Optional<String> body, Optional<TypeRef> request,
                        Function<String, TypeDefinition> entries) {
        Map<String, RecordField> declared = new LinkedHashMap<>();
        request.ifPresent(ref -> {
            TypeDefinition type = entries.apply(ref.name());
            if (type == null || !(type.body() instanceof RecordBody record)) {
                throw new IllegalArgumentException("'" + name + "' takes '" + ref.name() + "', which is not a record"
                        + " -- a request is the record whose fields an endpoint distributes over the HTTP request,"
                        + " so a method taking one value declares a one-field record for it");
            }
            record.fields().forEach(field -> declared.put(field.name(), field));
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
                requireScalar(name, declared.get(field), where, entries);
            }
            inRecordOrder.put(field, where);
        }
        return new Placement(Collections.unmodifiableMap(inRecordOrder));
    }

    private static void place(String entry, Map<String, Location> placed, Map<String, RecordField> declared,
                              String field, Location where, Function<String, TypeDefinition> entries) {
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
            requireScalar(entry, declared.get(field), where, entries);
        }
    }

    /**
     * A URL segment carries exactly one scalar; a query parameter and a header may repeat, so either admits an
     * array of scalars as well -- {@code ?tag=a&tag=b} is ordinary HTTP, and refusing it would push a
     * multi-valued parameter into the body where no GET can carry it. A record, a map, a tuple, or an array of
     * any of those has no spelling in any of the three.
     */
    private static void requireScalar(String entry, RecordField field, Location where,
                                      Function<String, TypeDefinition> entries) {
        TypeDefinition type = entries.apply(field.type().name());
        if (type == null) {
            return;                                     // a built-in: text, int32, a date -- scalar by construction
        }
        if (type.body() instanceof ArrayBody array) {
            if (where == Location.PATH) {
                refuse(entry, field, where, "an array, and a URL segment carries exactly one scalar");
            }
            if (isContainer(entries.apply(array.elementType().name()))) {
                refuse(entry, field, where, "an array of " + array.elementType().name()
                        + ", and a repeated " + where.name().toLowerCase(Locale.ROOT) + " value carries a scalar");
            }
            return;
        }
        if (isContainer(type)) {
            refuse(entry, field, where, "a " + field.type().name() + ", which is not a scalar");
        }
    }

    private static boolean isContainer(TypeDefinition type) {
        return type != null && (type.body() instanceof RecordBody || type.body() instanceof ArrayBody
                || type.body() instanceof MapBody || type.body() instanceof TupleBody);
    }

    private static void refuse(String entry, RecordField field, Location where, String because) {
        throw new IllegalArgumentException("'" + entry + "' places '" + field.name() + "' in the " + where
                + ", but it is " + because);
    }

    /** The fields at {@code where}, in the request record's order. */
    List<String> at(Location where) {
        return fields.entrySet().stream().filter(e -> e.getValue() == where).map(Map.Entry::getKey).toList();
    }
}
