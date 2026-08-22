package io.ltr8.tson.http.javalin;

import io.javalin.http.Context;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonHttpException;
import io.ltr8.tson.http.TsonMediaType;
import io.ltr8.tson.tree.TsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * One request and its response, in TSON terms -- the Javalin counterpart of {@code tson-http-jdk}'s
 * {@code TsonExchange}, deliberately the same shape so the two adapters can be read against each other.
 *
 * <p><b>Javalin buffers a result but streams an output stream.</b> {@link #respondBytes} hands Javalin a
 * {@code byte[]} and lets it set {@code Content-Length}; {@link #respond} takes the servlet output stream and
 * writes into it as the document is produced. Status and content type are set before the stream is taken,
 * because taking it is what starts the response.
 */
public final class TsonContext {

    private final Context context;
    private final TsonHttpCodec codec;
    private boolean committed;

    TsonContext(Context context, TsonHttpCodec codec) {
        this.context = context;
        this.codec = codec;
    }

    /** The request method, uppercase. */
    public String method() {
        return context.method().name();
    }

    /** The request path, without the query. */
    public String path() {
        return context.path();
    }

    /** One request header, or {@code null} if the request carried none by that name. */
    public String header(String name) {
        return context.header(name);
    }

    /** The underlying Javalin context, for anything this class does not cover. */
    public Context context() {
        return context;
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
        context.header("Allow", String.join(", ", allowed));
        throw new TsonHttpException(405, "Method not allowed",
                method() + " is not allowed here; try " + String.join(", ", allowed), List.of(), null);
    }

    /** Reads the request body into a tree, validated against whatever schema it names. */
    public TsonValue readTree() {
        return codec.readTree(context.bodyInputStream(), context.contentType());
    }

    /** Reads the request body into a tree against a stated schema and root type. */
    public TsonValue readTreeAs(String schemaUri, String typeName) {
        return codec.readTreeAs(context.bodyInputStream(), context.contentType(), schemaUri, typeName);
    }

    /** Reads the request body into a bound object, validated against whatever schema it names. */
    public <T> T readObject(Class<T> targetClass) {
        return codec.readObject(context.bodyInputStream(), context.contentType(), targetClass);
    }

    /** Reads the request body into a bound object against a stated schema and root type. */
    public <T> T readObjectAs(String schemaUri, String typeName, Class<T> targetClass) {
        return codec.readObjectAs(context.bodyInputStream(), context.contentType(), schemaUri, typeName,
                targetClass);
    }

    /**
     * Sends {@code value} as an {@code application/tson} body, <b>streamed</b> into the response as it is
     * produced, so a large document never exists as a {@code String}. No {@code Content-Length}, since the
     * length is not known when the stream is taken.
     */
    public void respond(int status, Object value) {
        stream(status, () -> codec.writeTo(value, context.outputStream()));
    }

    /** {@link #respond} for a tree. */
    public void respondTree(int status, TsonValue value) {
        stream(status, () -> codec.writeTreeTo(value, context.outputStream()));
    }

    /** Sends a body already in hand, letting Javalin set {@code Content-Length}. */
    public void respondBytes(int status, byte[] body) {
        commit();
        context.status(status).contentType(TsonMediaType.APPLICATION_TSON.toString()).result(body);
    }

    /** Sends a status and no body. */
    public void respondEmpty(int status) {
        commit();
        context.status(status);
    }

    /** Sets a response header. Must be called before the response is sent. */
    public void setHeader(String name, String value) {
        if (committed) {
            throw new IllegalStateException("cannot set '" + name + "': the response has already been sent");
        }
        context.header(name, value);
    }

    private void stream(int status, ThrowingWrite write) {
        commit();
        // Before the stream is taken: taking it is what starts the response, after which neither can change.
        context.status(status).contentType(TsonMediaType.APPLICATION_TSON.toString());
        try {
            write.run();
            context.outputStream().flush();
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
    private interface ThrowingWrite {
        void run() throws IOException;
    }
}
