package io.ltr8.tson.service.http;

import io.ltr8.tson.schema.TsonLinkedSchema;
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
 *
 * <p>A request named through an extern ([TSON-SCHEMA] §5.13) is placed as the foreign record the extern lands on:
 * its fields are that record's, and each field's type is looked up in the foreign schema's namespace, where its
 * declaration wrote it, so a field typed by a record that schema declares is no scalar in the path. A field typed by
 * an extern is judged as what the extern lands on, in a request of either kind, and every type expression is resolved
 * in the schema declaring the entry holding it ({@link Landed}): an array's element type where the array is declared,
 * so a field typed by an extern of an array of records declared in a third schema is refused in the query, whatever
 * the request record's own schema declares under the element's name.
 *
 * <p>A plain alias is judged as the entry it reaches past every hop ({@link Landed#pastAliases()}): a request that
 * aliases a record is placed as that record, a field typed by an alias of a record or an array is no scalar in the
 * path, and an array of an alias of a record has no query spelling. A refusal names the type as it was written.
 */
public record RequestPlacement(Map<String, Location> fields) {

    /** Where one request field travels. */
    public enum Location { PATH, QUERY, HEADER, BODY }

    /** A template variable, the operator stripped: {@code {+schemaPath}} places {@code schemaPath} as one field. */
    private static final Pattern SEGMENT = Pattern.compile("\\{[+]?([^}]+)}");

    /**
     * The placement of {@code request}'s fields at the endpoint {@code name}. A request reached through an extern or a
     * plain alias is the record it lands on; its fields' types are judged in the schema declaring that record, and
     * each name such a type's definition writes in the schema declaring that definition ({@link Landed}).
     *
     * @param scope the description, in whose namespace {@code request} is written
     */
    static RequestPlacement of(String name, HttpVerb verb, String path, List<String> query, Map<String, String> headers,
                               Optional<String> body, Optional<TypeRef> request, TsonLinkedSchema scope) {
        Map<String, RecordField> declared = new LinkedHashMap<>();
        // No request: no field is declared to judge, so the landing is never asked a name.
        Landed landed = request.map(ref -> Landed.of(scope, ref.name()).pastAliases())
                .orElseGet(() -> new Landed(scope, ""));
        if (request.isPresent()) {
            TypeRef ref = request.get();
            if (!(landed.definition() instanceof RecordBody record)) {
                throw new IllegalArgumentException("'" + name + "' takes '" + ref.text() + "', which is not a record"
                        + " -- a request is the record whose fields an endpoint distributes over the HTTP request,"
                        + " so an exchange taking one value declares a one-field record for it");
            }
            declared.putAll(record.fields());
        }
        Map<String, Location> placed = new LinkedHashMap<>();
        Matcher segments = SEGMENT.matcher(path);
        while (segments.find()) {
            place(name, placed, declared, segments.group(1), Location.PATH, landed);
        }
        query.forEach(field -> place(name, placed, declared, field, Location.QUERY, landed));
        headers.keySet().forEach(field -> place(name, placed, declared, field, Location.HEADER, landed));
        body.ifPresent(field -> place(name, placed, declared, field, Location.BODY, landed));

        boolean remainderIsQuery = verb == HttpVerb.GET || verb == HttpVerb.HEAD || body.isPresent();
        // Walked in the request record's order, so the result reads as the record does; Map.copyOf would
        // forget it.
        Map<String, Location> inRecordOrder = new LinkedHashMap<>();
        for (String field : declared.keySet()) {
            Location where = placed.getOrDefault(field, remainderIsQuery ? Location.QUERY : Location.BODY);
            if (where == Location.QUERY && !placed.containsKey(field)) {
                requireScalar(name, field, declared.get(field), where, landed);
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
                              String field, Location where, Landed request) {
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
            requireScalar(entry, field, declared.get(field), where, request);
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
     * any of those has no spelling in any of the three. An array's element type is judged where the array is declared,
     * and a type named by a plain alias as what the alias reaches.
     *
     * @param request the landing of the record declaring {@code field}
     */
    private static void requireScalar(String entry, String name, RecordField field, Location where, Landed request) {
        if (field.sibling().isPresent()) {
            return;                                     // a dependent field: requireInRemainderBody refuses it
        }
        Body type = body(field.type(), request);
        if (type.definition() == null) {
            return;                                     // a built-in: text, int32, a date -- scalar by construction
        }
        if (type.definition() instanceof ArrayBody array) {
            if (where == Location.PATH) {
                refuse(entry, name, where, "an array, and a URL segment carries exactly one scalar");
            }
            if (isContainer(body(array.elementType(), type.at()).definition())) {
                refuse(entry, name, where, "an array of " + array.elementType().text()
                        + ", and a repeated " + where.name().toLowerCase(Locale.ROOT) + " value carries a scalar");
            }
            return;
        }
        if (isContainer(type.definition())) {
            refuse(entry, name, where, "a " + field.type().text() + ", which is not a scalar");
        }
    }

    /**
     * Why {@code field} is not one scalar, if it is not: what a URL segment carries, and what a response header's
     * template expands a variable from. A dependent field, an array, a record, a map and a tuple are not. The field's
     * type is judged as what it lands on, past its externs and its plain alias hops; an array is refused whatever its
     * element type, so no name the landed definition writes is read.
     *
     * @param record the landing of the record declaring {@code field}
     */
    static Optional<String> notOneScalar(RecordField field, Landed record) {
        if (field.sibling().isPresent()) {
            return Optional.of("a dependent field, whose type its sibling names per instance");
        }
        Top type = body(field.type(), record).definition();
        if (type instanceof ArrayBody) {
            return Optional.of("an array");
        }
        return isContainer(type) ? Optional.of("a " + field.type().text() + ", which is not a scalar") : Optional.empty();
    }

    /**
     * A type's definition with the landing whose namespace resolves the names that definition writes: a named type's
     * entry where it lands past its plain alias hops, a type written in place with the landing it was written in.
     */
    private record Body(Top definition, Landed at) {
    }

    /** The type {@code type}, written in {@code at}'s declaration, stands for. */
    private static Body body(TypeExpr type, Landed at) {
        return switch (type) {
            case TypeRef reference -> {
                Landed landed = at.resolve(reference.name()).pastAliases();
                yield new Body(landed.definition(), landed);
            }
            case TypeInline inline -> new Body(inline.of(), at);
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
