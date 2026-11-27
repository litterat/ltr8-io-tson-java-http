package io.ltr8.tson.http.probe.operationrefinement;

import java.util.List;

/**
 * The endpoint base, abstract in the probe's layer as {@code http.endpoint} is, so an untagged endpoint is refused
 * naming the subtypes. A description's own subtype of {@code operation} has no class here: the question is which class
 * it reaches.
 */
public sealed interface Endpoint permits Operation, Binding, Document {

    /** The success status, injected as 200 where nothing writes or pins it. */
    int status();

    /** The media types listed, empty when none is. */
    List<String> mediaTypes();
}
