package io.ltr8.tson.http;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A description of an HTTP API whose payloads are TSON documents, read from a document governed by
 * {@code api-1.tn}.
 *
 * <p><b>What OpenAPI is for JSON, minus the part OpenAPI mostly is.</b> OpenAPI embeds a schema language
 * because JSON has none; TSON already has published, identity-addressed schemas, so an operation references
 * one by {@code !!id} and names a root type within it. The whole of a contract is that pair, which is the same
 * pair everything else handling a TSON payload turns out to need.
 *
 * <p><b>A description is worth having only if something checks it.</b> This exists so a test can drive a real
 * server against its own description and fail when the two disagree — see {@code TsonApiConformanceTest} in the
 * JDK adapter. A description nothing executes is documentation that quietly stops being true, which is the
 * lesson the demo servers here already taught.
 *
 * <p>The description is itself a TSON document governed by a TSON schema, so it is served, validated and
 * versioned by exactly the machinery it describes.
 */
public final class TsonApi {

    /** The current API-description schema's canonical identity. */
    public static final String SCHEMA_ID = "https://tson.io/2026/32/ltr8/http/api-2.tn";

    private static final String SOURCE = readResource("/api-2.tn");

    /** Superseded, still published. {@code api-1.tn} spelled every reference as a (schema, type) pair. */
    private static final String SUPERSEDED_1 = readResource("/api-1.tn");

    private final Api api;

    private TsonApi(Api api) {
        this.api = api;
    }

    /** Where a parameter is carried. Not the body, which is a document and has its own reference. */
    @Typename(name = "parameter_location")
    public enum ParameterLocation {
        PATH, QUERY, HEADER
    }

    @Typename(name = "http_method")
    public enum HttpMethod {
        GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
    }

    /** A parameter carried outside the body. {@code type} names a scalar type from the standard library. */
    @Typename(name = "parameter")
    public record Parameter(String name, ParameterLocation in, String type, boolean required,
                            Optional<String> description) {
    }

    @Typename(name = "response")
    public record Response(int status, Optional<String> body, Optional<String> description) {
    }

    @Typename(name = "operation")
    public record Operation(HttpMethod method, String path, Optional<String> summary, List<Parameter> parameters,
                            Optional<String> request, List<Response> responses) {

        /** This operation's declared response for {@code status}, or empty if it declares none. */
        public Optional<Response> responseFor(int status) {
            return responses.stream().filter(response -> response.status() == status).findFirst();
        }
    }

    @Typename(name = "api")
    public record Api(String title, String version, Optional<URI> base, List<URI> imports,
                      List<Operation> operations) {
    }

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "api", Api.class,
            "operation", Operation.class,
            "response", Response.class,
            "parameter", Parameter.class,
            "parameter_location", ParameterLocation.class,
            "http_method", HttpMethod.class);

    /** The current schema's own source text, for a server that publishes the schemas it uses. */
    public static String schemaSource() {
        return SOURCE;
    }

    /**
     * Every version of the description schema still published, current first. §10 makes a published schema
     * immutable, so {@code api-1.tn} stays served — a document that named it must go on resolving.
     */
    public static List<String> publishedSources() {
        return List.of(SOURCE, SUPERSEDED_1);
    }

    /**
     * Reads an API description.
     *
     * @param description the description document, which must name {@code api-1.tn} as its {@code !!schema}
     * @throws TsonHttpException 400 if it is not a valid description
     */
    public static TsonApi read(String description) {
        // Serves both published versions, so a description naming the superseded one still reads (§10).
        TsonSchemaSource source = uri -> uri.contains("api-2.tn") ? SOURCE
                : uri.contains("api-1.tn") ? SUPERSEDED_1
                : TsonSchemaSource.registeredOnly().fetch(uri);
        Tson tson = Tson.builder().schemaSource(source).bindings(BINDINGS).build();
        TsonHttpCodec codec = new TsonHttpCodec(tson);
        return new TsonApi(codec.readObject(
                new java.io.ByteArrayInputStream(description.getBytes(StandardCharsets.UTF_8)),
                TsonMediaType.APPLICATION_TSON.toString(), Api.class));
    }

    /** The description itself. */
    public Api api() {
        return api;
    }

    /** Every operation this API declares. */
    public List<Operation> operations() {
        return api.operations();
    }

    /** The operation for {@code method} and {@code path}, or empty if none is declared. */
    public Optional<Operation> operation(HttpMethod method, String path) {
        return api.operations().stream()
                .filter(operation -> operation.method() == method && operation.path().equals(path))
                .findFirst();
    }

    /** The schemas this description imports — its whole namespace, and what a server must publish. */
    public Set<String> referencedSchemas() {
        return api.imports().stream().map(URI::toString)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Every type name this description uses, in declaration order, with where it was written. */
    private Map<String, String> referencedTypes() {
        Map<String, String> where = new LinkedHashMap<>();
        for (Operation operation : api.operations()) {
            String at = operation.method() + " " + operation.path();
            operation.request().ifPresent(type -> where.putIfAbsent(type, at + " request"));
            operation.parameters().forEach(p -> where.putIfAbsent(p.type(), at + " parameter '" + p.name() + "'"));
            for (Response response : operation.responses()) {
                response.body().ifPresent(type -> where.putIfAbsent(type, at + " response " + response.status()));
            }
        }
        return where;
    }

    /**
     * Resolves every type name this description uses against its own {@code imports}, and reports what does not
     * resolve.
     *
     * <p><b>This is the work the resolver will not do, done here instead.</b> A data document cannot hold a
     * reference to a type — type names resolve at type-ref positions in a schema and nowhere else — so a
     * description written as data carries its own import list and its own namespace rule, and a consumer
     * enforces it. That is the trade: the description is an ordinary document needing nothing new from the type
     * system, and in exchange the checking is application code.
     *
     * <p>The rule mirrors a schema's own, <b>including the part that is easy to get wrong</b>: ambiguity is
     * judged by the <em>declaration</em>, not by how many imports surface the name. Imports are transitive, so
     * a schema's entries are its whole merged namespace — importing both {@code problem-1.tn} and something
     * that imports it surfaces {@code problem} twice from one declaration, which is not a conflict. Counting
     * occurrences instead is precisely the bug {@code UPSTREAM.md} #11 fixed upstream, and it is just as wrong
     * here. Two imports declaring genuinely different types under one name is the real conflict.
     *
     * @param tson resolves the imported schemas; each is fetched through its own schema source
     * @return every problem found, empty meaning the description is sound
     */
    public List<Diagnostic> validate(Tson tson) {
        List<Diagnostic> problems = new ArrayList<>();
        // name -> the distinct declarations found for it, each with the import that surfaced it.
        Map<String, Map<Declaration, String>> declaredBy = new LinkedHashMap<>();

        for (URI importedUri : api.imports()) {
            String imported = importedUri.toString();
            try {
                // resolveLinked fetches, resolves, links and registers if it is not already -- so an
                // import is loaded through whatever schema source this Tson was built with, the same way a
                // schema's own !!import would be.
                tson.loader().resolveLinked(imported).schema().entries().forEach((name, definition) ->
                        declaredBy.computeIfAbsent(name, k -> new LinkedHashMap<>())
                                .putIfAbsent(Declaration.of(definition), imported));
            } catch (RuntimeException unloadable) {
                problems.add(Diagnostic.ofSchemaError(imported, "",
                        "this description imports '" + imported + "', which cannot be loaded: "
                                + unloadable.getMessage(), Optional.empty()));
            }
        }

        referencedTypes().forEach((type, at) -> {
            Map<Declaration, String> declarations = declaredBy.getOrDefault(type, Map.of());
            if (declarations.isEmpty()) {
                problems.add(Diagnostic.ofSchemaError(SCHEMA_ID, "", at + " names type '" + type
                        + "', which none of this description's imports declares", Optional.empty()));
            } else if (declarations.size() > 1) {
                problems.add(Diagnostic.ofSchemaError(SCHEMA_ID, "", at + " names type '" + type
                        + "', which more than one import declares differently: "
                        + List.copyOf(declarations.values()), Optional.empty()));
            }
        });
        return List.copyOf(problems);
    }

    /**
     * What a name was <em>declared</em> as, for deciding whether two imports mean the same type.
     *
     * <p><b>The whole {@code TypeDefinition} will not do</b>, and finding out cost a debugging cycle. Imports
     * are transitive, so one declaration is reached by several routes — and the copies are not equal, because
     * linking credits each route's own {@code subtypes}. {@code problem} seen through {@code orders-errors-1.tn}
     * has {@code sku_not_found} among its subtypes; seen directly through {@code problem-1.tn} it has none.
     * Comparing the definitions would call that a conflict, which is the occurrence-counting mistake in a new
     * costume.
     *
     * <p>So this compares the authored declaration and leaves out what linking derived.
     */
    private record Declaration(io.ltr8.tson.schema.meta.TypeKind kind, Object body) {

        static Declaration of(TypeDefinition definition) {
            return new Declaration(definition.kind(), definition.body());
        }
    }

    private static String readResource(String path) {
        try (InputStream in = TsonApi.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
