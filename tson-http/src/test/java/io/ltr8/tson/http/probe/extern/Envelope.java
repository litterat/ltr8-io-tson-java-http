package io.ltr8.tson.http.probe.extern;

/** {@code envelope-1.tn}'s {@code envelope}, whose {@code body} is typed by an extern of {@code order-1.tn}'s order. */
public record Envelope(String id, OrderV1 body) {
}
