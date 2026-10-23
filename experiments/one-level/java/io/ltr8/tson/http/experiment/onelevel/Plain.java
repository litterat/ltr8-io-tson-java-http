package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.annotation.Typename;

import java.util.Map;

/** {@code plain => { entries: {identifier => meth} }}: a record holding a map that is no namespace. */
@Typename(name = "plain")
public record Plain(Map<String, Meth> entries) {
}
