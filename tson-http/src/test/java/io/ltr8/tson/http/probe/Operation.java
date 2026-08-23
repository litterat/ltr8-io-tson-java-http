package io.ltr8.tson.http.probe;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * The <b>alternative</b> operation shape, for the design {@code SketchTest} weighs and rejects: responses as
 * type references into an ordinary schema, each naming an application of {@code response<T, S>}, rather than
 * as {@code !response { status: … body: … }} data records.
 *
 * <p>Kept because rejecting a design is worth a test. See {@code sketch/README.md} for why this one is not
 * adopted — in short, the template exists in {@code http-api-1.tn} to <em>manufacture types</em>, which is
 * the constraint the {@code data} base kind removed.
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
