package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.ValueRef;

/**
 * One exchange of one protocol: the unit an http projection's {@code @service.binds} claim is held in, never a name
 * alone, since two protocols may both declare {@code get_order}. {@code owner} is the name of the entry applying
 * {@code service.protocol} ({@code orders}), {@code name} the exchange's key in it, and the string form is the
 * qualified name that references the exchange ({@code orders.get_order}).
 */
public record ExchangeName(String owner, String name) {

    /**
     * The exchange a {@code ref<exchange>} names, read from its name alone, delivered or not: the qualified name split
     * on its last {@code .}, the protocol's entry before it and the exchange's key after. An exchange name is an
     * identifier, so it holds no {@code .}.
     */
    static ExchangeName of(ValueRef<?> reference) {
        String written = reference.name();
        int at = written.lastIndexOf('.');
        if (at <= 0 || at == written.length() - 1) {
            throw new IllegalArgumentException("'" + written + "' is not an exchange's qualified name -- write the "
                    + "protocol's entry and the exchange's key, as orders.place_order");
        }
        return new ExchangeName(written.substring(0, at), written.substring(at + 1));
    }

    @Override
    public String toString() {
        return owner + "." + name;
    }
}
