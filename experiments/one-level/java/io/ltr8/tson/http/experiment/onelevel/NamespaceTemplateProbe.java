package io.ltr8.tson.http.experiment.onelevel;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.meta.Namespace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A namespace type, measured against what the record-template workaround could do.</b> Before tson-java made
 * {@code namespace} a constructor, a namespace was written as {@code ns => <M> { entries: M }} closed over a
 * namespace map, and walked as {@code orders.entries.get}. Now {@code iface => namespace & { … }} is the map itself:
 * written as the bare map, the names inside it judged, and walked by {@code ref<T>} as {@code orders.get} -- the
 * short path the one-level note asked for, with no reading-side rule needed.
 */
class NamespaceTemplateProbe {

    static final String LAYER = OneLevelProbe.head(OneLevelProbe.LAYER_ID, OneLevelProbe.KERNEL, OneLevelProbe.META)
            + """
            {
              meth       => { request?: type_kind }
              iface      => namespace & { key_type: = identifier  value_type: = meth }
              plain      => { entries: {identifier => meth} }
              plains     => namespace & { key_type: = identifier  value_type: = plain }
              bind       => { m: ref<meth> }
              binds      => namespace & { key_type: = identifier  value_type: = bind }
              bind_text  => { m: ref<meth> }
              bind_texts => namespace & { key_type: = identifier  value_type: = bind_text }
            }""";

    static Tson tson(String schema) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(OneLevelProbe.LAYER_ID, LAYER);
        documents.put(OneLevelProbe.ID, schema);
        Tson tson = OneLevelProbe.tson(documents);
        assertEquals(List.of(), tson.validateSchema(LAYER));
        return tson;
    }

    static String schema(String entries) {
        return OneLevelProbe.head(OneLevelProbe.ID, OneLevelProbe.LAYER_ID, OneLevelProbe.CORE)
                + "{\n  order => { sku: text }\n" + entries + "\n}";
    }

    static List<Diagnostic> resolve(String entries) {
        String schema = schema(entries);
        return tson(schema).validateSchema(schema);
    }

    static Object entry(String entries, String name) {
        String schema = schema(entries);
        Tson tson = tson(schema);
        assertEquals(List.of(), tson.validateSchema(schema));
        return tson.schemaRegistry().get(OneLevelProbe.ID).orElseThrow().schema().entries().get(name);
    }

    /** The value under {@code key} of the namespace entry {@code name}. */
    static Object held(String entries, String name, String key) {
        return assertInstanceOf(Namespace.class, entry(entries, name)).get(key);
    }

    static final String ORDERS = "  orders => !iface { get => { request: order } }\n";

    /** The entry is the map: a {@code Namespace} of the constructor, holding each value under its key. */
    @Test
    void aNamespaceTemplateIsWrittenAsTheBareMap() {
        var orders = assertInstanceOf(Namespace.class, entry(ORDERS, "orders"));
        assertEquals("iface", orders.type());
        assertEquals("order", assertInstanceOf(Meth.class, orders.get("get")).request().name());
    }

    @Test
    void aNameInsideItIsJudged() {
        String message = OneLevelProbe.onlyMessage(resolve("  orders => !iface { get => { request: no_such } }"));
        assertTrue(message.contains("at /get/request names 'no_such'"), message);
    }

    /** {@code ref<T>} reaches a member by key, {@code orders.get}: a namespace is entered by its keys. */
    @Test
    void aReferenceReachesAMemberThroughTheField() {
        Bind bind = assertInstanceOf(Bind.class, held(ORDERS + "  b => !binds { one => { m: orders.get } }", "b", "one"));
        assertEquals("orders.get", bind.m().name());

        String missing = OneLevelProbe.onlyMessage(resolve(ORDERS + "  b => !binds { one => { m: orders.nope } }"));
        assertTrue(missing.contains("'orders' has no key 'nope' -- it holds get"), missing);
    }

    /** A record's field holding a plain map is no namespace, and the walk does not enter it. */
    @Test
    void aMapThatIsNoNamespaceIsNotEntered() {
        String message = OneLevelProbe.onlyMessage(resolve("  p => !plains { orders => { entries: { get => {"
                + " request: order } } } }\n  b => !binds { one => { m: p.orders.entries.get } }"));
        assertTrue(message.contains("a map that is no namespace, so it is not entered by name"), message);
    }

    /**
     * <b>The slot judges the name, never the host.</b> The same {@code ref<meth>} slot bound to a {@code String}
     * component receives the name as text, and a name reaching nothing is refused as under a {@code TypeRef} host
     * ({@link #aReferenceReachesAMemberThroughTheField}).
     */
    @Test
    void aReferenceBoundToAStringIsJudged() {
        BindText bind = assertInstanceOf(BindText.class,
                held(ORDERS + "  b => !bind_texts { one => { m: orders.get } }", "b", "one"));
        assertEquals("orders.get", bind.m());

        String missing = OneLevelProbe.onlyMessage(resolve(ORDERS + "  b => !bind_texts { one => { m: nobody.get } }"));
        assertTrue(missing.contains("'nobody' is no entry of this schema or its imports"), missing);
    }
}
