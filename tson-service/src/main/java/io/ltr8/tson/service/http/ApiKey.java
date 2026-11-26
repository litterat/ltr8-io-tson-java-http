package io.ltr8.tson.service.http;

import io.ltr8.annotation.Typename;

/**
 * A key the client carries in a request header or a query parameter.
 *
 * <p>The schema types {@code name} {@code text}, since a query parameter's name is no header name. A header's name
 * is held as {@code service.header_name} holds one, folded under ASCII case folding as RFC 9110 §5.1 compares it:
 * {@code X-Api-Key} is {@code x-api-key}, and nothing beyond ASCII is touched. {@link TsonServiceDescription} refuses a
 * header-borne name that is no RFC 9110 token.
 *
 * @param in   where the key travels
 * @param name the query parameter's name as written, or the header's name folded
 */
@Typename(name = "service.api_key")
public record ApiKey(ApiKeyIn in, String name) implements SecurityScheme {

    public ApiKey {
        if (in == ApiKeyIn.header && name != null) {
            name = asciiFold(name);
        }
    }

    /** {@code A}-{@code Z} to {@code a}-{@code z}, and nothing else: the fold HTTP field names state for themselves. */
    static String asciiFold(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        text.chars().forEach(c -> folded.append((char) (c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c)));
        return folded.toString();
    }
}
