package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * <h2>Why an operation's description is a field and not its {@code @doc}</h2>
 *
 * <p>An operation is a schema entry, so {@code @doc} is where its long-form description belongs. It does not
 * survive into resolved output ({@code UPSTREAM.md} #20) — measured, and true of an ordinary record and of
 * this project's own {@code problem-1.tn} as well — so {@link Operation#description()} carries it as an
 * ordinary field. When that gap closes, reading the annotation here is the better shape.
 *
 * <h2>The entry name is the operationId</h2>
 *
 * <p>And unlike OpenAPI's, it lives in a namespace with a collision rule — two operations cannot quietly
 * share one, and a typo is an unresolved reference rather than a second operation nobody notices.
 */
public final class TsonApiDescription {

    private final String schemaId;
    private final Map<String, Operation> operations;
    private TsonApiDescription(String schemaId, Map<String, Operation> operations) {
        this.schemaId = schemaId;
        this.operations = Map.copyOf(operations);
    }

    static TsonApiDescription of(Tson tson, String schemaId) {
        var compiled = tson.schemaRegistry().get(schemaId).orElseThrow(() -> new IllegalArgumentException(
                "'" + schemaId + "' is not resolved -- resolve the description before reading it"));
        Map<String, Operation> found = new LinkedHashMap<>();
        compiled.schema().entries().forEach((name, definition) -> {
            // An operation is found by what its body IS, not by a naming convention -- the `data` base kind
            // is what lets an entry say it is not a type, and this is the payoff.
            if (definition.body() instanceof Operation operation) {
                found.put(name, operation);
            }
        });
        return new TsonApiDescription(schemaId, found);
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

}
