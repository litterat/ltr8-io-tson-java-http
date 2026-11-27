/**
 * TSON over HTTP, independent of any particular server: the media type, the codec, the status policy, the error body,
 * the deployment descriptor, and {@code http.tn}, the schema that projects a protocol {@code io.ltr8.tson.service}
 * declares onto HTTP, with the reader that checks a description written in it. Each server adapter is a translation
 * layer over this and holds no TSON knowledge of its own.
 *
 * <p>The root package is the API, the holder of each schema among it. The classes that mirror a {@code .tn} type, each
 * carrying {@code @Typename}, live under {@code schema} and nothing else does: the mirrors of {@code http.tn}, whose
 * root map is the module's own, in {@code schema} itself, and those of each other schema the module owns in {@code
 * schema.<root map>}, {@code problem.tn}'s in {@code schema.problem} and {@code deployment.tn}'s in {@code
 * schema.deployment}. A {@code schema} package names nothing of the root.
 */
module io.ltr8.tson.http {
    exports io.ltr8.tson.http;
    // Unqualified: bind's public lookup constructs a bound record only in a package exported to every module.
    exports io.ltr8.tson.http.schema;
    exports io.ltr8.tson.http.schema.problem;
    exports io.ltr8.tson.http.schema.deployment;

    // transitive: TsonHttpCodec hands back a Tson's readers and writers, and java.net.http reaches a
    // consumer through it -- nothing here names that module directly.
    requires transitive io.ltr8.tson;
    // transitive: the shared vocabulary is in this module's own signatures -- ProcessorConfig on
    // TsonDeployment.applyTo, SchemaAccess and SchemaSource on TsonSchemaVersions, Diagnostic throughout
    // TsonHttpException. It resolves through io.ltr8.tson either way; naming it says a consumer needs it.
    requires transitive io.ltr8.tson.base;
    // transitive: a JSON body read as a tree comes back as a JsonValue, which is in this module's signatures.
    requires transitive io.ltr8.tson.json;
    // transitive: every schema package's records carry @Typename, and HttpProjection and Resource hold an AnnotatedMap
    // in their components. It resolves through io.ltr8.tson.service either way; naming it says a consumer needs it.
    requires transitive io.ltr8.annotation;
    // transitive: http.tn imports service.tn, so its records name service.tn's (Protocol, Exchange), the description
    // names ExchangeName, and TsonHttpSchema's bindings include TsonServiceSchema's.
    requires transitive io.ltr8.tson.service;
}
