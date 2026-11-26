package io.ltr8.tson.service.http;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What sits under a verb in a {@link Resource}: an {@link Operation} carrying its signature inline, a {@link
 * Binding} borrowing an exchange's, or a {@link Document} answering bytes of a media type it lists. The schema
 * declares {@code service.endpoint} {@code abstract}, so the resolver refuses an untagged endpoint, or a bare {@code
 * !service.endpoint}, naming the subtypes; this sealed interface is the Java side of the same fact, the binder's
 * target for an {@code endpoint}-typed slot with no record of its own to construct.
 */
@Typename(name = "service.endpoint")
public sealed interface Endpoint permits Operation, Binding, Document {

    /** Request fields carried in the query string. */
    List<String> query();

    /** Request fields carried in headers, each to the header that carries it, held folded. */
    Map<String, String> headers();

    /** The one request field carried unwrapped as the whole body, if one is. */
    Optional<String> body();

    /** The success status. */
    int status();

    /**
     * The media types listed, as written: what an endpoint speaks beside {@code application/tson}, or the whole
     * contract of a {@link Document}'s body. {@link Route#mediaTypes()} is the resolved set.
     */
    List<String> mediaTypes();

    /**
     * The security schemes this endpoint names, each a reference delivered its scheme: empty when the endpoint
     * writes no {@code security}, so it inherits its projection's; present and empty when it writes {@code security:
     * []}, which says it is open. {@link Route#security()} is the list in force.
     */
    Optional<List<ValueRef<SecurityScheme>>> security();

    /**
     * The headers a success response carries, each folded name to its value as written: an RFC 6570 level-1 template
     * over the response type's fields, or a literal. {@link TsonServiceDescription} checks every variable at startup.
     */
    Map<String, String> responseHeaders();
}
