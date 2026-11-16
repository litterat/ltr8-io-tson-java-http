package io.ltr8.tson.http.api;

import io.ltr8.tson.Tson;
import io.ltr8.tson.http.TsonProblemSchema;

import java.util.Map;

/**
 * A service description naming foreign types by extern ([TSON-SCHEMA] §5.13), and the schemas they land in: two
 * versions of {@code order}, a business error, and a request record. Shared by {@code TsonApiDescriptionExternTest},
 * which holds the reader to it, and {@code ExternProbeTest}, which measures the library under it.
 *
 * <ul>
 * <li>{@code order-1.tn} and {@code order-2.tn} each declare {@code order}, the second adding {@code currency}: two
 * versions an import of both could not hold in one namespace.</li>
 * <li>{@code errors-1.tn} declares {@code not_found}, composing {@code problem.details} and fixing status 404.</li>
 * <li>{@code keys-1.tn} declares the request records {@code order_key}, all scalars; {@code order_span}, whose
 * {@code id} is the record {@code span}; and {@code pinned_key} and {@code free_key}, each holding a dependent field
 * {@code payload: kind!}, its sibling pinned to {@code span} in the first and a free {@code meta.type_ref} in the
 * second.</li>
 * <li>{@code api-1.tn} names each as an extern -- {@code order_v1}, {@code order_v2}, {@code not_found_v1}, {@code
 * order_key_v1} -- beside its own {@code order_ref}, with one exchange per version and a projection binding both.</li>
 * </ul>
 */
public final class ExternExamples {

    public static final String BASE = "https://schemas.example.com/2026/40/extern/";
    public static final String CORE = "https://tson.io/2026/40/m/core.tn";
    public static final String ORDER_1 = BASE + "order-1.tn";
    public static final String ORDER_2 = BASE + "order-2.tn";
    public static final String ERRORS = BASE + "errors-1.tn";
    public static final String KEYS = BASE + "keys-1.tn";
    public static final String API = BASE + "api-1.tn";

    public static final String ORDER_1_SOURCE = order(ORDER_1, "");
    public static final String ORDER_2_SOURCE = order(ORDER_2, "  currency: text");

    public static final String ERRORS_SOURCE = "!!id:\"" + ERRORS + "\"\n!!import:\"" + CORE + "\"\n!!import:\""
            + TsonProblemSchema.ID + "\"\n!schema {\n  not_found => problem.details & { status: = 404  order_id: text }\n}";

    public static final String KEYS_SOURCE = "!!id:\"" + KEYS + "\"\n!!import:\"" + CORE + "\"\n" + """
            !schema {
              span       => { from: text  to: text }
              order_key  => { id: text  region?: text }
              order_span => { id: span }
              pinned_key => { id: text  kind?: meta.type_ref = span  payload: kind! }
              free_key   => { id: text  kind: meta.type_ref  payload: kind! }
            }""";

    /** The description with nothing spliced in. */
    public static final String DESCRIPTION = description("", "");

    private ExternExamples() {
    }

    /** {@code order} at {@code id}, with {@code extra} fields after {@code sku} and {@code quantity}. */
    static String order(String id, String extra) {
        return "!!id:\"" + id + "\"\n!!import:\"" + CORE + "\"\n!schema {\n  order => { sku: text  quantity: int32"
                + extra + " }\n}";
    }

    /**
     * The description: both versions, a business error and a request record as externs, one exchange per version,
     * and a projection binding both. {@code errors} is spliced into the v1 exchange and {@code headers} into the v2
     * endpoint.
     */
    public static String description(String errors, String headers) {
        return "!!id:\"" + API + "\"\n!!import:\"" + TsonApiSchema.ID + "\"\n!!import:\"" + CORE + "\"\n" + """
                !schema {
                  order_ref    => { id: text }
                  order_v1     => !meta.extern { schema: "%s"  type: order }
                  order_v2     => !meta.extern { schema: "%s"  type: order }
                  not_found_v1 => !meta.extern { schema: "%s"  type: not_found }
                  order_key_v1 => !meta.extern { schema: "%s"  type: order_key }
                  orders => !service.protocol {
                    exchanges: {
                      @service.safe get_order_v1 => { request: order_ref  response: order_v1%s }
                      @service.safe get_order_v2 => { request: order_ref  response: order_v2 }
                    }
                  }
                  orders_http => !service.http {
                    binds: [orders]
                    resources: {
                      "/v1/orders/{id}" => { GET => !service.binding { exchange: orders.exchanges.get_order_v1 } }
                      "/v2/orders/{id}" => { GET => !service.binding { exchange: orders.exchanges.get_order_v2%s } }
                    }
                  }
                }""".formatted(ORDER_1, ORDER_2, ERRORS, KEYS, errors, headers);
    }

    /** The orders examples beside every schema here, {@code description} as {@code api-1.tn}. Mutable. */
    public static Map<String, String> library(String description) {
        Map<String, String> library = OrdersExamples.library();
        library.put(ORDER_1, ORDER_1_SOURCE);
        library.put(ORDER_2, ORDER_2_SOURCE);
        library.put(ERRORS, ERRORS_SOURCE);
        library.put(KEYS, KEYS_SOURCE);
        library.put(API, description);
        return library;
    }

    /** A {@code Tson} over the library, the layer's vocabulary bound, with {@code description} resolved. */
    public static Tson described(String description) {
        Tson tson = OrdersExamples.tson(library(description));
        tson.resolve(description);
        return tson;
    }
}
