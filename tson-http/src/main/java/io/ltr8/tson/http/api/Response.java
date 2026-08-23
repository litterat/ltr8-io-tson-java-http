package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * One response: a status, and the type of its body.
 *
 * <p>{@code body} is absent for a 204, or for a document served as bytes. {@code description} is a field
 * rather than an {@code @doc} because a response is a <em>value</em> inside an operation's payload, not a
 * schema entry, so there is nothing for an annotation to attach to.
 */
@Typename(name = "response")
public record Response(int status, Optional<TypeRef> body, Optional<String> description) {
}
