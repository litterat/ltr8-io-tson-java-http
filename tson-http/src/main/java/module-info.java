/**
 * TSON over HTTP, independent of any particular server: the media type, the codec, the status policy and the
 * error body. Each server adapter is a translation layer over this and holds no TSON knowledge of its own.
 */
module io.ltr8.tson.http {
    exports io.ltr8.tson.http;
    exports io.ltr8.tson.http.api;

    requires transitive io.ltr8.tson;
    // transitive: Builder.httpClient(HttpClient) is public API, so a consumer naming it needs this module.
    requires transitive java.net.http;
}
