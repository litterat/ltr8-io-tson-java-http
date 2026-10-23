package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One HTTP operation: a value held under its operationId in an {@code api}, the namespace type {@code meta-http.tn}
 * declares, and read back from the schema that holds it.
 *
 * <p><b>The payload types are real because {@code meta-http.tn} types their slots {@code type_kind}.</b> The read
 * records each name it produces there and the linker judges it, so naming a type nothing declares is an author
 * error at schema load -- the one thing a description written as data cannot have, since a data document can
 * name a schema but cannot hold a reference to a type. This class declares none of it.
 *
 * <p>There is no {@code description} component: an operation is held under a <em>key</em>, so {@code @doc} on the
 * key carries its long form and {@link TsonApiDescription#doc} reads it back from the key's annotations.
 */
@Typename(name = "operation")
public record Operation(HttpMethod method, String path, Optional<String> summary,
                        Optional<Boolean> deprecated, List<Parameter> parameters,
                        Optional<TypeRef> request, List<Response> responses,
                        List<Encoding> encodings) {

    /**
     * Normalises an omitted {@code parameters} to empty, and an omitted {@code encodings} to TSON alone.
     *
     * <p><b>A bound class guards its own optional lists</b> — the convention this library follows for {@code RecordBody}'s
     * {@code groups} and for {@code TypeRef}. An optional field the document omits reaches the constructor as {@code null},
     * and absent and {@code []} are the same value, so the guard belongs here rather than being a rule the binder guesses
     * at.
     *
     * <p>{@code responses} is REQUIRED and deliberately not guarded: a required field never reaches a
     * constructor, because the reader reports {@code FIELD_REQUIRED} and abandons the construction first.
     * Guarding it would mask a real violation rather than absorb a legitimate absence.
     */
    public Operation {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        // Every operation speaks TSON; the list says what it speaks beside it.
        Set<Encoding> spoken = EnumSet.of(Encoding.TSON);
        if (encodings != null) {
            spoken.addAll(encodings);
        }
        encodings = List.copyOf(spoken);
    }

    /**
     * The variables {@code path}'s URI template names, in order -- {@code schemaPath} for both {@code
     * /{schemaPath}} and {@code /{+schemaPath}}, the operator being how a value expands rather than which
     * value. {@code meta-http.tn}'s {@code uri_template} admits one variable to an expression, so each is one name.
     */
    public List<String> pathVariables() {
        return PATH_VARIABLE.matcher(path).results().map(match -> match.group(1)).toList();
    }

    /** The parameters carried in the path -- the ones its template must name. */
    public List<String> pathParameters() {
        return parameters.stream().filter(parameter -> parameter.in() == ParameterLocation.PATH)
                .map(Parameter::name).toList();
    }

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[+#]?([^}]+)}");

    /** Every payload type this operation names: its request body, its parameters' types, then its response bodies. */
    public List<TypeRef> payloadTypes() {
        List<TypeRef> all = new ArrayList<>();
        request.ifPresent(all::add);
        parameters.forEach(parameter -> all.add(parameter.type()));
        responses.forEach(response -> response.body().ifPresent(all::add));
        return List.copyOf(all);
    }

    /** This operation's declared response for {@code status}, or empty if it declares none. */
    public Optional<Response> responseFor(int status) {
        return responses.stream().filter(response -> response.status() == status).findFirst();
    }

    /**
     * Whether this operation also speaks TSON's JSON encoding -- admits a JSON body and answers in JSON when asked.
     * A server serving it builds the route's codec {@code acceptingJson}; that it did is for a test against the
     * running server to check, since nothing in the description can see the codec.
     */
    public boolean speaksJson() {
        return encodings.contains(Encoding.JSON);
    }

    /** Whether this operation is marked deprecated; absent means no. */
    public boolean isDeprecated() {
        return deprecated.orElse(false);
    }
}
