package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonProblemDiagnostic;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.TsonSchemaCatalog;
import io.ltr8.tson.http.TsonDeployment;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.http.api.Operation;
import io.ltr8.tson.http.api.TsonApiCoverage;
import io.ltr8.tson.http.api.TsonApiDescription;
import io.ltr8.tson.http.api.TsonApiSchema;
import io.ltr8.tson.http.jdk.TsonHandler;
import io.ltr8.tson.http.jdk.TsonSchemaHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * A validator as a service: post a schema and a document, get back every problem in one pass.
 * {@code ./gradlew :tson-http-jdk:runValidator}
 *
 * <p><b>A conformance tool wearing a demo's clothes.</b> tson.io's home page runs the same pair through the
 * TypeScript implementation in the visitor's browser; this runs it through the Java one over HTTP, so the two
 * verdicts can be put side by side. The page it serves ships the same scenarios that page does, for exactly
 * that reason — a difference in code, message or source position between the two implementations is a finding,
 * and finding it should not require writing a harness first.
 *
 * <p>It is a second demo rather than a fourth route on {@link OrderServer}, because it demonstrates the
 * opposite thing. That server is the shape a real service takes: schemas resolved at startup, shared for
 * reads, never resolved from a handler. This one takes a schema <em>from the request</em>, which that shape
 * forbids — and the whole interest is in what it costs to do safely. See {@link #verdict}.
 */
public final class ValidatorServer {

    /** The envelope this service reads and writes. */
    public static final String VALIDATE_ID = "https://schemas.example.com/2026/34/app/validate-1.tn";

    public static final String VALIDATE = schema("validate-1.tn");

    /** This service's own description, published and resolved at startup like any other schema. */
    public static final String API_ID = "https://schemas.example.com/2026/34/app/validate-api-1.tn";

    public static final String API = schema("validate-api-1.tn");

    /**
     * A cap on the request body, because this endpoint compiles what it is sent.
     *
     * <p><b>It is checked against {@code Content-Length} and that is genuinely weaker than it looks</b> — a
     * chunked request declares no length and walks straight past it. Said plainly rather than dressed up: this
     * is a demo, the cap stops an accident rather than an attacker, and a service exposed to the open internet
     * needs a bounded read on the stream itself. What the cap does buy is that the ordinary way to hurt this
     * endpoint by hand, pasting something enormous into the page, gets a clear 413 instead of a heap dump.
     */
    static final int MAX_BODY_BYTES = 256 * 1024;

    /** Which of the two phases the diagnostics came from. Both are declared by {@code validate-1.tn}. */
    public enum Phase { SCHEMA, DATA }

    /**
     * What to check. Public, and so are the records below: tson-java declares no {@code opens} and binding
     * only ever touches public constructors and methods.
     */
    @Typename(name = "validation_request")
    public record ValidationRequest(Optional<String> schema, String data) {
    }

    /** The verdict. {@code elapsed_ms} times the validation alone — see {@link #verdict}. */
    @Typename(name = "validation_result")
    public record ValidationResult(boolean conforming, Phase phase,
                                   @Field("elapsed_ms") double elapsedMs,
                                   List<TsonProblemDiagnostic> diagnostics) {
    }

    private ValidatorServer() {
    }

    /**
     * The verdict on one request, and the only part of this demo that is really about anything.
     *
     * <p><b>A fresh {@link Tson} per request, which the rest of this repo tells you not to do.</b> The rule
     * there — build one instance at startup, resolve every schema on that thread, then share it for reads — is
     * about a server whose schemas it knows. Here the schema arrives in the body, so there is no startup at
     * which to resolve it, and the two obvious ways to bend the rule are both wrong:
     *
     * <ul>
     *   <li><b>Sharing one instance and locking around it does not work</b>, and fails silently rather than
     *       loudly. {@code validateSchema} <em>registers</em> a sound schema, so the second caller to submit a
     *       different schema under the same {@code !!id} is told
     *       {@code SCHEMA_ERROR: a schema is already registered under '…'} — a complaint about their schema
     *       that is really about someone else's — and their data is then checked against the first caller's
     *       shape. A conformance tool that quietly answers about the wrong schema is worse than one that is
     *       slow.</li>
     *   <li><b>Clearing the registry between requests</b> puts mutation back on the request path, which is the
     *       thing the rule exists to keep off it.</li>
     * </ul>
     *
     * <p>A per-request instance has neither problem: it is confined to the thread that made it and is
     * discarded with the response, so no two callers can see each other's schemas at all. It costs the
     * standard-library bootstrap — meta-kernel, {@code meta.tn} and {@code core.tn}, around 18 ms — against
     * roughly 1 ms of actual validation. That ratio is why {@code elapsed_ms} times the validation and not the
     * request: reporting the bootstrap would make every implementation comparison a measurement of this
     * decision instead of the validator.
     *
     * <p><b>The schema source serves the submitted schema and nothing else.</b> A {@code !!schema} or
     * {@code !!import} naming anything the caller did not paste resolves to nothing and is reported as
     * {@code SCHEMA_UNAVAILABLE}. That is deliberate and it is the security boundary: the identity in a
     * submitted document is an untrusted URL, and an endpoint that fetched it would be a request forger for
     * anyone who could reach it.
     */
    static ValidationResult verdict(ValidationRequest request, TsonDeployment deployment) {
        String schemaText = request.schema().filter(text -> !text.isBlank()).orElse(null);

        // Serving the schema at the identity it declares, rather than at whatever the data asked for, is what
        // makes "your document names a schema you did not paste" reachable as SCHEMA_UNAVAILABLE instead of
        // arriving as a confusing identity mismatch from inside the loader.
        //
        // ofMap is what refuses everything else, and refusing is the security boundary rather than a
        // convenience: a schema identity in a submitted document is an untrusted URL, and a source that went
        // and fetched it would make this endpoint a request forger for anyone who could reach it. It also
        // canonicalises, so a caller pinning their own schema with ?sha256= still resolves to it (§2.2.1).
        String id = schemaText == null ? null : declaredId(schemaText);
        Map<String, String> library = id == null ? Map.of() : Map.of(id, schemaText);
        // The deployment's policies apply HERE and not to this service's own envelope, and the split is
        // forced by what a validator is. A descriptor states one process's policies, and applying the token
        // policy to the envelope would refuse a request whose `data` field merely CONTAINS a mixed-script
        // value -- so the one service that exists to give a verdict on such a document could never be asked
        // about one. Text a service acts on and text it is asked about are different surfaces; only the
        // second is judged.
        Tson probe = deployment.applyTo(Tson.builder().schemaSource(TsonSchemaSource.ofMap(library))).build();

        long started = System.nanoTime();
        Phase phase = Phase.DATA;
        List<Diagnostic> diagnostics = List.of();
        if (schemaText != null) {
            phase = Phase.SCHEMA;
            diagnostics = probe.validateSchema(schemaText);
        }
        // The schema phase gates the data phase: a document cannot be checked against a schema that did not
        // resolve, and reporting the document's fields as unknown on top of the real problem would bury it.
        if (diagnostics.isEmpty()) {
            phase = Phase.DATA;
            diagnostics = probe.validate(request.data());
        }
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;

        return new ValidationResult(diagnostics.isEmpty(), phase, elapsedMs,
                diagnostics.stream().map(TsonProblemDiagnostic::from).toList());
    }

    /**
     * The {@code !!id} a schema document declares, or {@code null} for one that has none or will not even
     * lex far enough to say. Both cases are ordinary here rather than exceptional — the caller is editing —
     * and both leave the library empty, so {@code validateSchema} reports what is actually wrong.
     */
    private static String declaredId(String schemaText) {
        try {
            return TsonDocumentHeader.peek(schemaText).id().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Starts the server on {@code port} — 0 for an ephemeral one — and returns it, so a test can drive the
     * real demo rather than a copy of it.
     *
     * <p><b>Two {@link Tson} instances with different jobs, and the split is the design.</b> The one built
     * here is this service's own: it governs the envelope, its schemas are known and resolved on this thread,
     * and it is shared across request threads for reads — the ordinary shape. The per-request instances
     * {@link #verdict} builds hold the caller's schema and are never shared with anything.
     */
    public static HttpServer start(int port, TsonDeployment deployment) throws IOException {
        Map<String, Class<?>> bindings = Map.of(
                "validation_request", ValidationRequest.class,
                "validation_result", ValidationResult.class,
                "diagnostic", TsonProblemDiagnostic.class,
                "acceptance_profile", TsonDeployment.AcceptanceProfile.class,
                "unicode_policy", TsonDeployment.Policy.class,
                "restriction_level", io.ltr8.tson.compiler.TsonUnicodePolicy.Level.class,
                "policy_unit", TsonDeployment.Unit.class);
        // deployment-1.tn is published; a descriptor governed by it never is. A client fetches the schema
        // to read the profile at /.well-known/tson-deployment, and there is nothing here to serve it the
        // descriptor itself with.
        Map<String, String> schemas = Map.of(VALIDATE_ID, VALIDATE, API_ID, API,
                TsonProblemSchema.ID, TsonProblemSchema.source(),
                TsonDeployment.ID, TsonDeployment.source(),
                TsonApiSchema.ID, TsonApiSchema.source());
        Tson tson = Tson.builder().schemaSource(TsonSchemaSource.ofMap(schemas)).bindings(bindings)
                .metaNameBinder(TsonApiSchema.metaNameBinder()).build();
        tson.resolve(VALIDATE);
        tson.resolve(API);
        TsonHttpCodec codec = new TsonHttpCodec(tson);

        // Forces the envelope's strict-binding check now: a field validate-1.tn declares with no component,
        // or a component no field fills, fails startup here rather than on the first request that reads one.
        codec.prepareToRead(VALIDATE_ID);

        TsonApiDescription described = TsonApiSchema.describedBy(tson, API_ID);
        codec.prepareToWrite(described.boundClasses(bindings).toArray(Class<?>[]::new));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        TsonApiCoverage coverage = TsonApiCoverage.of(described);

        Operation validate = coverage.serving("validate");
        server.createContext(validate.path(), TsonHandler.asHttpHandler(codec, exchange -> {
            exchange.requireMethod(validate.method().name());
            requireBodyWithinCap(exchange.exchange());
            ValidationRequest request = exchange.readObjectAs(VALIDATE_ID, "validation_request",
                    ValidationRequest.class);
            // Computed before the header is set, not inside the respond call: a header describes the body it
            // goes out with, and setting it first would leave a failure's `problem` body labelled as a
            // `validation_result`.
            byte[] body = exchange.codec()
                    .write(verdict(request, deployment), VALIDATE_ID, "validation_result");
            // Self-describing, like every other reply in this repo: the result names the schema governing it,
            // which this server also publishes, so a client can check what it got without being told anything.
            exchange.setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(VALIDATE_ID));
            exchange.respondBytes(200, body);
        }));

        // One context for two operations, because com.sun.net.httpserver matches a prefix: "/" catches the
        // page and every schema path alike, so the split is made here rather than by the framework. The page
        // deliberately does NOT go through TsonHandler -- that boundary checks Accept and every route behind
        // it produces TSON, so a browser asking for text/html would be answered 406 before it ever rendered.
        Operation page = coverage.serving("get_page");
        Operation wellKnown = coverage.serving("get_acceptance_profile");
        coverage.serving("get_schema");
        // Derived from the descriptor on every request rather than built once: it costs nothing, and a
        // cached projection is how a published profile starts disagreeing with what is enforced.
        byte[] profile = codec.write(deployment.profile(), TsonDeployment.ID, "acceptance_profile");
        HttpHandler schemaHandler = TsonHandler.asHttpHandler(codec,
                TsonSchemaHandler.of(catalog(described, schemas)));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (page.path().equals(path) || "/index.html".equals(path)) {
                respondWithPage(exchange);
            } else if (wellKnown.path().equals(path)) {
                exchange.getResponseHeaders().set("Content-Type", "application/tson");
                exchange.sendResponseHeaders(200, profile.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(profile);
                }
            } else {
                schemaHandler.handle(exchange);
            }
        });

        coverage.requireComplete();
        server.start();
        return server;
    }

    /**
     * Refuses an oversized body before the codec reads it. Thrown, not answered here, so it travels the same
     * boundary as every other failure and comes back as a {@code problem} like the rest.
     */
    private static void requireBodyWithinCap(HttpExchange exchange) {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared == null) {
            return;
        }
        try {
            if (Long.parseLong(declared.trim()) > MAX_BODY_BYTES) {
                throw new TsonHttpException(413, TsonHttpException.TYPES + "payload-too-large",
                        "Payload too large", "this demo validates bodies up to " + MAX_BODY_BYTES + " bytes",
                        List.of(), null);
            }
        } catch (NumberFormatException e) {
            // A malformed Content-Length is the framework's problem, not this cap's; let the read fail.
        }
    }

    /** The page, straight off the demo classpath. */
    private static void respondWithPage(HttpExchange exchange) throws IOException {
        byte[] body = resource("validator.html").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * What this server publishes, derived from its own description rather than listed — so a schema the
     * description references and this server does not serve is impossible rather than merely tested for.
     */
    private static TsonSchemaCatalog catalog(TsonApiDescription described, Map<String, String> schemas) {
        List<String> published = new ArrayList<>();
        for (String id : described.referencedSchemas()) {
            String source = schemas.get(id);
            if (source == null) {
                throw new IllegalStateException("the description references '" + id + "', which this server "
                        + "has no source for");
            }
            published.add(source);
        }
        return TsonSchemaCatalog.of(published);
    }

    /** A demo schema, as a real {@code .tn} file on the demo classpath rather than a Java text block. */
    private static String schema(String name) {
        return resource(name);
    }

    private static String resource(String name) {
        try (InputStream in = ValidatorServer.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the demo classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The descriptor this demo runs under, named here rather than found. {@link TsonDeployment} has no
     * search path on purpose: a descriptor is diffable where an environment variable is not, but a runtime
     * that loads whatever is on its path still lets a container image change a security policy with no code
     * diff. The call site says which file; the file says what is in it.
     */
    public static TsonDeployment deployment() {
        return TsonDeployment.read(resource("deployment.tn"));
    }

    public static void main(String[] args) throws IOException {
        TsonDeployment deployment = deployment();
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : deployment.listener().flatMap(TsonDeployment.Listener::port).orElse(8080);
        HttpServer server = start(port, deployment);
        int bound = server.getAddress().getPort();
        System.out.println("""

                TSON validator demo -- JDK com.sun.net.httpserver

                  Open  http://localhost:%d/

                Or drive it directly. The request is itself a TSON document, governed by a schema this
                server publishes:

                  curl -s http://localhost:%d/validate \\
                    -H 'Content-Type: application/tson' \\
                    --data-binary @request.tn

                What this deployment will accept, derived from its descriptor:

                  curl -s http://localhost:%d/.well-known/tson-deployment

                The schemas it publishes:

                  curl -s http://localhost:%d/2026/34/app/validate-1.tn
                  curl -s http://localhost:%d/2026/34/ltr8/http/deployment-1.tn
                """.formatted(bound, bound, bound, bound, bound));
    }
}
