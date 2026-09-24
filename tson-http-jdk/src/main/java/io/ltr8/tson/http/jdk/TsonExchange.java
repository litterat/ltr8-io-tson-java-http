package io.ltr8.tson.http.jdk;

import com.sun.net.httpserver.HttpExchange;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.http.TsonSchemaHeader;
import io.ltr8.tson.tree.TsonValue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * One request and its response, in TSON terms. The whole of what this adapter is: an {@link HttpExchange} on one
 * side, {@link TsonHttpCodec} on the other, and nothing of its own about TSON.
 *
 * <p><b>A response is committed once, and the boundary needs to know whether it has been.</b> {@code
 * com.sun.net.httpserver} sends status and headers in one call, so after {@link #respond} the status can no
 * longer change -- an exception thrown after that point cannot become a 500, and {@link TsonHandler}'s error
 * boundary must not try. {@link #committed()} is how it finds out.
 *
 * <p><b>A response is written in the representation the boundary negotiated</b> ({@link #representation()}):
 * TSON, or JSON where the codec admits it and the client prefers it. {@link #respond} and {@link
 * #respondDescribed} follow it; {@link #respondTree} and {@link #respondBytes} can only carry TSON, and send it
 * wherever the client accepts it at all.
 *
 * <p><b>Reading is what validates.</b> The read methods delegate to the codec, so a body that breaks its schema
 * throws {@link TsonHttpException} with every diagnostic, the boundary turns that into a 400 carrying the whole
 * list, and a handler that simply reads and works with the result has correct validation behaviour without
 * writing any.
 */
public final class TsonExchange {

    private final HttpExchange exchange;
    private final TsonHttpCodec codec;
    private TsonHttpCodec.Representation representation = TsonHttpCodec.Representation.TSON;
    private boolean committed;

    TsonExchange(HttpExchange exchange, TsonHttpCodec codec) {
        this.exchange = exchange;
        this.codec = codec;
    }

    /** The request method, uppercase. */
    public String method() {
        return exchange.getRequestMethod();
    }

    /** The request URI, path and query as sent. */
    public URI uri() {
        return exchange.getRequestURI();
    }

    /** One request header, or {@code null} if the request carried none by that name. */
    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /** The underlying exchange, for anything this class does not cover. */
    public HttpExchange exchange() {
        return exchange;
    }

    /** The codec this exchange reads and writes through. */
    public TsonHttpCodec codec() {
        return codec;
    }

    /** How this request's response is written -- what the boundary negotiated from its {@code Accept}. */
    public TsonHttpCodec.Representation representation() {
        return representation;
    }

    /** Set by the boundary once {@code Accept} is negotiated; until then, and if it fails, TSON. */
    void representation(TsonHttpCodec.Representation negotiated) {
        this.representation = negotiated;
    }

    /** Whether the response has been sent, after which its status can no longer change. */
    public boolean committed() {
        return committed;
    }

    /**
     * Rejects a method this route does not serve, answering 405 with the {@code Allow} header RFC 9110 §15.5.6
     * requires -- which is why this exists rather than each handler writing its own comparison.
     *
     * @throws TsonHttpException 405 if {@link #method()} is not one of {@code allowed}
     */
    public void requireMethod(String... allowed) {
        if (Arrays.asList(allowed).contains(method())) {
            return;
        }
        exchange.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new TsonHttpException(405, TsonHttpException.TYPES + "method-not-allowed",
                "Method not allowed",
                method() + " is not allowed here; try " + String.join(", ", allowed), List.of(), null);
    }

    /** Reads the request body into a tree, validated against whatever schema it names. */
    public TsonValue readTree() {
        return codec.readTree(exchange.getRequestBody(), contentType());
    }

    /** Reads the request body into a tree against a stated schema and root type. */
    public TsonValue readTreeAs(String schemaUri, String typeName) {
        return codec.readTreeAs(exchange.getRequestBody(), contentType(), schemaUri, typeName);
    }

    /** Reads the request body into a bound object, validated against whatever schema it names. */
    public <T> T readObject(Class<T> targetClass) {
        return codec.readObject(exchange.getRequestBody(), contentType(), targetClass);
    }

    /** Reads the request body into a bound object against a stated schema and root type. */
    public <T> T readObjectAs(String schemaUri, String typeName, Class<T> targetClass) {
        return codec.readObjectAs(exchange.getRequestBody(), contentType(), schemaUri, typeName, targetClass);
    }

    /**
     * Sends {@code value} in the negotiated {@link #representation()}, <b>streamed</b> -- the document is written
     * into the response as it is produced rather than built first, so a large one never exists as a {@code
     * String}. The response is chunked, since its length is not known when the headers go out.
     *
     * @throws IllegalStateException if the response has already been sent
     */
    public void respond(int status, Object value) {
        stream(status, representation.mediaType(), out -> codec.writeTo(value, representation, out));
    }

    /**
     * {@link #respond}, naming the schema that governs the reply: in the {@code TSON-Schema} header always, and
     * in band as well where the representation is TSON ({@code !!schema} and a root type-ref). A JSON body has no
     * in-band channel, so the header is its only one ([TSON-JSON] §3.5).
     */
    public void respondDescribed(int status, Object value, String schemaUri, String rootTypeName) {
        setHeader(TsonSchemaHeader.NAME, TsonSchemaHeader.format(schemaUri));
        stream(status, representation.mediaType(),
                out -> codec.writeTo(value, schemaUri, rootTypeName, representation, out));
    }

    /**
     * {@link #respond} for a tree, which only TSON can carry: sent as {@code application/tson} wherever the client
     * accepts it at all.
     *
     * @throws TsonHttpException 406 if the client accepts no TSON
     */
    public void respondTree(int status, TsonValue value) {
        stream(status, representation.requireTson(), out -> codec.writeTreeTo(value, out));
    }

    /**
     * Sends TSON already in hand, with a real {@code Content-Length} -- for a caller that wants a length more than
     * it minds materialising the document. The bytes are the caller's own encoding, so they are labelled {@code
     * application/tson} and sent wherever the client accepts it at all.
     *
     * @throws TsonHttpException 406 if the client accepts no TSON
     */
    public void respondBytes(int status, byte[] body) {
        send(status, representation.requireTson(), body);
    }

    /** Sends an error body the codec rendered in the negotiated representation. The boundary's, and only its. */
    void respondProblem(int status, byte[] body) {
        send(status, representation.problemMediaType(), body);
    }

    private void send(int status, TsonMediaType mediaType, byte[] body) {
        commit();
        try {
            exchange.getResponseHeaders().set("Content-Type", mediaType.toString());
            if (isHead()) {
                // §15.4 of RFC 9110: a HEAD response carries the headers its GET would, and no body. -1 is
                // this server's "no body at all", distinct from 0, which means "chunked, length unknown".
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sends a status and no body -- a 204, or a HEAD that has nothing to describe. */
    public void respondEmpty(int status) {
        commit();
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sets a response header. Must be called before the response is sent. */
    public void setHeader(String name, String value) {
        if (committed) {
            throw new IllegalStateException("cannot set '" + name + "': the response has already been sent");
        }
        exchange.getResponseHeaders().set(name, value);
    }

    private boolean isHead() {
        return "HEAD".equals(method());
    }

    private String contentType() {
        return header("Content-Type");
    }

    private void stream(int status, TsonMediaType mediaType, ThrowingWriter write) {
        commit();
        try {
            exchange.getResponseHeaders().set("Content-Type", mediaType.toString());
            if (isHead()) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            // 0 is this server's "chunked, length unknown", which is the price of not materialising the body.
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                write.writeTo(out);
            }
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
