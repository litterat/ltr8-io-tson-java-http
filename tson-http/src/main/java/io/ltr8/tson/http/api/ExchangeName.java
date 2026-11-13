package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.ValueRef;

/**
 * One exchange of one protocol: the unit an http projection's {@code binds} claim is held in, never a name alone,
 * since two protocols may both declare {@code get_order}. {@code owner} is the name of the entry applying {@code
 * service.protocol} ({@code orders}), {@code name} the exchange's key in its {@code exchanges}, and the string form
 * is the qualified name that references the exchange ({@code orders.exchanges.get_order}), so a message naming one
 * can be pasted back into a document.
 */
public record ExchangeName(String owner, String name) {

    /** The field of {@code service.protocol} a qualified name walks to reach an exchange. */
    private static final String FIELD = "exchanges";

    /**
     * The exchange a {@code ref<exchange>} names, read from its name: three segments, the protocol's entry, the field
     * {@code exchanges} and the exchange's key. The resolver judged the name, so anything else is no reference to an
     * exchange of this layer.
     */
    static ExchangeName of(ValueRef<?> reference) {
        String written = reference.name();
        String[] segments = written.split("\\.", -1);
        if (segments.length != 3 || !segments[1].equals(FIELD) || segments[0].isEmpty() || segments[2].isEmpty()) {
            throw new IllegalArgumentException("'" + written + "' is not an exchange's qualified name -- write the "
                    + "protocol's entry, its exchanges field and the exchange's key, as orders.exchanges.place_order");
        }
        return new ExchangeName(segments[0], segments[2]);
    }

    @Override
    public String toString() {
        return owner + "." + FIELD + "." + name;
    }
}
