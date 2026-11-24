package io.ltr8.tson.http.probe.valuereference;

import io.ltr8.tson.schema.meta.ValueRef;

import java.util.List;

/** A record holding references to schemes, as an endpoint's {@code security} does: the alias half's use site. */
public record Guard(List<ValueRef<Scheme>> security) {

    public Guard {
        security = List.copyOf(security);
    }
}
