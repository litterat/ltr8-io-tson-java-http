package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.schema.meta.Namespace;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The API a schema describes, read back from that schema's resolved entries.
 *
 * <h2>There is no {@code validate}, and that is the point</h2>
 *
 * <p>The same model over a description written as <em>data</em> needs about forty lines resolving each
 * payload's type name against the description's own import list, because a data document holds bare names
 * that nothing resolves. This project had those lines and deleted them with the design; they reimplemented an
 * upstream namespace bug twice before they were right — once by counting how many imports surface a name, and
 * once by comparing whole resolved entries, which differ per route because linking credits each route's own
 * subtypes.
 *
 * <p>Here the compiler has already done it. A description whose {@code body} names a type nothing declares
 * does not resolve, so an instance of this class cannot exist for an unsound description. What is left is a
 * read model.
 *
 * <h2>A key has two annotation positions, and they land in different places</h2>
 *
 * <p>{@code @doc:"…" create_order => { … }} annotates the <b>key</b> and is read from the {@code api}'s own
 * annotations; {@code create_order => @doc:"…" { … }} annotates the <b>value</b>. Reading only the second and
 * concluding {@code @doc} was dropped is a mistake that cost a wrongly-filed upstream item and a redundant
 * {@code description} field on {@code operation}, since removed.
 *
 * <h2>The key is the operationId</h2>
 *
 * <p>And unlike OpenAPI's, it lives in a namespace: an {@code api} entry is a value of a namespace type, so a key
 * written twice is a duplicate key, and a typo is an unresolved reference rather than a second operation nobody
 * notices. A description may hold several {@code api} entries, and an operationId two of them share refuses the
 * model, since a server claims an operation by its operationId alone.
  */
public final class TsonApiDescription {

    /**
     * The spec's own bundled meta directory. Every implementation has these; a service does not publish them,
     * and a client resolving this description already has them.
     */
    private static final String BUNDLED = "https://tson.io/2026/38/m/";

    /** The namespace type {@code meta-http.tn} declares, whose values hold operations. */
    private static final String API = "api";

    private final Tson tson;
    private final String schemaId;
    private final Map<String, Operation> operations;

    private final Map<String, String> docs;

    private TsonApiDescription(Tson tson, String schemaId, Map<String, Operation> operations,
                               Map<String, String> docs) {
        this.tson = tson;
        this.schemaId = schemaId;
        this.operations = Map.copyOf(operations);
        this.docs = Map.copyOf(docs);
    }

    /** An operation's {@code @doc} -- its long-form description. {@code summary} is the short one. */
    public Optional<String> doc(String operationName) {
        return Optional.ofNullable(docs.get(operationName));
    }

    static TsonApiDescription of(Tson tson, String schemaId) {
        var compiled = tson.schemaRegistry().get(schemaId).orElseThrow(() -> new IllegalArgumentException(
                "'" + schemaId + "' is not resolved -- resolve the description before reading it"));
        Map<String, Operation> found = new LinkedHashMap<>();
        Map<String, String> docs = new LinkedHashMap<>();
        compiled.schema().entries().forEach((entry, value) -> {
            // An API is found by what the entry IS -- a value of meta-http.tn's namespace type -- not by a naming
            // convention, and its operations are the values it holds.
            if (value instanceof Namespace<?> api && api.type().equals(API)) {
                api.forEach((name, held) -> {
                    Operation operation = (Operation) held;
                    requirePathMatchesItsParameters(name, operation);
                    Operation earlier = found.putIfAbsent(name, operation);
                    if (earlier != null) {
                        throw new IllegalArgumentException("operation '" + name + "' is declared by two api entries,"
                                + " and a server claims an operation by its operationId alone");
                    }
                    api.getAnnotations(name).value("doc", String.class).ifPresent(text -> docs.put(name, text));
                });
            }
        });
        return new TsonApiDescription(tson, schemaId, found, docs);
    }

    /**
     * <b>An operation's path template names exactly its PATH parameters</b> -- the one check a description
     * needs that neither the resolver nor {@code meta-http.tn}'s {@code uri_template} pattern can make, since it
     * relates two fields. A variable no parameter declares is a segment a server has no type for, and a PATH
     * parameter the template never names is one no request can carry. Either is the description's author's
     * mistake, so the model is refused rather than built: as with a payload type nothing declares, a server
     * fails at startup rather than publishing a contract nobody can act on.
     *
     * <p>A path is a path: it starts with {@code /}. A full URI template belongs to a server list, which this
     * meta layer deliberately does not have.
     *
     * @throws IllegalArgumentException naming the operation and what disagrees
     */
    private static void requirePathMatchesItsParameters(String name, Operation operation) {
        if (!operation.path().startsWith("/")) {
            throw new IllegalArgumentException("operation '" + name + "': path '" + operation.path()
                    + "' is not a path -- it must start with '/'");
        }
        List<String> variables = operation.pathVariables();
        List<String> declared = operation.pathParameters();
        List<String> undeclared = variables.stream().filter(variable -> !declared.contains(variable)).toList();
        List<String> unused = declared.stream().filter(parameter -> !variables.contains(parameter)).toList();
        if (!undeclared.isEmpty() || !unused.isEmpty()) {
            throw new IllegalArgumentException("operation '" + name + "': path '" + operation.path()
                    + "' and its PATH parameters disagree"
                    + (undeclared.isEmpty() ? "" : " -- the template names " + undeclared
                            + " and no PATH parameter declares " + (undeclared.size() == 1 ? "it" : "them"))
                    + (unused.isEmpty() ? "" : " -- PATH parameter" + (unused.size() == 1 ? " " : "s ") + unused
                            + " never appear" + (unused.size() == 1 ? "s" : "") + " in the template"));
        }
    }

    /** The description schema's identity. */
    public String schemaId() {
        return schemaId;
    }

    /**
     * Every operation, keyed by its operationId.
     *
     * <p><b>Not in declaration order.</b> A schema's {@code entries()} is ordered by resolution, not by how
     * the author wrote it, so a renderer that wants a stable presentation order sorts by something it
     * chooses -- the name, or the path -- rather than relying on this.
     */
    public Map<String, Operation> operations() {
        return operations;
    }

    /** The operation serving {@code method} and {@code path}, if this description declares one. */
    public Optional<Operation> operation(HttpMethod method, String path) {
        return operations.values().stream()
                .filter(operation -> operation.method() == method && operation.path().equals(path))
                .findFirst();
    }

    /**
     * Every schema identity a client needs in order to resolve this description — itself, the meta layer that
     * governs it, and its imports transitively, minus the bundled standard library.
     *
     * <p><b>This is the set a server must publish</b>, and deriving it is the point: a description that
     * references a schema its own server does not serve is a contract nobody can act on, and hand-listing
     * what to publish is how that happens. The conformance test asserted the two agreed; this makes them the
     * same thing.
     *
     * <p>Ordered with this description first, then in resolution order.
     */
    public Set<String> referencedSchemas() {
        Set<String> found = new LinkedHashSet<>();
        collect(schemaId, found);
        return found;
    }

    private void collect(String id, Set<String> found) {
        if (id == null || id.startsWith(BUNDLED) || !found.add(id)) {
            return;
        }
        var schema = tson.loader().resolveLinked(id).schema();
        collect(schema.meta(), found);
        schema.imports().forEach(imported -> collect(imported, found));
    }

    /**
     * Every payload type this description's operations name — request bodies, response bodies and parameter
     * types, in the order the operations declare them.
     *
     * <p>Not every one is a type the application binds: a parameter's {@code text}, or the {@code problem}
     * this library writes itself, are named here and mapped by nobody. {@link #boundClasses} is the filtered
     * form, which is what a warm-up wants.
     */
    public Set<String> payloadTypes() {
        Set<String> types = new LinkedHashSet<>();
        operations.values().forEach(operation ->
                operation.payloadTypes().forEach(reference -> types.add(reference.name())));
        return types;
    }

    /**
     * The Java classes {@code bindings} maps this description's payload types to — what to warm at startup,
     * derived rather than listed.
     *
     * <p>Hand-listing them is how a response type added to a description never gets warmed: nothing connects
     * the two lists, and the omission costs only latency, so nothing reports it. A type {@code bindings} does
     * not map is skipped rather than refused — {@code text} and {@code problem} are named by descriptions and
     * bound by nobody.
     */
    public List<Class<?>> boundClasses(Map<String, Class<?>> bindings) {
        return payloadTypes().stream().map(bindings::get).filter(java.util.Objects::nonNull)
                .distinct().toList();
    }

}
