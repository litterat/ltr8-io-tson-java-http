package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What sits under a verb in a {@link Resource}: an {@link Operation} carrying its signature inline, a {@link
 * Binding} borrowing a method's, or a {@link Document} answering bytes of a media type. A sealed interface, so the
 * binder has a target for an {@code endpoint}-typed slot and a bare {@code !endpoint} has nothing to construct --
 * the schema alone would admit one, and this binding is what makes the base abstract.
 */
@Typename(name = "endpoint")
public sealed interface Endpoint permits Operation, Binding, Document {

    /** Request fields carried in the query string. */
    List<String> query();

    /** Request fields carried in headers, each to the header that carries it, held folded. */
    Map<String, String> headers();

    /** The one request field carried unwrapped as the whole body, if one is. */
    Optional<String> body();

    /** The success status. */
    int status();

    /** The encodings spoken beside TSON, which every endpoint speaks. */
    List<Encoding> encodings();
}
