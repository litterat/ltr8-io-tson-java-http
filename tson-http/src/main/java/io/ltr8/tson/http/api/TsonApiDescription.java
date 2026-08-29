package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;

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
 * once by comparing whole {@code TypeDefinition}s, which differ per route because linking credits each
 * route's own subtypes.
 *
 * <p>Here the compiler has already done it. A description whose {@code body} names a type nothing declares
 * does not resolve, so an instance of this class cannot exist for an unsound description. What is left is a
 * read model.
 *
 * <h2>An entry has two annotation positions, and they land in different places</h2>
 *
 * <p>{@code @doc:"…" create_order => !operation { … }} annotates the <b>entry</b> and is read from the
 * entries map's own annotations; {@code create_order => @doc:"…" !operation { … }} annotates the
 * <b>definition</b> and is read from the {@code TypeDefinition}. Both are retained. Reading only the second
 * and concluding {@code @doc} was dropped is a mistake that cost a wrongly-filed upstream item and a
 * redundant {@code description} field on {@code operation}, since removed.
 *
 * <h2>The entry name is the operationId</h2>
 *
 * <p>And unlike OpenAPI's, it lives in a namespace with a collision rule — two operations cannot quietly
 * share one, and a typo is an unresolved reference rather than a second operation nobody notices.
 */
public final class TsonApiDescription {

    /**
     * The spec's own bundled meta directory. Every implementation has these; a service does not publish them,
     * and a client resolving this description already has them.
     */
    private static final String BUNDLED = "https://tson.io/2026/34/m/";

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
        var entries = compiled.schema().entries();
        entries.forEach((name, definition) -> {
            // An operation is found by what its body IS, not by a naming convention -- the `data` base kind
            // is what lets an entry say it is not a type, and this is the payoff.
            if (definition.body() instanceof Operation operation) {
                found.put(name, operation);
                entries.getAnnotations(name).value("doc", String.class)
                        .ifPresent(text -> docs.put(name, text));
            }
        });
        return new TsonApiDescription(tson, schemaId, found, docs);
    }

    /** The description schema's identity. */
    public String schemaId() {
        return schemaId;
    }

    /**
     * Every operation, keyed by its entry name -- which is its operationId.
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
                operation.references().forEach(reference -> types.add(reference.name())));
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
