package io.ltr8.tson.service.http;

import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.http.TsonHttpCodec;
import io.ltr8.tson.http.probe.extern.OrderV1;
import io.ltr8.tson.http.probe.extern.OrderV2;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.service.TsonBindings;
import io.ltr8.tson.service.http.RequestPlacement.Location;
import io.ltr8.tson.service.internal.Landed;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.ltr8.tson.service.http.ExternExamples.API;
import static io.ltr8.tson.service.http.ExternExamples.DESCRIPTION;
import static io.ltr8.tson.service.http.ExternExamples.ORDER_1;
import static io.ltr8.tson.service.http.ExternExamples.ORDER_2;
import static io.ltr8.tson.service.http.ExternExamples.TAGGED_KEYS;
import static io.ltr8.tson.service.http.ExternExamples.description;
import static io.ltr8.tson.service.http.ExternExamples.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonServiceDescription} over a description naming its payload types by extern ([TSON-SCHEMA] §5.13), {@link
 * ExternExamples}: every check that looks inside a payload type reads the foreign entry the extern lands on, with the
 * names that entry's declaration writes looked up in the foreign schema, and a payload type is named as the binder is
 * asked it, the declaring schema's identity and the entry's own name.
 */
class TsonServiceDescriptionExternTest {

    /** The v1 exchange taking {@code order_key_v1}, an extern of {@code keys-1.tn}'s {@code order_key}. */
    static final String KEYED = DESCRIPTION.replace("request: order_ref  response: order_v1",
            "request: order_key_v1  response: order_v1");

    static TsonServiceDescription read(String description) {
        return TsonServiceSchema.describedBy(described(description), API);
    }

    // ── what the reader looks inside ──

    /**
     * A business error named through an extern fixes the status its target pins: {@code not_found_v1} lands on
     * {@code errors-1.tn}'s {@code not_found}, which composes {@code problem.details} with {@code status: = 404}.
     */
    @Test
    void anErrorThroughAnExternFixesTheStatusItsTargetPins() {
        Route route = read(description("  errors: [not_found_v1]", "")).routes().getFirst();

        assertEquals("not_found_v1", route.errorFor(404).orElseThrow().name());
    }

    /**
     * A response header's variable names a field of the record the response lands on, judged in the foreign schema:
     * {@code sku} of {@code order-2.tn}'s {@code order} is a scalar, so the template expands from it.
     */
    @Test
    void aResponseHeaderExpandsOverTheForeignRecordsScalarField() {
        Route route = read(description("", "  response_headers: { ETag => \"{sku}\" }")).routes().get(1);

        assertEquals(Map.of("etag", "A-1"), route.expandResponseHeaders(field -> field.equals("sku") ? "A-1" : null));
    }

    /**
     * And a field the foreign schema types by a record is no scalar, though the description's own namespace holds
     * no {@code span}: the field's type is looked up where its declaration wrote it.
     */
    @Test
    void aResponseHeaderOverAForeignRecordFieldIsRefused() {
        String spanned = description("", "  response_headers: { ETag => \"{id}\" }")
                .replace("type: order_key }", "type: order_span }")
                .replace("response: order_v2 }", "response: order_key_v1 }");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> read(spanned));

        assertEquals("'orders_http GET /v2/orders/{id}' declares the response header 'etag: {id}', but 'id' of "
                + "'order_key_v1' is a span, which is not a scalar, and a header's variable expands from one scalar",
                refused.getMessage());
    }

    /**
     * A request named through an extern places the foreign record's fields: {@code id} from the path, {@code region}
     * from the query, in the record's order.
     */
    @Test
    void anExternTypedRequestPlacesTheForeignRecordsFields() {
        String placed = KEYED.replace("exchange: orders.exchanges.get_order_v1 }",
                "exchange: orders.exchanges.get_order_v1  query: [region] }");
        Route route = read(placed).routes().getFirst();

        assertEquals(Map.of("id", Location.PATH, "region", Location.QUERY), route.placement().fields());
        assertEquals(List.of("id", "region"), List.copyOf(route.placement().fields().keySet()));
    }

    /** And refuses a foreign field typed by a foreign record in the path, the record judged in its own schema. */
    @Test
    void anExternTypedRequestRefusesAForeignRecordInThePath() {
        String spanned = KEYED.replace("type: order_key }", "type: order_span }");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> read(spanned));

        assertEquals("'orders_http GET /v1/orders/{id}' places 'id' in the PATH, but it is a span, which is not a "
                + "scalar", refused.getMessage());
    }

    /**
     * <b>A type expression is resolved in the schema declaring the entry holding it.</b> {@code labelled}'s {@code
     * labels} is {@code label_list}, an extern of {@code tags-1.tn}'s {@code labels}, an array of that schema's record
     * {@code label}: an array of records has no query spelling, though {@code keys-1.tn} declares no {@code label}, and
     * though it declares a scalar {@code label} of its own.
     */
    @Test
    void aForeignArrayOfRecordsFromAThirdSchemaIsRefusedInTheQuery() {
        String expected = "'orders_http GET /v1/orders/{id}' places 'labels' in the QUERY, but it is an array of label,"
                + " and a repeated query value carries a scalar";
        for (String own : List.of("", "  label      => text\n")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> keyed("labelled", TAGGED_KEYS + own, "  query: [labels]"), own);

            assertEquals(expected, refused.getMessage(), own);
        }
    }

    /**
     * And an array of scalars from the third schema is admitted, though {@code keys-1.tn} declares a record of the
     * element's name: {@code tags-1.tn}'s {@code codes} is {@code [code]}, its own {@code code} a {@code text}.
     */
    @Test
    void aForeignArrayOfScalarsFromAThirdSchemaIsAdmittedInTheQuery() {
        Route route = keyed("coded", TAGGED_KEYS, "  query: [codes]").routes().getFirst();

        assertEquals(Map.of("id", Location.PATH, "codes", Location.QUERY), route.placement().fields());
    }

    /**
     * An extern landing on an entry its schema holds by import lands in the importer: {@code shelf-1.tn} imports
     * {@code tags-1.tn}, so the landing's namespace is {@code shelf-1.tn}'s and the entry's declaring schema {@code
     * tags-1.tn}. An import merges the declarer's whole namespace and admits no redefinition, so the importer resolves
     * every name the entry writes as the declarer does, and the array of records is refused as before.
     */
    @Test
    void aForeignArrayAnImportHoldsIsJudgedAsItsDeclarerWroteIt() {
        String shelf = ExternExamples.BASE + "shelf-1.tn";
        String stocked = "  shelved    => !meta.extern { schema: \"" + shelf + "\"  type: labels }\n"
                + "  stocked    => { id: text  labels: shelved }\n";
        Map<String, String> library = ExternExamples.library(keyedDescription("stocked", "  query: [labels]"));
        library.put(shelf, "!!id:\"" + shelf + "\"\n!!import:\"" + ExternExamples.CORE + "\"\n!!import:\""
                + ExternExamples.TAGS + "\"\n!schema {\n  shelf => { name: text }\n}");
        library.put(ExternExamples.KEYS, ExternExamples.keys(stocked + "  label      => text\n"));
        Tson tson = OrdersExamples.tson(library);
        tson.resolve(library.get(API));

        Landed landed = Landed.of(tson.loader().resolveLinked(ExternExamples.KEYS), "shelved");
        assertEquals(CanonicalIdentity.canonicalize(shelf), landed.in().identity());
        assertEquals(CanonicalIdentity.canonicalize(ExternExamples.TAGS), landed.identity());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TsonServiceSchema.describedBy(tson, API));
        assertEquals("'orders_http GET /v1/orders/{id}' places 'labels' in the QUERY, but it is an array of label, and "
                + "a repeated query value carries a scalar", refused.getMessage());
    }

    /**
     * A response header's variable is judged through the landing of the field's type: {@code badged}'s {@code code}
     * is {@code code_ref}, an extern of {@code tags-1.tn}'s {@code code}, a {@code text}, so it expands, though {@code
     * keys-1.tn}'s own {@code code} is a record; its {@code label} lands on {@code tags-1.tn}'s record and is refused.
     * Any array is refused there, its element type unread, so {@code coded}'s {@code codes} is refused as an array.
     */
    @Test
    void aResponseHeaderJudgesAFieldTypedByAnExternWhereItLands() {
        Route route = headed("badged", "{code}").routes().get(1);
        assertEquals(Map.of("etag", "C-1"), route.expandResponseHeaders(field -> field.equals("code") ? "C-1" : null));

        String declared = "'orders_http GET /v2/orders/{id}' declares the response header ";
        assertEquals(declared + "'etag: {label}', but 'label' of 'order_key_v1' is a label_ref, which is not a scalar,"
                + " and a header's variable expands from one scalar", assertThrows(IllegalArgumentException.class,
                () -> headed("badged", "{label}")).getMessage());
        assertEquals(declared + "'etag: {codes}', but 'codes' of 'order_key_v1' is an array, and a header's variable "
                + "expands from one scalar", assertThrows(IllegalArgumentException.class,
                () -> headed("coded", "{codes}")).getMessage());
    }

    /**
     * A plain alias inside the foreign schema is judged as what it reaches there: {@code aliased_span}'s {@code id} is
     * {@code span_alias}, which {@code keys-1.tn} declares as an alias of its record {@code span}, so it is no scalar
     * in the path, though the description declares neither name.
     */
    @Test
    void aForeignFieldTypedByAForeignPlainAliasOfARecordIsRefusedInThePath() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> keyed("aliased_span",
                "  span_alias   => span\n  aliased_span => { id: span_alias }\n", ""));

        assertEquals("'orders_http GET /v1/orders/{id}' places 'id' in the PATH, but it is a span_alias, which is not a "
                + "scalar", refused.getMessage());
    }

    /**
     * {@link #KEYED} with {@code order_key_v1} an extern of {@code keys-1.tn}'s {@code request}, and {@code placement}
     * spliced into the v1 endpoint.
     */
    static String keyedDescription(String request, String placement) {
        return KEYED.replace("type: order_key }", "type: " + request + " }").replace(
                "exchange: orders.exchanges.get_order_v1 }", "exchange: orders.exchanges.get_order_v1" + placement + " }");
    }

    /** {@link #keyedDescription}, read with {@code extra} declared in {@code keys-1.tn}. */
    static TsonServiceDescription keyed(String request, String extra, String placement) {
        return readWithKeys(keyedDescription(request, placement), extra);
    }

    /**
     * The description whose v2 endpoint answers {@code order_key_v1}, an extern of {@code keys-1.tn}'s {@code
     * response}, with an {@code ETag} of {@code template}, read with {@link ExternExamples#TAGGED_KEYS} declared there.
     */
    static TsonServiceDescription headed(String response, String template) {
        return readWithKeys(description("", "  response_headers: { ETag => \"" + template + "\" }")
                .replace("type: order_key }", "type: " + response + " }")
                .replace("response: order_v2 }", "response: order_key_v1 }"), TAGGED_KEYS);
    }

    static TsonServiceDescription readWithKeys(String description, String extra) {
        Map<String, String> library = ExternExamples.library(description);
        library.put(ExternExamples.KEYS, ExternExamples.keys(extra));
        Tson tson = OrdersExamples.tson(library);
        tson.resolve(description);
        return TsonServiceSchema.describedBy(tson, API);
    }

    // ── what the binder is asked ──

    /**
     * <b>Two versions in one description bind to two classes through one context.</b> A reader asks the binder for
     * the entry an extern lands on, by its declaring schema's identity and its own name, so {@code
     * TsonBindings.bySchema} ({@link DataNameBinder#byNamespace}) binds {@code order-1.tn}'s {@code order} to {@link
     * OrderV1} and {@code order-2.tn}'s to {@link OrderV2}. {@code payloadTypes()} names each so, {@code
     * boundClasses()} warms both, and each route's response writes and reads under the description by the name the
     * route gives it, in TSON and in JSON.
     */
    @Test
    void twoVersionsBindToTwoClassesThroughOneContext() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(ExternExamples.library(DESCRIPTION))))
                .withDataBindContext(TsonBindings.bySchema(Map.of(TsonServiceSchema.ID, TsonServiceSchema.bindings(),
                        ORDER_1, Map.of("order", OrderV1.class), ORDER_2, Map.of("order", OrderV2.class)))));
        tson.resolve(DESCRIPTION);
        TsonServiceDescription described = TsonServiceSchema.describedBy(tson, API);

        List<Route> routes = described.routes();
        assertEquals(new PayloadType(ORDER_1, "order"), described.payloadType(routes.get(0).response().orElseThrow()));
        assertEquals(new PayloadType(ORDER_2, "order"), described.payloadType(routes.get(1).response().orElseThrow()));
        assertEquals(CanonicalIdentity.canonicalize(ORDER_2),
                described.payloadType(routes.get(1).response().orElseThrow()).identity());
        assertEquals(List.of(OrderV1.class, OrderV2.class), described.boundClasses());

        TsonHttpCodec codec = new TsonHttpCodec(tson).acceptingJson();
        codec.prepareToRead(ORDER_1, ORDER_2);
        codec.prepareToWrite(described.boundClasses().toArray(Class<?>[]::new));
        Map<Object, String> values = Map.of(new OrderV1("A", 1), "{\"sku\": \"A\", \"quantity\": 1}",
                new OrderV2("A", 1, "EUR"), "{\"sku\": \"A\", \"quantity\": 1, \"currency\": \"EUR\"}");
        List<Object> sent = List.of(new OrderV1("A", 1), new OrderV2("A", 1, "EUR"));
        for (int i = 0; i < routes.size(); i++) {
            String root = routes.get(i).response().orElseThrow().name();
            Object value = sent.get(i);
            byte[] written = codec.write(value, API, root);
            assertEquals(value, codec.readObject(new ByteArrayInputStream(written), "application/tson",
                    value.getClass()), () -> new String(written, StandardCharsets.UTF_8));
            PayloadType asked = described.payloadType(routes.get(i).response().orElseThrow());
            assertEquals(value, codec.readObjectAs(new ByteArrayInputStream(values.get(value)
                    .getBytes(StandardCharsets.UTF_8)), "application/json", asked.schemaId(), asked.name(),
                    value.getClass()));
        }
    }

    /**
     * <b>A class bound by an extern's local name binds nothing a read asks for</b>, so the description refuses it at
     * startup where a route names the extern, naming the entry that would serve; otherwise every read of the type is
     * a 500 ({@code ExternProbeTest.aNameOnlyBinderIsAskedTheTargetsNameNotTheExterns}). Bound by the entry's own name,
     * it loads and warms.
     */
    @Test
    void aBindingByAnExternsLocalNameIsRefusedAtStartup() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                TsonServiceSchema.describedBy(boundByName(ExternExamples.library(DESCRIPTION),
                        Map.of("order_v1", OrderV1.class)), API));

        assertEquals("'GET /v1/orders/{id}' names 'order_v1', which '" + API + "' declares as an extern of 'order' in '"
                + ORDER_1 + "', and the bind context binds 'order_v1' to " + OrderV1.class.getName() + ", which no "
                + "read asks for: no schema of the closure declares a 'order_v1' of its own -- a reader asks the binder "
                + "for the entry an extern lands on, by the schema declaring it and its name there; bind 'order' "
                + "instead, through TsonBindings.bySchema keyed by '" + ORDER_1 + "' where two schemas each declare "
                + "'order'", refused.getMessage());

        Map<String, Class<?>> byEntry = Map.of("order", OrderV1.class);
        TsonServiceDescription described = TsonServiceSchema.describedBy(boundByName(ExternExamples.library(DESCRIPTION),
                byEntry), API);
        assertEquals(List.of(OrderV1.class), described.boundClasses(byEntry));
        assertEquals(List.of(OrderV1.class), described.boundClasses());
    }

    /**
     * The refusal reaches only what it can be sure of. An extern no route names is not a payload type, so a binding
     * by its name is not judged ({@code order_key_v1} here). And a binding by a local name that a schema of the
     * closure declares as its own entry may be for that entry, so it loads: here {@code order-1.tn} declares an
     * {@code order_v1} of its own beside the {@code order} the description's extern names.
     */
    @Test
    void aBindingByAnExternsLocalNameIsJudgedOnlyWhereItCanServeNothing() {
        Map<String, Class<?>> unnamed = Map.of("order_key_v1", OrderV1.class);
        TsonServiceSchema.describedBy(boundByName(ExternExamples.library(DESCRIPTION), unnamed), API);

        Map<String, String> owning = ExternExamples.library(DESCRIPTION);
        owning.put(ORDER_1, ExternExamples.ORDER_1_SOURCE.replace("  order => {",
                "  order_v1 => { sku: text  quantity: int32 }\n  order => {"));
        TsonServiceSchema.describedBy(boundByName(owning, Map.of("order_v1", OrderV1.class)), API);
    }

    /**
     * A dependent field in a request named through an extern is judged through the landing: the sibling's pin and
     * every type it reaches are the foreign schema's, asked of the binder by that schema's identity. {@code
     * pinned_key} pins {@code kind} to {@code span}, which the description does not declare: bound with {@code span}
     * keyed by {@code keys-1.tn} it loads, and without it the description is refused naming {@code span}. A free
     * {@code meta.type_ref} sibling, {@code free_key}, is refused with the rule's own message.
     */
    @Test
    void aDependentFieldInAnExternTypedRequestIsJudgedThroughTheLanding() {
        Map<String, Class<?>> keys = Map.of("pinned_key", Keyed.class, "free_key", Keyed.class, "span", Span.class);
        dependent("pinned_key", keys);

        String unbound = assertThrows(IllegalArgumentException.class,
                () -> dependent("pinned_key", Map.of("pinned_key", Keyed.class))).getMessage();
        assertTrue(unbound.startsWith("'POST /v1/notes/{id}' takes 'order_key_v1', whose dependent field 'payload: "
                + "kind!' is read against the type a sender names in 'kind', and it may name types this server cannot "
                + "bind: 'span' (no class binds it) --"), unbound);

        String free = assertThrows(IllegalArgumentException.class, () -> dependent("free_key", keys)).getMessage();
        assertTrue(free.startsWith("'POST /v1/notes/{id}' takes 'order_key_v1', whose dependent field 'payload: "
                + "kind!' is read against the type a sender names in 'kind', and it may name types this server cannot "
                + "bind:"), free);
        assertTrue(free.contains("(the kernel's vocabulary)") && free.contains("pin 'kind'"), free);
    }

    /** A bound request with a dependent field: {@code kind} receives the name, {@code payload} what it reads to. */
    public record Keyed(String id, TypeRef kind, Object payload) {
    }

    /** {@code keys-1.tn}'s {@code span}. */
    public record Span(String from, String to) {
    }

    /**
     * The description with a {@code POST /v1/notes/{id}} exchange taking {@code order_key_v1} as an extern of {@code
     * keys-1.tn}'s {@code request}, read under {@code keys} bound by that schema's identity.
     */
    static TsonServiceDescription dependent(String request, Map<String, Class<?>> keys) {
        String noted = DESCRIPTION.replace("type: order_key }", "type: " + request + " }")
                .replace("@service.safe get_order_v2 => { request: order_ref  response: order_v2 }",
                        "@service.safe get_order_v2 => { request: order_ref  response: order_v2 }\n"
                                + "      put_note => { request: order_key_v1  response: order_v1 }")
                .replace("\"/v2/orders/{id}\" =>", "\"/v1/notes/{id}\" => { POST => !service.binding { exchange: "
                        + "orders.exchanges.put_note } }\n      \"/v2/orders/{id}\" =>");
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(ExternExamples.library(noted))))
                .withDataBindContext(TsonBindings.bySchema(Map.of(TsonServiceSchema.ID, TsonServiceSchema.bindings(),
                        ExternExamples.KEYS, keys))));
        tson.resolve(noted);
        return TsonServiceSchema.describedBy(tson, API);
    }

    /**
     * A {@code Tson} over {@code library} binding {@code application} by name beside the layer's vocabulary, the
     * description resolved.
     */
    static Tson boundByName(Map<String, String> library, Map<String, Class<?>> application) {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(library)))
                .withDataBindContext(TsonBindings.of(TsonServiceSchema.bindingsWith(application))));
        tson.resolve(library.get(API));
        return tson;
    }

    /**
     * {@code TsonBindings.bySchema} answers a type by the map of the schema declaring it: a name that map lacks is
     * its missing line, and a schema no map is keyed by is refused listing those that are, never answered by another
     * schema's map.
     */
    @Test
    void bySchemaAnswersEachTypeByItsDeclaringSchemasMap() throws DataBindException {
        DataNameBinder binder = TsonBindings.bySchema(Map.of(ORDER_1, Map.of("order", OrderV1.class))).nameBinder();
        String v1 = CanonicalIdentity.canonicalize(ORDER_1);
        String v2 = CanonicalIdentity.canonicalize(ORDER_2);

        assertEquals(OrderV1.class, binder.resolve(v1, "order"));
        assertEquals("'line' is not bound: the bindings map [order]",
                assertThrows(DataBindException.class, () -> binder.resolve(v1, "line")).getMessage());
        assertEquals("'order' of '" + v2 + "' is not bound: the bindings are keyed by schema, and hold [" + v1 + "]",
                assertThrows(DataBindException.class, () -> binder.resolve(v2, "order")).getMessage());
    }
}
