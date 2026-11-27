/**
 * A service's description of itself, independent of any transport: {@code service.tn}, the schema that declares
 * protocols of exchanges, the records it reads to, the join that holds a protocol against a Java interface, and the
 * bind context that maps schema types to classes with startup semantics. The root package is the API: {@code
 * TsonBindings}, {@code TsonServiceSchema}, the holder of {@code service.tn}, the join {@code TsonServiceProtocol} and
 * {@code TsonExchangeName}. {@code schema} holds the records that mirror {@code service.tn}'s types and nothing else, and
 * names nothing of the root. {@code internal} is the type resolution the join shares with {@code io.ltr8.tson.http},
 * which projects a protocol onto HTTP and is the one module it is exported to.
 */
module io.ltr8.tson.service {
    exports io.ltr8.tson.service;
    // Unqualified: bind's public lookup constructs a bound record only in a package exported to every module.
    exports io.ltr8.tson.service.schema;
    // Qualified: shared with the HTTP projection's reader, not offered.
    exports io.ltr8.tson.service.internal to io.ltr8.tson.http;

    // transitive: Tson is in this module's own signatures (TsonServiceProtocol.join).
    requires transitive io.ltr8.tson;
    // transitive: TsonBindings hands back a DataBindContext, and the join a DataProtocol and DataExchange.
    requires transitive io.ltr8.bind;
    // transitive: the bound records carry @Typename. It resolves through io.ltr8.bind either way; naming it says a
    // consumer needs it.
    requires transitive io.ltr8.annotation;
    // transitive: the meta records (TypeRef, Namespace) are in this module's signatures. It resolves through
    // io.ltr8.tson either way; naming it says a consumer needs it.
    requires transitive io.ltr8.tson.schema;
    // CanonicalIdentity and the atom registrations TsonBindings applies; in no signature here.
    requires io.ltr8.tson.base;
    // AtomParsers and AtomType, asked whether an atom at an exchange's position produces the method's class, as the
    // library asks of an atom field; in no signature here.
    requires io.ltr8.tson.atom;
}
