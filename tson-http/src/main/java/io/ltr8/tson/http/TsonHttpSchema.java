package io.ltr8.tson.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * This project's {@code http.tn} -- HTTP's own vocabulary ({@code http_method}, {@code status_code},
 * {@code header_name}, {@code uri_template}) as a library a schema imports -- as source text a server can serve.
 *
 * <p><b>This project's library, not the spec's.</b> These are HTTP's terms rather than formats every schema
 * needs, and the names a converted API description declares for itself, so they are imported where wanted and
 * reserve their names nowhere else. {@code meta-http.tn} imports it, so a server publishing an API description
 * publishes this too ({@code TsonApiSchema.publishedById()} carries both).
 */
public final class TsonHttpSchema {

    /** This library's identity -- the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/38/io/ltr8/http/http.tn";

    private static final String SOURCE = readResource("/http.tn");

    private TsonHttpSchema() {
    }

    /** The schema source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /** The published history keyed by identity -- one entry, since nothing here is published yet. */
    public static Map<String, String> publishedById() {
        return Map.of(ID, SOURCE);
    }

    private static String readResource(String path) {
        try (InputStream in = TsonHttpSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
