package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The sketch's {@code exemption}: why a claimed method has no endpoint in this api.
 *
 * <p>{@code reason} is the sole required field, which is what keeps the short positional spelling
 * ({@code get_order => "served by the read replica"}) legal; {@code interface} names the implemented interface
 * whose method is exempt, and is needed only when two of them declare the name.
 */
@Typename(name = "exemption")
public record Exemption(String reason, @Field("interface") Optional<String> owner) {
}
