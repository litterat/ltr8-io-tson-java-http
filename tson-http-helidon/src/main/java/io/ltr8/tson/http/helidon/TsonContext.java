package io.ltr8.tson.http.helidon;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.tree.TsonValue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * One request and its response, in TSON terms -- the Helidon counterpart of {@code tson-http-jdk}'s
 * {@code TsonExchange} and {@code tson-http-javalin}'s {@code TsonContext}, deliberately the same shape so the
 * three adapters can be read against each other.
 *
 * <p>Helidon keeps request and response as two objects rather than one exchange, which is the only structural
 * difference; everything else is the same translation.
 */
public final class TsonContext {

    private final ServerRequest request;
    private final ServerResponse response;
    private final TsonHttpCodec codec;
    private boolean committed;

    TsonContext(ServerRequest request, ServerResponse response, TsonHttpCodec codec) {
        this.request = request;
        this.response = response;
        this.codec = codec;
    }

    /** The request method, uppercase. */
    public String method() {
        return request.prologue().method().text();
    }

    /** The request path, without the query. */
    public String path() {
        return request.path().path();
    }

    /** One request header, or {@code null} if the request carried none by that name. */
    public String header(String name) {
        return request.headers().first(HeaderNames.create(name)).orElse(null);
    }

    /** The underlying request, for anything this class does not cover. */
    public ServerRequest request() {
        return request;
    }

    /** The underlying response, for anything this class does not cover. */
    public ServerResponse response() {
        return response;
    }

    /** The codec this context reads and writes through. */
    public TsonHttpCodec codec() {
        return codec;
    }

    /** Whether this handler has answered. */
    public boolean committed() {
        return committed;
    }

    /**
     * Rejects a method this route does not serve, answering 405 with the {@code Allow} header RFC 9110 §15.5.6
     * requires.
     *
     * @throws TsonHttpException 405 if {@link #method()} is not one of {@code allowed}
     */
    public void requireMethod(String... allowed) {
        if (Arrays.asList(allowed).contains(method())) {
            return;
        }
        response.header(HeaderNames.ALLOW.defaultCase(), String.join(", ", allowed));
        throw new TsonHttpException(405, "Method not allowed",
                method() + " is not allowed here; try " + String.join(", ", allowed), List.of(), null);
    }

    /** Reads the request body into a tree, validated against whatever schema it names. */
    public TsonValue readTree() {
        return codec.readTree(request.content().inputStream(), contentType());
    }

    /** Reads the request body into a tree against a stated schema and root type. */
    public TsonValue readTreeAs(String schemaUri, String typeName) {
        return codec.readTreeAs(request.content().inputStream(), contentType(), schemaUri, typeName);
    }

    /** Reads the request body into a bound object, validated against whatever schema it names. */
    public <T> T readObject(Class<T> targetClass) {
        return codec.readObject(request.content().inputStream(), contentType(), targetClass);
    }

    /** Reads the request body into a bound object against a stated schema and root type. */
    public <T> T readObjectAs(String schemaUri, String typeName, Class<T> targetClass) {
        return codec.readObjectAs(request.content().inputStream(), contentType(), schemaUri, typeName,
                targetClass);
    }

    /**
     * Sends {@code value} as an {@code application/tson} body, <b>streamed</b> into the response as it is
     * produced, so a large document never exists as a {@code String}.
     */
    public void respond(int status, Object value) {
        stream(status, out -> codec.writeTo(value, out));
    }

    /** {@link #respond} for a tree. */
    public void respondTree(int status, TsonValue value) {
        stream(status, out -> codec.writeTreeTo(value, out));
    }

    /** Sends a body already in hand, letting Helidon set {@code Content-Length}. */
    public void respondBytes(int status, byte[] body) {
        commit();
        response.status(status).header(HeaderNames.CONTENT_TYPE.defaultCase(),
                TsonMediaType.APPLICATION_TSON.toString());
        response.send(body);
    }

    /** Sends a status and no body. */
    public void respondEmpty(int status) {
        commit();
        response.status(status).send();
    }

    /** Sets a response header. Must be called before the response is sent. */
    public void setHeader(String name, String value) {
        if (committed) {
            throw new IllegalStateException("cannot set '" + name + "': the response has already been sent");
        }
        response.header(name, value);
    }

    private String contentType() {
        return header("Content-Type");
    }

    private void stream(int status, ThrowingWriter write) {
        commit();
        // Before the stream is taken: taking it starts the response, after which neither can change.
        response.status(status).header(HeaderNames.CONTENT_TYPE.defaultCase(),
                TsonMediaType.APPLICATION_TSON.toString());
        try (OutputStream out = response.outputStream()) {
            write.writeTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commit() {
        if (committed) {
            throw new IllegalStateException("the response has already been sent");
        }
        committed = true;
    }

    @FunctionalInterface
    private interface ThrowingWriter {
        void writeTo(OutputStream out) throws IOException;
    }
}
