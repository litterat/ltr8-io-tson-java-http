package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.annotation.Typename;

/** The same slot as {@link Bind}, bound to a {@code String}: it receives the name as text, judged all the same. */
@Typename(name = "bind_text")
public record BindText(String m) {
}
