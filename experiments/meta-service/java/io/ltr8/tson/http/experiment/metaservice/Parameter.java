package io.ltr8.tson.http.experiment.metaservice;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;
import java.util.Optional;
@Typename(name = "parameter")
public record Parameter(String name, ParameterLocation in, TypeRef type, boolean required, Optional<String> description) {
}
