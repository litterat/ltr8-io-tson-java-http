/**
 * A service's description of itself: {@code service.tn}, the records it reads to, the reader that checks a
 * description against what resolves, the join that holds a protocol against a Java interface, and the bind context
 * that maps schema types to classes with startup semantics. The root package holds that bind context, {@code
 * TsonBindings}; {@code protocol} the transport-neutral exchanges a protocol declares and the join; {@code http} the
 * {@code !service.http} projection, and with it the schema and the reader, since {@code service.tn} is one schema
 * holding both sides; {@code internal}, which is not exported, the type resolution {@code protocol} and {@code http}
 * share. {@code protocol} names nothing in {@code http}, {@code internal} names neither, and the root names none of
 * the three. {@code tson-http} serves what this module describes.
 */
module io.ltr8.tson.service {
    exports io.ltr8.tson.service;
    exports io.ltr8.tson.service.protocol;
    exports io.ltr8.tson.service.http;
    // io.ltr8.tson.service.internal is deliberately not exported: it is shared by protocol and http, not offered.

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
    // AtomParsers and AtomType, asked whether an atom at an exchange's position produces the method's class, as the
    // library asks of an atom field; in no signature here.
    requires io.ltr8.tson.atom;
    requires io.ltr8.tson.compiler;
}
