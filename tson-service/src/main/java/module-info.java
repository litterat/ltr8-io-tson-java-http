/**
 * A service's description of itself: {@code service.tn}, the records it reads to, the reader that checks a
 * description against what resolves, and the bind context that maps schema types to classes with startup semantics.
 * The root package holds that bind context, {@code TsonBindings}; {@code protocol} the transport-neutral exchanges a
 * protocol declares; {@code http} the {@code !service.http} projection, and with it the schema and the reader, since
 * {@code service.tn} is one schema holding both sides. {@code protocol} names nothing in {@code http}, and the root
 * names neither. {@code tson-http} serves what this module describes.
 */
module io.ltr8.tson.service {
    exports io.ltr8.tson.service;
    exports io.ltr8.tson.service.protocol;
    exports io.ltr8.tson.service.http;

    // transitive: Tson is in this module's own signatures (TsonServiceSchema.describedBy).
    requires transitive io.ltr8.tson;
    // transitive: TsonBindings hands back a DataBindContext.
    requires transitive io.ltr8.bind;
    // transitive: the bound records carry @Typename and @Field, and Http and Resource hold an AnnotatedMap in
    // their components. It resolves through io.ltr8.bind either way; naming it says a consumer needs it.
    requires transitive io.ltr8.annotation;
    // transitive: the meta records (TypeRef, ValueRef, Namespace) are in this module's signatures. It resolves
    // through io.ltr8.tson either way; naming it says a consumer needs it.
    requires transitive io.ltr8.tson.schema;
    // CanonicalIdentity and the atom registrations TsonBindings applies; in no signature here.
    requires io.ltr8.tson.base;
    // SchemaModelNameBinder, asked whether a name is the kernel's vocabulary; in no signature here.
    requires io.ltr8.tson.compiler;
}
