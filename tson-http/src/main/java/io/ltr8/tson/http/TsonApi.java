package io.ltr8.tson.http;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    /** {@code api-1.tn}'s canonical identity. */
    public static final String SCHEMA_ID = "https://tson.io/2026/32/ltr8/http/api-1.tn";

    private static final String SOURCE = readResource("/api-1.tn");

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

    /**
     * One TSON payload: the schema that governs it and the root type within that schema. Both are required for
     * the reason they are required everywhere else — a schema alone does not say which of its types a document
     * is, and a bound record writes no type-ref of its own.
     */
    @Typename(name = "body")
    public record Body(String schema, String type, @io.ltr8.annotation.Field("media_type")
                       Optional<String> mediaType) {

        /** The media type this body is carried as; {@code application/tson} unless stated otherwise. */
        public String effectiveMediaType() {
            return mediaType.orElse(TsonMediaType.APPLICATION_TSON.toString());
        }
    }

    @Typename(name = "response")
    public record Response(int status, Optional<Body> body, Optional<String> description) {
    }

    @Typename(name = "operation")
    public record Operation(HttpMethod method, String path, Optional<String> summary, List<Parameter> parameters,
                            Optional<Body> request, List<Response> responses) {

        /** This operation's declared response for {@code status}, or empty if it declares none. */
        public Optional<Response> responseFor(int status) {
            return responses.stream().filter(response -> response.status() == status).findFirst();
        }
    }

    @Typename(name = "api")
    public record Api(String title, String version, Optional<String> base, List<Operation> operations) {
    }

    private static final DataNameBinder BINDER = name -> switch (name) {
        case "api" -> Api.class;
        case "operation" -> Operation.class;
        case "response" -> Response.class;
        case "body" -> Body.class;
        case "parameter" -> Parameter.class;
        case "parameter_location" -> ParameterLocation.class;
        case "http_method" -> HttpMethod.class;
        default -> SchemaMetaNameBinder.INSTANCE.resolve(name);
    };

    /** {@code api-1.tn}'s own source text, for a server that publishes the schemas it uses. */
    public static String schemaSource() {
        return SOURCE;
    }

    /**
     * Reads an API description.
     *
     * @param description the description document, which must name {@code api-1.tn} as its {@code !!schema}
     * @throws TsonHttpException 400 if it is not a valid description
     */
    public static TsonApi read(String description) {
        DataBindContext bind =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(BINDER).build());
        TsonSchemaSource source = uri -> uri.contains("api-1.tn") ? SOURCE
                : TsonSchemaSource.registeredOnly().fetch(uri);
        Tson tson = Tson.builder().schemaSource(source).dataBindContext(bind).build();
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

    /**
     * Every schema identity this description references, from every request and response body, in declaration
     * order.
     *
     * <p>The check worth running against a server: it must publish all of them. A description referencing a
     * schema its own server does not serve is a contract a client cannot obtain.
     */
    public Set<String> referencedSchemas() {
        Set<String> schemas = new LinkedHashSet<>();
        for (Operation operation : api.operations()) {
            operation.request().ifPresent(body -> schemas.add(body.schema()));
            for (Response response : operation.responses()) {
                response.body().ifPresent(body -> schemas.add(body.schema()));
            }
        }
        return schemas;
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
