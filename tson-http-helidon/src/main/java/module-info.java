/**
 * The Helidon adapter: {@code tson-http}'s codec driven from a Helidon SE route, with the same error boundary
 * as the JDK and Javalin adapters' so the three can be read against each other -- plus {@code TsonMediaSupport},
 * which registers TSON with Helidon's own entity machinery so a plain handler reads and writes it natively.
 */
module io.ltr8.tson.http.helidon {
    exports io.ltr8.tson.http.helidon;

    requires transitive io.ltr8.tson.http;
    // transitive: TsonContext hands back Helidon's own request and response, and a route is registered
    // against Helidon's Handler.
    requires transitive io.helidon.webserver;
    requires transitive io.helidon.http.media;
    requires io.helidon.http;
    requires io.helidon.common;
}
