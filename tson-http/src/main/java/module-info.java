/**
 * TSON over HTTP, independent of any particular server: the media type, the codec, the status policy and the
 * error body. Each server adapter is a translation layer over this and holds no TSON knowledge of its own.
 */
module io.ltr8.tson.http {
    exports io.ltr8.tson.http;
    exports io.ltr8.tson.http.api;

    // transitive: TsonHttpCodec hands back a Tson's readers and writers, and java.net.http reaches a
    // consumer through it -- nothing here names that module directly.
    requires transitive io.ltr8.tson;
    // transitive: the shared vocabulary is in this module's own signatures -- ProcessorConfig on
    // TsonDeployment.applyTo, SchemaAccess and SchemaSource on TsonSchemaVersions, Diagnostic throughout
    // TsonHttpException. It resolves through io.ltr8.tson either way; naming it says a consumer needs it.
    requires transitive io.ltr8.tson.base;
}
