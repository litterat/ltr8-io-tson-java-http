/**
 * TSON over HTTP, independent of any particular server: the media type, the codec, the status policy and the
 * error body. Each server adapter is a translation layer over this and holds no TSON knowledge of its own.
 */
module io.ltr8.tson.http {
    exports io.ltr8.tson.http;

    requires transitive io.ltr8.tson;
}
