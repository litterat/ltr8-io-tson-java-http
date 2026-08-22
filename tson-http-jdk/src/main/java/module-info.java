/**
 * The JDK adapter: {@code tson-http}'s codec driven from {@code com.sun.net.httpserver}, with no external
 * dependency of any kind. Both the "no framework needed" demonstration and the reference the Javalin and
 * Helidon adapters are read against when their behaviour looks odd.
 */
module io.ltr8.tson.http.jdk {
    exports io.ltr8.tson.http.jdk;

    requires transitive io.ltr8.tson.http;
    // transitive: TsonExchange hands back the underlying HttpExchange as an escape hatch.
    requires transitive jdk.httpserver;
}
