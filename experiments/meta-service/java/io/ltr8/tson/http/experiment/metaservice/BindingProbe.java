package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real question behind the sketch: a method declared once on an interface, and a <em>separate</em> entry --
 * possibly in another document -- binding it to HTTP. That needs one entry to refer to another, and a
 * {@code kind: DATA} entry cannot be referred to. Two ways of modelling it today, both measured; the third is a
 * spec change (tson-java's {@code SPEC-FEEDBACK.md}, "a namespace should be a value").
 *
 * <p><b>A.</b> The binding names the method by {@code type_name}. Resolves, reads back, and the resolver does
 * not check the name -- a typo loads clean and only the reader sees {@code null}. The check is the description
 * reader's, at startup.
 *
 * <p><b>B.</b> A method is a <em>type</em> -- the type of its call record -- under plain {@code meta.tn}, no meta
 * layer at all. {@code create_order => place_order & http & { verb: = POST … }} composes, the operation IS-A
 * its method, the binding reads back as fixed fields, and a plan step is a value of the method type. The cost
 * is visible in the read-back value: schema facts declared as fields are injected into every instance.
 */
class BindingProbe {

    static final String IFACE_ID = "https://schemas.example.com/2026/34/app/orders-1.tn";
    static final String API_ID = "https://schemas.example.com/2026/34/app/orders-api-1.tn";
    static final String LIB_ID = "https://tson.io/2026/34/ltr8/http/service-1.tn";

    // ── A: the sketch's own `method` and `binding` -- the latter naming the former by identifier ───

    static final String IFACE_A = """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/34/m/core.tn"
        {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
          place_order  => !method { request: order  response: order }
          cancel_order => !method { request: order_ref  idempotent: true }
        }""".formatted(IFACE_ID, Experiment.META_ID);

    static Tson tsonA(String api) {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(Experiment.META_ID, Experiment.metaServiceSource());
        lib.put(IFACE_ID, IFACE_A);
        lib.put(API_ID, api);
        return Experiment.bindVocabulary(Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib))).build();
    }

    static String apiA(String entries) {
        return """
        !!id:"%s"
        !!meta:"%s"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        {
        %s
        }""".formatted(API_ID, Experiment.META_ID, IFACE_ID, entries);
    }

    @Test
    void aBindingNamingAMethodByIdentifierResolvesAndIsCheckedInJava() {
        String api = apiA("  create_order => !binding { method: place_order  verb: POST  path: \"/orders\"  status: 201 }");
        Tson tson = tsonA(api);
        List<Diagnostic> problems = tson.validateSchema(api);
        assertEquals(List.of(), problems, () -> "" + problems);

        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        Binding binding = assertInstanceOf(Binding.class, entries.get("create_order").body());
        assertEquals("place_order", binding.method());
        // The check the resolver does not do: the name is a method in the merged namespace.
        assertInstanceOf(Method.class, entries.get(binding.method()).body());
    }

    /** The cost of A, measured: a {@code type_name} slot is data, and a typo in it is nobody's error but the reader's. */
    @Test
    void aBindingNamingNothingIsNotCaughtByTheResolver() {
        String api = apiA("  create_order => !binding { method: plaec_order  verb: POST  path: \"/orders\" }");
        Tson tson = tsonA(api);

        assertEquals(List.of(), tson.validateSchema(api));
        var entries = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries();
        assertNull(entries.get("plaec_order"));
    }

    /** Why A cannot be written as composition: a {@code ~data} instance has no vocabulary body to compose with. */
    @Test
    void composingWithADataMethodIsRefused() {
        String api = apiA("  create_order => place_order & { verb: text }");
        List<Diagnostic> problems = tsonA(api).validateSchema(api);

        assertEquals(1, problems.size(), () -> "" + problems);
        assertTrue(problems.getFirst().message().contains("has no fields to contribute"),
                problems.getFirst().message());
    }

    // ── B: a method is a type, and the operation IS-A the method ─────────────────────────────────

    static final String LIB_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        {
          method      => <Req, Resp> { request: Req  response: Resp?  safe: boolean ~ false  idempotent: boolean ~ false }
          http_verb   => !enum [GET POST PUT PATCH DELETE HEAD OPTIONS]
          status_code => !integer ^ { min: 100  max: 599 }
          http        => { verb: http_verb  path: text  status: status_code ~ 200 }
        }""".formatted(LIB_ID);

    /**
     * {@code place_order => method<order, order>} alone would be an alias to an instantiation, which has no
     * vocabulary body to compose with (§5.8); the trailing {@code & { … }} is what gives it one.
     */
    static final String IFACE_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        {
          order     => { sku: text  quantity: int32 }
          order_ref => { id: text }
          place_order  => method<order, order> & { errors: [text]? }
          cancel_order => method<order_ref, void> & { idempotent: = true }
        }""".formatted(IFACE_ID, LIB_ID);

    static final String API_B = """
        !!id:"%s"
        !!meta:"https://tson.io/2026/34/m/meta.tn"
        !!import:"https://tson.io/2026/34/m/core.tn"
        !!import:"%s"
        !!import:"%s"
        {
          create_order => place_order & http & { verb: = POST  path: = "/orders"  status: = 201 }
        }""".formatted(API_ID, LIB_ID, IFACE_ID);

    @Test
    void aMethodAsATypeCanBeComposedIntoAnOperation() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(LIB_ID, LIB_B);
        lib.put(IFACE_ID, IFACE_B);
        lib.put(API_ID, API_B);
        Tson tson = Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib)).build();
        List<Diagnostic> problems = tson.validateSchema(API_B);
        assertEquals(List.of(), problems, () -> "" + problems);

        var def = tson.schemaRegistry().get(API_ID).orElseThrow().schema().entries().get("create_order");
        assertTrue(def.supertypes().contains("place_order"), () -> "" + def.supertypes());
        assertTrue(def.supertypes().contains("http"), () -> "" + def.supertypes());

        // The binding is read off the schema: fixed fields, compiler-checked.
        Map<String, RecordField> fields = new LinkedHashMap<>();
        ((RecordBody) def.body()).fields().forEach(f -> fields.put(f.name(), f));
        assertEquals(FieldState.REQUIRED_FIXED, fields.get("verb").state());
        assertEquals("POST", fields.get("verb").value().orElseThrow().text());
        assertEquals("/orders", fields.get("path").value().orElseThrow().text());
        assertEquals("201", fields.get("status").value().orElseThrow().text());
        assertEquals("order", fields.get("request").type().name());

        // The payoff: a plan step is a value of the method type, and an HTTP call record is a valid one.
        String step = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 } }""".formatted(API_ID);
        var value = tson.treeReader().read(step);
        assertEquals("POST", value.get("verb").asString().orElseThrow());   // the cost: injected into every value

        String bad = """
            !!schema:"%s"
            !create_order { request: { sku: A-100  quantity: 2 }  verb: GET }""".formatted(API_ID);
        String refused = assertThrows(RuntimeException.class, () -> tson.treeReader().read(bad)).getMessage();
        assertTrue(refused.contains("'verb' is fixed on 'create_order'"), refused);
    }
}
