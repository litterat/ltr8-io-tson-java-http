package io.ltr8.tson.http.probe;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A minimal operation shape, bound by {@code UpstreamGapsTest}'s probes so they can drive a {@code data &}
 * constructor without depending on {@code meta-http-1.tn}'s real one: responses as bare type references,
 * rather than as the {@code !response { status: … body: … }} data records the shipped meta layer declares.
 *
 * <p>The class must be named {@code Operation}: {@code DefaultDataNameBinder} mangles the schema type name
 * to PascalCase and calls {@code Class.forName}, so {@link Typename} names the schema side but does not make
 * the class discoverable under a different name.
 */
@Typename(name = "operation")
public record Operation(String method, String path, List<TypeRef> responses) implements Data {

    @Override
    public List<TypeRef> references() {
        return responses;
    }
}
