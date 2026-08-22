/**
 * The Javalin adapter: {@code tson-http}'s codec driven from a {@link io.javalin.Javalin} route, with the same
 * error boundary as {@code tson-http-jdk}'s so the two can be read against each other.
 */
module io.ltr8.tson.http.javalin {
    exports io.ltr8.tson.http.javalin;

    requires transitive io.ltr8.tson.http;
    // transitive: TsonContext hands back the underlying Javalin Context as an escape hatch, and a route is
    // registered against Javalin's own Handler.
    requires transitive io.javalin;
}
