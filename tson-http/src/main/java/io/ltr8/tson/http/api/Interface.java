package io.ltr8.tson.http.api;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * An interface: a named, documented map of methods, and the interfaces it extends. Each {@code extends} entry is
 * a reference the resolver judged, held as the qualified name written ({@code orders.orders}); extending is not
 * composing, so an inherited method stays the declaring interface's.
 */
@Typename(name = "interface")
public record Interface(@Field("extends") List<TypeRef> extended, AnnotatedMap<String, Method> methods) {

    public Interface {
        extended = extended == null ? List.of() : List.copyOf(extended);
        methods = methods == null ? new AnnotatedMap<>() : methods;
    }
}
