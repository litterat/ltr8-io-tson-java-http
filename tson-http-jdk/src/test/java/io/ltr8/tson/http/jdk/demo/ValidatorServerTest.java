package io.ltr8.tson.http.jdk.demo;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.http.TsonProblemDiagnostic;
import io.ltr8.tson.http.TsonDeployment;
import io.ltr8.tson.http.TsonProblemSchema;
import io.ltr8.tson.http.jdk.demo.ValidatorServer.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real validator demo over real HTTP.
 *
 * <p>The reply is read back <b>through the codec, against the published envelope</b> rather than by matching
 * strings: this service exists to be believed about diagnostics, so a test that asserted on substrings would
 * pass just as happily on a body no client could parse.
 */
class ValidatorServerTest {

    /** The schema a caller submits. Deliberately not this server's own — it arrives in the body like any. */
    private static final String PEOPLE = """
            !!id:"https://example.com/people.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              employee => { id: uuid  name: non_empty_text  age: uint8 }
            }""";

    private static final String CONFORMING = """
            !!schema:"https://example.com/people.tn"
            !employee { id: "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"  name: "Ada"  age: 36 }""";

    private com.sun.net.httpserver.HttpServer server;
    private HttpClient client;
    private String base;
    private Tson tson;

    @BeforeEach
    void startServer() throws Exception {
        server = ValidatorServer.start(0, ValidatorServer.deployment());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
        // A reader for the reply, wired the way a real client would wire one: the envelope and the problem
        // schema it imports, both fetched from the server under test rather than held as local copies.
        Map<String, String> schemas = Map.of(
                ValidatorServer.VALIDATE_ID, ValidatorServer.VALIDATE,
                TsonProblemSchema.ID, TsonProblemSchema.source());
        tson = Tson.builder().schemaSource(schemas::get)
                .bindings(Map.of("validation_result", ValidationResult.class,
                        "diagnostic", TsonProblemDiagnostic.class))
                .build();
        tson.resolve(ValidatorServer.VALIDATE);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    /** A request as the page builds one: both payloads as single-line quoted tokens. */
    private static String request(String schema, String data) {
        String fields = (schema == null ? "" : "  schema: " + quoted(schema) + "\n")
                + "  data:   " + quoted(data) + "\n";
        return "!!schema:\"" + ValidatorServer.VALIDATE_ID + "\"\n!validation_request {\n" + fields + "}\n";
    }

    /** [TSON-DATA] §7.2.2's single-line escapes, which is all these payloads need. */
    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private HttpResponse<String> validate(String schema, String data) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/validate"))
                        .header("Content-Type", "application/tson")
                        .POST(HttpRequest.BodyPublishers.ofString(request(schema, data), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ValidationResult resultOf(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return tson.objectReader().read(response.body(), ValidationResult.class);
    }

    @Test
    void aConformingDocumentIsReportedAsConforming() throws Exception {
        ValidationResult result = resultOf(validate(PEOPLE, CONFORMING));

        assertTrue(result.conforming(), () -> "expected no diagnostics, got " + result.diagnostics());
        assertEquals(ValidatorServer.Phase.DATA, result.phase());
        assertEquals(List.of(), result.diagnostics());
    }

    /**
     * <b>A document that does not conform is a 200, and that is the endpoint's central decision.</b> The
     * request was well-formed and the service answered it; the diagnostics <em>are</em> the answer. Answering
     * 4xx would leave a client unable to tell "you asked badly" from "the thing you asked about is bad" —
     * which is exactly the distinction this service exists to report.
     */
    @Test
    void aFaultingDocumentIsAnAnswerRatherThanAFailure() throws Exception {
        ValidationResult result = resultOf(validate(PEOPLE, """
                !!schema:"https://example.com/people.tn"
                !employee { id: "not-a-uuid"  name: ""  age: 300  nickname: "Countess" }"""));

        assertFalse(result.conforming());
        assertEquals(ValidatorServer.Phase.DATA, result.phase());
        // Every fault in one pass, which is the claim the page makes: three bad values and one field nobody
        // declared, not the first of them.
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                        Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, Diagnostic.Code.UNRECOGNIZED_FIELD),
                result.diagnostics().stream().map(TsonProblemDiagnostic::code).toList());
        // Both ends located: the value in the data, and the rule in the schema.
        TsonProblemDiagnostic first = result.diagnostics().getFirst();
        assertTrue(first.dataPosition().isPresent(), "the data end should be located");
        assertTrue(first.schemaPosition().isPresent(), "the schema end should be located");
    }

    /**
     * A broken schema stops the run, and {@code phase} is what says so. Without it a client cannot tell a
     * document that passed from one that was never checked.
     */
    @Test
    void aBrokenSchemaIsReportedAndTheDataIsNotChecked() throws Exception {
        ValidationResult result = resultOf(validate("""
                !!id:"https://example.com/people.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { employee => { name: no_such_type } }""", CONFORMING));

        assertEquals(ValidatorServer.Phase.SCHEMA, result.phase());
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR),
                result.diagnostics().stream().map(TsonProblemDiagnostic::code).toList());
    }

    /** No schema submitted is a class 1 read: base syntax plus whatever the document's own type-refs name. */
    @Test
    void anAbsentSchemaAsksForAClassOneRead() throws Exception {
        ValidationResult result = resultOf(validate(null, "{ when: !date 2026-13-45  free: bare }"));

        assertEquals(ValidatorServer.Phase.DATA, result.phase());
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                result.diagnostics().stream().map(TsonProblemDiagnostic::code).toList());
    }

    /**
     * <b>The isolation the whole design rests on.</b> Two callers submit different schemas under one
     * {@code !!id}, and each is answered about their own.
     *
     * <p>A shared {@link Tson} behind a lock fails this, and fails it quietly: {@code validateSchema}
     * registers a sound schema, so the second caller is told {@code SCHEMA_ERROR: a schema is already
     * registered under '…'} — a complaint about their schema that is really about someone else's — and their
     * document is then checked against the first caller's shape. The per-request instance is what makes that
     * unreachable, and this is the test that would notice it being optimised away.
     */
    @Test
    void twoCallersSharingASchemaIdDoNotSeeEachOther() throws Exception {
        String mine = """
                !!id:"https://example.com/people.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { employee => { handle: text } }""";
        String theirs = """
                !!id:"https://example.com/people.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { employee => { nickname: text } }""";
        String document = """
                !!schema:"https://example.com/people.tn"
                !employee { handle: "ada" }""";

        assertTrue(resultOf(validate(mine, document)).conforming());
        // The same document against the other caller's shape: `handle` is unknown there, `nickname` missing.
        assertFalse(resultOf(validate(theirs, document)).conforming());
        // And back again -- neither call left anything behind for the other to trip over.
        assertTrue(resultOf(validate(mine, document)).conforming(),
                "a previous caller's schema is still registered somewhere");
    }

    /**
     * <b>The service fetches nothing.</b> A schema identity in a submitted document is an untrusted URL, so a
     * document naming a schema the caller did not paste is reported as unavailable rather than resolved off
     * the network. An endpoint that fetched it would be a request forger for anyone who could reach it.
     */
    @Test
    void aSchemaThatWasNotSubmittedIsNeverFetched() throws Exception {
        ValidationResult result = resultOf(validate(PEOPLE, """
                !!schema:"https://example.com/somewhere-else.tn"
                !employee { id: "f81d4fae-7dec-11d0-a765-00a0c91e6bf6" }"""));

        assertEquals(List.of(Diagnostic.Code.SCHEMA_UNAVAILABLE),
                result.diagnostics().stream().map(TsonProblemDiagnostic::code).toList());
    }

    /**
     * <b>A 400 here is always about the envelope, never about the document under test.</b> That is what keeps
     * the two answerable apart: this body is not a {@code validation_request} at all, so there was no question
     * to answer.
     */
    @Test
    void aMalformedEnvelopeIsTheOneThingAnsweredFourHundred() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/validate"))
                        .header("Content-Type", "application/tson")
                        .POST(HttpRequest.BodyPublishers.ofString("!!schema:\"" + ValidatorServer.VALIDATE_ID
                                + "\"\n!validation_request { data: 1  unexpected: 2 }", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("problem-1.tn"), response.body());
    }

    /** The page is served at the path its own description declares, and is not answered as TSON. */
    @Test
    void thePageIsServedAtTheRoot() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/")).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertEquals("text/html; charset=utf-8",
                response.headers().firstValue("Content-Type").orElse(""));
        assertTrue(response.body().contains("<title>"), "expected the validator page");
    }

    /**
     * The demo's own schemas resolve from the running server, so the identity in a reply's {@code !!schema}
     * is something a client can actually dereference -- the same loop every other demo here proves.
     */
    @Test
    void theEnvelopeIsPublishedAtItsOwnIdentityPath() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/2026/34/app/validate-1.tn")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("!!id:\"" + ValidatorServer.VALIDATE_ID + "\""), response.body());
    }

    /**
     * <b>The deployment descriptor is a decision, not a description, and this is where that shows.</b> The
     * demo's descriptor sets {@code tokens: { level: SINGLE_SCRIPT }}, so a value mixing scripts is refused
     * -- by this deployment, under a policy another one need not apply. Remove the setting and the same
     * document conforms.
     */
    @Test
    void theDeploymentsTokenPolicyDecidesTheVerdict() throws Exception {
        // Latin "dmin" behind a Cyrillic first letter: the homograph the rule exists for.
        ValidationResult mixed = resultOf(validate(null, "{ display: \"\u0430dmin\" }"));

        assertFalse(mixed.conforming());
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                mixed.diagnostics().stream().map(TsonProblemDiagnostic::code).toList());
        // And it names the data it was judged against, which is the half of §8.2 that used to reach a client
        // nowhere: §8.3 marks the rule unstable across Unicode releases, so a refusal without it cannot be
        // told from another processor's disagreement.
        assertTrue(mixed.diagnostics().getFirst().unicodeDataVersion().isPresent(),
                () -> "a refusal states its data version: " + mixed.diagnostics());

        // Wholly Cyrillic is one script, so it is admitted -- the rule is about mixing, not about Cyrillic.
        assertTrue(resultOf(validate(null, "{ display: \"\u0430\u0434\u043c\u0438\u043d\" }")).conforming());
    }

    /**
     * <b>The policy judges the document under test and not the envelope carrying it</b>, which a validator
     * forces: the request above puts that same mixed-script text in its own {@code data} field, so a token
     * policy applied to the envelope would refuse the one service that exists to give a verdict on such a
     * document. Text a service acts on and text it is asked about are different surfaces.
     *
     * <p>Asserted by the test above passing at all -- this one states the reason, and fails the day someone
     * applies the deployment to the service's own {@link io.ltr8.tson.Tson} as well as to the probe.
     */
    @Test
    void theEnvelopeIsNotJudgedByThePolicyItCarries() throws Exception {
        HttpResponse<String> response = validate(null, "{ display: \"\u0430dmin\" }");

        assertEquals(200, response.statusCode(),
                "the envelope carries the refused text and must still be read: " + response.body());
    }

    /**
     * <b>What a counterparty may see is a projection, never the descriptor.</b> The policies go out; the fetch
     * allow-list and the listener do not -- which origins a deployment trusts is nobody else's business.
     */
    @Test
    void theAcceptanceProfileIsPublishedAndDropsTheTrustConfiguration() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/.well-known/tson-deployment")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertEquals("application/tson", response.headers().firstValue("Content-Type").orElse(""));
        assertTrue(response.body().contains("SINGLE_SCRIPT"), response.body());
        // Self-describing, and the schema it names is published, so a client can check what it got.
        assertTrue(response.body().contains(TsonDeployment.ID), response.body());
        assertFalse(response.body().contains("schema_hosts"), response.body());
        assertFalse(response.body().contains("listener"), response.body());
    }

    /** The descriptor governs the demo but is not itself served -- deployment-1.tn's rule 2. */
    @Test
    void theDescriptorIsNeverServed() throws Exception {
        HttpResponse<String> schema = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/2026/34/ltr8/http/deployment-1.tn")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, schema.statusCode(), "the schema is published, so a profile can be validated");

        // And nothing anywhere serves an instance of it: the descriptor is not in the catalog under any path.
        assertFalse(schema.body().contains("validator-demo"),
                "the schema path served the descriptor rather than the schema");
    }

    /** The identities the demo publishes are the ones its Java constants name. */
    @Test
    void identitiesMatchTheConstants() {
        assertTrue(ValidatorServer.VALIDATE.startsWith("!!id:\"" + ValidatorServer.VALIDATE_ID + "\""),
                ValidatorServer.VALIDATE.lines().findFirst().orElse(""));
        assertTrue(ValidatorServer.API.startsWith("!!id:\"" + ValidatorServer.API_ID + "\""),
                ValidatorServer.API.lines().findFirst().orElse(""));
    }
}
