package io.ltr8.tson.http.helidon;

import io.helidon.common.GenericType;
import io.helidon.http.Headers;
import io.helidon.http.HeaderNames;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.EntityReader;
import io.helidon.http.media.EntityWriter;
import io.helidon.http.media.MediaSupport;
import io.ltr8.tson.http.TsonAcceptHeader;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.TsonMediaType;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Registers TSON with Helidon's own entity machinery, so an ordinary Helidon handler reads and writes it with
 * no TSON-specific code at all:
 *
 * <pre>{@code
 * WebServer.builder()
 *         .mediaContext(MediaContext.builder().addMediaSupport(TsonMediaSupport.create(codec)).build())
 *         .routing(routing -> {
 *             TsonHandler.install(routing, codec);              // so a rejection is still a TSON problem body
 *             routing.post("/orders", (req, res) -> {
 *                 Order order = req.content().as(Order.class);  // validated, or 400 with every diagnostic
 *                 res.send(new Order(order.sku(), order.quantity() * 2));
 *             });
 *         })
 *         .build();
 * }</pre>
 *
 * <p><b>This is the Helidon adapter's distinctive half.</b> The other two adapters can only offer a wrapper
 * type, because their frameworks have no equivalent seam; Helidon's {@code MediaSupport} SPI means TSON
 * becomes a media type the framework itself knows, and existing handler code keeps working.
 *
 * <p><b>It still validates, and it still fails the same way.</b> Both directions go through the same
 * {@link TsonHttpCodec} as {@link TsonContext}, so a body that breaks its schema raises the same
 * {@code TsonHttpException} carrying every diagnostic. What it does <em>not</em> have is a handler boundary to
 * catch it -- the read happens inside Helidon's entity machinery, before any code of this adapter's runs -- so
 * <b>{@link TsonHandler#install} is not optional when using this</b>. Without it, Helidon renders its own error
 * page and the diagnostics are lost.
 *
 * <p><b>Support level says why it matched.</b> A request that names {@code application/tson} is
 * {@code SUPPORTED}; one that names nothing at all is {@code COMPATIBLE}, so an explicit handler for another
 * type still wins; anything that names a different type is {@code NOT_SUPPORTED}. The same three-way answer on
 * the write side reads {@code Accept} through {@link TsonAcceptHeader}, which is where q-values and
 * specificity are already handled.
 */
public final class TsonMediaSupport implements MediaSupport {

    private final TsonHttpCodec codec;
    private final String name;

    private TsonMediaSupport(TsonHttpCodec codec, String name) {
        this.codec = codec;
        this.name = name;
    }

    /** TSON media support over {@code codec}. */
    public static TsonMediaSupport create(TsonHttpCodec codec) {
        return new TsonMediaSupport(codec, "tson");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "tson";
    }

    @Override
    public <T> ReaderResponse<T> reader(GenericType<T> type, Headers requestHeaders) {
        return switch (level(requestHeaders)) {
            case NOT_SUPPORTED -> ReaderResponse.unsupported();
            case SupportLevel level -> new ReaderResponse<>(level, () -> new Reader<>(codec));
        };
    }

    @Override
    public <T> WriterResponse<T> writer(GenericType<T> type, Headers requestHeaders,
                                        WritableHeaders<?> responseHeaders) {
        String accept = requestHeaders.first(HeaderNames.ACCEPT).orElse(null);
        if (!TsonAcceptHeader.parse(accept).acceptsTson()) {
            return WriterResponse.unsupported();
        }
        // COMPATIBLE rather than SUPPORTED when the client only said */* -- it will take TSON, but it did not
        // ask for it, so a media support that was actually named should win.
        SupportLevel level = accept != null && accept.contains(TsonMediaType.APPLICATION_TSON.toString())
                ? SupportLevel.SUPPORTED
                : SupportLevel.COMPATIBLE;
        return new WriterResponse<>(level, () -> new Writer<>(codec));
    }

    /** How well this support matches what the request says its body is. */
    private static SupportLevel level(Headers requestHeaders) {
        return requestHeaders.first(HeaderNames.CONTENT_TYPE)
                .map(contentType -> {
                    try {
                        return TsonMediaType.parse(contentType).isTson()
                                ? SupportLevel.SUPPORTED
                                : SupportLevel.NOT_SUPPORTED;
                    } catch (IllegalArgumentException notAMediaType) {
                        return SupportLevel.NOT_SUPPORTED;
                    }
                })
                // No Content-Type: RFC 9110 §8.3 permits examining the content, and [TSON-DATA] §7.1 makes a
                // document classifiable from its opening bytes -- so this can read it, but should not outrank
                // a support the request actually named.
                .orElse(SupportLevel.COMPATIBLE);
    }

    /** Reads an entity by delegating to the codec, so validation is identical to a handler-driven read. */
    private record Reader<T>(TsonHttpCodec codec) implements EntityReader<T> {

        @Override
        public T read(GenericType<T> type, InputStream stream, Headers requestHeaders) {
            return codec.readObject(stream, requestHeaders.first(HeaderNames.CONTENT_TYPE).orElse(null),
                    rawClass(type));
        }

        @Override
        public T read(GenericType<T> type, InputStream stream, Headers requestHeaders, Headers responseHeaders) {
            return read(type, stream, requestHeaders);
        }

        @SuppressWarnings("unchecked")
        private static <T> Class<T> rawClass(GenericType<T> type) {
            return (Class<T>) type.rawType();
        }
    }

    /** Writes an entity by delegating to the codec, streaming into Helidon's own output stream. */
    private record Writer<T>(TsonHttpCodec codec) implements EntityWriter<T> {

        @Override
        public void write(GenericType<T> type, T object, OutputStream stream, Headers requestHeaders,
                          WritableHeaders<?> responseHeaders) {
            write(type, object, stream, responseHeaders);
        }

        @Override
        public void write(GenericType<T> type, T object, OutputStream stream,
                          WritableHeaders<?> responseHeaders) {
            responseHeaders.set(HeaderNames.CONTENT_TYPE, TsonMediaType.APPLICATION_TSON.toString());
            codec.writeTo(object, stream);
            try {
                stream.close();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
