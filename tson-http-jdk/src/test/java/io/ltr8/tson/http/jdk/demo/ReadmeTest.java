package io.ltr8.tson.http.jdk.demo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README, executed against the real demo server.
 *
 * <p><b>Why this exists.</b> This project's own rule is that a demo nobody exercises is documentation that
 * quietly stops being true — which is why every demo server here is driven by a test rather than only
 * printed. The README was the one place the rule was not applied, and it duly went stale: its {@code curl}
 * examples named {@code problem-1.tn} through three version bumps that made them wrong, and nothing noticed
 * until the versions were collapsed back and the examples became accidentally correct again.
 *
 * <p><b>What it checks is derived from the README's text</b>, not copied from it — the paths it tells a reader
 * to fetch, the schema URLs it prints, and the request bodies it says to post. Editing the README to say
 * something untrue fails here; editing it to say something true in different words does not.
 *
 * <p>What it cannot check: the Java snippets, which are illustrative rather than compiled, and prose. So the
 * README should avoid stating facts that go stale and nothing verifies — a test count, for instance, which is
 * why it no longer gives one.
 */
class ReadmeTest {

    private static final Pattern CURL_PATH = Pattern.compile("curl -s(?:[^\\n]*?)localhost:8080(\\S*)");
    private static final Pattern SCHEMA_URL = Pattern.compile("https://[\\w./-]+\\.tn");
    private static final Pattern POSTED_BODY = Pattern.compile("--data-binary '(.*?)'", Pattern.DOTALL);

    private static String readme;
    private HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        readme = Files.readString(Path.of("..", "README.md"));
        server = OrderServer.start(0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        client.close();
    }

    private static List<String> matches(Pattern pattern) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(readme);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/tson")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Every schema URL the README prints is one this server actually publishes, at that URL's own path. */
    @Test
    void everySchemaTheReadmeNamesIsPublished() throws Exception {
        Set<String> named = new LinkedHashSet<>();
        Matcher m = SCHEMA_URL.matcher(readme);
        while (m.find()) {
            named.add(m.group());
        }
        assertFalse(named.isEmpty(), "the README should show at least one schema URL");

        for (String url : named) {
            String path = URI.create(url).getPath();
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), url + " is printed by the README but not served at " + path);
            assertTrue(response.body().startsWith("!!id:\"" + url + "\""),
                    () -> path + " serves a document that is not " + url + ": "
                            + response.body().lines().findFirst().orElse(""));
        }
    }

    /** Every path the README tells a reader to GET answers, and answers TSON. */
    @Test
    void everyGetTheReadmeShowsAnswers() throws Exception {
        List<String> paths = matches(CURL_PATH).stream().filter(p -> !p.equals("/orders")).toList();
        assertFalse(paths.isEmpty(), "the README should show at least one GET");

        for (String path : paths) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), "the README says to fetch " + path);
            assertEquals("application/tson", response.headers().firstValue("Content-Type").orElseThrow());
        }
    }

    /**
     * The two bodies the README says to post, posted — and the claims it makes about the replies.
     *
     * <p>The first is the "quantity doubled" claim; the second is the one the README draws a conclusion from,
     * that both problems arrive in one response rather than one per round trip.
     */
    @Test
    void thePostedBodiesGetTheRepliesTheReadmeShows() throws Exception {
        List<String> bodies = matches(POSTED_BODY);
        assertEquals(2, bodies.size(), "the README shows two POSTs: a valid order and an empty one");

        // Derived, not copied: the quantity the README posts, and the one it shows coming back. The demo
        // doubles it, so the README's own arithmetic is checked as well as the server's.
        Matcher posted = Pattern.compile("quantity:\\s*(\\d+)").matcher(bodies.getFirst());
        assertTrue(posted.find(), bodies.getFirst());
        int sent = Integer.parseInt(posted.group(1));

        HttpResponse<String> valid = post("/orders", bodies.getFirst());
        assertEquals(201, valid.statusCode(), valid.body());
        assertTrue(valid.body().contains("quantity: " + sent * 2),
                () -> "posted " + sent + ", so the reply doubles it: " + valid.body());

        Matcher shown = Pattern.compile("!order \\{ sku: \"ABC-1\" quantity: (\\d+) \\}").matcher(readme);
        assertTrue(shown.find(), "the README should show the reply it describes");
        assertEquals(sent * 2, Integer.parseInt(shown.group(1)),
                "the README shows a reply the server would not send");
        assertTrue(valid.body().contains(OrderServer.SCHEMA_ID),
                () -> "the reply names the schema governing it: " + valid.body());

        HttpResponse<String> empty = post("/orders", bodies.get(1));
        assertEquals(400, empty.statusCode(), empty.body());
        assertTrue(empty.body().contains(io.ltr8.tson.http.TsonProblemSchema.ID),
                () -> "the error body names the problem schema: " + empty.body());
        assertTrue(empty.body().contains("/sku") && empty.body().contains("/quantity"),
                () -> "both problems in one response, which is the point the README makes: " + empty.body());
        assertTrue(empty.body().contains("FIELD_REQUIRED"), empty.body());
    }

    /** The module table names the schemas this core owns; they are the ones it actually ships. */
    @Test
    void theModuleTableNamesTheSchemasThatExist() {
        assertTrue(readme.contains("`problem-1.tn`"),
                "the table should name the error-body schema it ships");
        assertTrue(readme.contains("`meta-http-1.tn`"),
                "the table should name the API-description meta layer it ships");
        assertTrue(readme.contains(io.ltr8.tson.http.TsonProblemSchema.ID.substring(
                        io.ltr8.tson.http.TsonProblemSchema.ID.lastIndexOf('/') + 1)),
                "and the name in the table must be the current one");
    }

    /** A test count in prose is a fact nothing maintains, so the README should not state one. */
    @Test
    void theReadmeDoesNotClaimATestCount() {
        assertFalse(readme.matches("(?s).*\\d+ tests.*"),
                "a count in prose goes stale silently -- say what is covered, not how many");
    }
}
