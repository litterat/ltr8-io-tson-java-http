package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * One method of one interface: the unit an api's {@code @implements} claim is held in, never a name alone, since two
 * interfaces may both declare {@code get_order}. {@code owner} is the name of the entry applying {@code interface}
 * ({@code orders}), {@code name} the method's key in it, and the string form is the qualified name that references the
 * method ({@code orders.get_order}).
 */
public record InterfaceMethod(String owner, String name) {

    /**
     * The method a {@code ref<method>} names: the qualified name split on its last {@code .}, the interface's entry
     * before it and the method's key after. A method name is an identifier, so it holds no {@code .}.
     */
    static InterfaceMethod of(TypeRef reference) {
        String written = reference.name();
        int at = written.lastIndexOf('.');
        if (at <= 0 || at == written.length() - 1) {
            throw new IllegalArgumentException("'" + written + "' is not a method's qualified name -- write the "
                    + "interface's entry and the method's key, as orders.place_order");
        }
        return new InterfaceMethod(written.substring(0, at), written.substring(at + 1));
    }

    @Override
    public String toString() {
        return owner + "." + name;
    }
}
