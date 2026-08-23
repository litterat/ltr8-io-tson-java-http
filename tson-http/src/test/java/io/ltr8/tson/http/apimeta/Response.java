package io.ltr8.tson.http.apimeta;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/** One response: a status and the type of its body. Absent body for a 204, or bytes. */
@Typename(name = "response")
public record Response(int status, Optional<TypeRef> body) {
}
