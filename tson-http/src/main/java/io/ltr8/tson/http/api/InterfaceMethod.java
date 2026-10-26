package io.ltr8.tson.http.api;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * One method of one interface: the unit an api's {@code implements} claim is held in, never a name alone, since two
 * interfaces may both declare {@code get_order}. {@code owner} is the interface's qualified name ({@code
 * orders.orders}), and the string form is the reference that names the method.
 */
public record InterfaceMethod(String owner, String name) {

    private static final String METHODS = ".methods.";

    /** The method a {@code ref<method>} names: the interface it walks through, and the key it ends at. */
    static InterfaceMethod of(TypeRef reference) {
        String written = reference.name();
        int at = written.lastIndexOf(METHODS);
        if (at < 0) {
            throw new IllegalStateException("'" + written + "' reaches a method other than through an interface's "
                    + "methods, which meta-service.tn declares no way to do");
        }
        return new InterfaceMethod(written.substring(0, at), written.substring(at + METHODS.length()));
    }

    @Override
    public String toString() {
        return owner + METHODS + name;
    }
}
