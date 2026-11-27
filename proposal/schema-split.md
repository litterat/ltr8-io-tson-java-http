# Splitting `service.tn` into a protocol schema and an http schema

**Status:** adopted. The layer is two schemas: `service.tn` in `tson-service`, the protocol side, which
keeps its `!!id` and its root map `service`, and `http.tn` in `tson-http`, the HTTP projection, with the `!!id`
`https://tson.io/2026/40/io/ltr8/http/http.tn` and the root map `http`. Measured by
`tson-http/src/test/java/io/ltr8/tson/http/probe/SchemaSplitProbeTest.java`, with its bound records under
`probe/schemasplit/`, which keeps its probe; what follows is the record of the measurement and of the shape adopted. It
concerns this project's own vocabulary and asks of tson-java nothing beyond one library gap, staged in `UPSTREAM.md`
(below). The probe measures a cut-down pair of schemas written in the test, with a placeholder root for the http side,
not the real files, so what it pins is the shape of the split and not each entry.

## Summary

The layer is two things that reference one way. The protocol side declares what a service says about its exchanges. The
http side declares the projection of a protocol onto HTTP and reaches the protocol side at four points; nothing on the
protocol side names anything HTTP. The Java side separates them (`tson-service` and `tson-http`) and the schemas follow.

- **`service.tn`** declares `signature`, `exchange`, `exchanges`, `protocol`, `exchange_name` and the annotations
  `summary`, `safe`, `idempotent` and `destructive`, under the root map `service`. It imports `meta.tn` only and names
  no transport. Its holder is `TsonServiceSchema` in `io.ltr8.tson.service`.
- **`http.tn`** declares the projection under the root map `http`, and imports `meta.tn`, `net.tn` and `service.tn`.
  Every http-side member keeps its member name under `http` (`http.endpoint`, `http.binding`, `http.resource`), except
  three renames: the projection record is `http.projection`, the verb enum `http.verb` and the authentication scheme
  record `http.auth`. Its holder is `TsonHttpSchema` in `io.ltr8.tson.http`, which binds and publishes
  both layers, since a client fetching `http.tn` fetches the `service.tn` it imports next.
- **The facts annotations stay `@service.safe`, `@service.idempotent`, `@service.destructive` and `@service.summary`**,
  also before a verb key in a projection, and `meta.ref<service.exchange>` and `service.protocol` keep their names.
- **A description using HTTP imports `http.tn` alone**, and sees every `service.*` name through its import closure; a
  protocol-only description imports `service.tn`.
- **A root of its own for the http side** costs every http-side name its qualified spelling, and leaves the protocol
  schema's consumers no http names under `service`. Each root is reserved only against a root that is no schema map,
  below.

## What is measured

Every claim is pinned by a test of `SchemaSplitProbeTest`, cited by method name; the probe's http root, where it is not
`service` or `http`, is a placeholder.

- **Both schemas resolve.** The http schema composes an imported record (`operation` is an `endpoint` composed with the
  protocol's `signature`), holds one as a field, types `meta.ref` slots over the imported `exchange` and `protocol`, and
  keeps its abstract `endpoint` with every subtype declared beside it; an untagged endpoint is refused naming the four
  kinds (`theHttpSchemaImportingTheProtocolSchemaResolves`).
- **A description in one document reads.** A description importing both schemas declares a protocol and a projection of it.
  Every reference across the layers is judged at load and delivered its referent, including a binding's
  `orders.exchanges.get_order`, which walks a record value's field
  (`theOneDocumentDescriptionDeliversEveryReferenceAcrossTheLayers`,
  `theOneDocumentDescriptionJudgesEachReferenceAcrossTheLayers`). A data document importing that description reads a binding
  through the tree reader, the object reader and `validate`
  (`aDataDocumentImportingTheDescriptionReadsABindingThroughEveryEntryPoint`).
- **A description in two documents reads.** The protocol entry in one schema, the projection in a second importing it and
  the http schema: the binding's reference walks the protocol across the import, warm or cold, and an unsound key is
  refused at load (`theTwoDocumentDescriptionWalksAnExchangeAcrossTheImport`). A data document importing the
  projection's description alone reads a binding too (`aDataDocumentImportingTheProjectionsDescriptionReadsABinding`).
- **A second projection layer beside the http one** resolves over the same protocol description, and so does a schema
  importing both projections, which reach the protocol description by two routes that unify
  (`aSecondProjectionBesideTheHttpOneResolvesAndSoDoesASchemaImportingBoth`). The gRPC projection of `BACKLOG.md` needs
  this shape.
- **A description importing only the http schema sees the protocol vocabulary**, because a schema's namespace is its own
  entries and its whole import closure's. `!service.protocol`, the facts annotations and `service.exchange` resolve with
  no import of the protocol schema (`aDescriptionImportingOnlyTheHttpSchemaSeesTheProtocolSchemaThroughItsClosure`). The
  closure is only what is imported: the http schema imports `net.tn` itself for `media_type`, which the protocol schema
  does not.
- **The facts, the positional form and binding hold.** `@service.safe`, `@service.idempotent`, `@service.destructive` and
  `@service.summary` read through, on an exchange's key and before a verb key in the projection, and a misspelt one is
  refused (`theFactsAnnotationsReadThroughTheSplit`). `!service.protocol` written as the map alone still works, and the
  binding's reference still walks the field the document did not write
  (`thePositionalFormHoldsWithTheProjectionInAnotherSchema`). One bind context keyed by qualified name binds both layers,
  including a record composing an imported one and a slot typed over an imported record (`oneBindContextBindsBothLayers`).
- **A root of the http side's own is open to every importer**, as `service` is. Both are nested schema maps, and
  [TSON-SCHEMA] §2.2.3 makes every nested schema map open: a domain schema's `http => !schema { thing => … }` resolves and
  its `http.thing` reads, and only a member path both schemas declare collides, naming both schemas
  (`aRootOfTheHttpSchemasOwnIsOpenToEveryImporter`). A record or type named `service` or the http root collides, since it
  is no schema map (`theHttpRootIsReservedInEveryDescriptionAndTheServiceMapIsOpenToAll`). So each root is reserved only
  against a root that is no schema map.
- **A root map may hold a member of its own name**, `http.http`, so a root named for the transport is admissible
  (`aRootMapMayHoldAMemberOfItsOwnName`).
- **The http schema may instead add its entries to the open `service` map**, with no rename
  (`theHttpSchemaMayAddItsEntriesToTheOpenServiceMap`); a root of its own is the shape adopted, for the reason
  above.

## What the real files add

The probe's cut-down schemas, its placeholder root and its own bound records leave the real files to be measured by
their own tests:

- `TsonServiceSchemaTest` in `tson-service` holds `service.tn` alone: it resolves with no other schema, no http name is
  visible through it, and its bound records are the protocol's and nothing else.
- `TsonHttpSchemaTest` holds `http.tn` and the reader, and the real fixtures (the orders examples, the demos'
  `orders-api-1.tn`, `validate-api-1.tn`) import `http.tn` alone, reading their protocol vocabulary through the closure.
- Bindings keyed per schema (`TsonBindings.bySchema`) must key both identities; a protocol reached through `binds` and held
  as a tree is refused by `TsonServiceDescription` naming the fix
  (`TsonServiceDescriptionExternTest.aProtocolLeftUnboundByPerSchemaBindingsIsRefused`).
- `referencedSchemas()` of an HTTP description lists `http.tn` then `service.tn`, and a server publishes both
  (`OrderServerTest.publishesBothServiceLayersAtTheirIdentityPaths`, `ValidatorServerTest` the same).

## Caveats

1. **A data document importing a protocol description and the http schema separately throws** at a reference through a
   record value's field, from `validate`, the tree reader and the object reader alike
   (`aDataDocumentImportingTheProtocolDescriptionAndTheHttpSchemaThrows`). It is the open `UPSTREAM.md` entry "A data
   document importing two schemas throws at a reference through a record value's field", pinned from the other side by
   `AffordanceProbeTest.anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField`. It does not reach a schema
   document, which is what a description is; it reaches a data document naming two schemas. The split makes that shape
   ordinary: a document wanting a protocol description and the http schema beside it imports two schemas, or a
   description importing both, which reads. Nothing planned is found to need the failing shape: the rpc wire form
   (`experiments/rpc/README.md`) names one wire schema derived from the protocol, the affordances proposal's instance
   document imports the description alone, which reads, and the gRPC projection is a second schema importing the
   protocol, a description importing it and the http schema being the schema-document shape.
2. **A library gap, staged in `UPSTREAM.md`.** A local declaration colliding with an imported one, such as a schema
   declaring `service.exchange` inside the open `service` map it imports, is refused with a message that names neither
   schema and a diagnostic whose `schemaId` is empty, where §2.2.3 says the diagnostic names both declaring schemas; two
   imports colliding do name both (`aMemberBothSchemasDeclareCollidesNamingNeitherSchema`). `UPSTREAM.md`'s entry "A local
   declaration colliding with an imported one names neither schema" stages it. The split does not depend on it, since a
   collision is refused either way.

## Not proposed

- Splitting the Java further, or moving a type between `protocol` and `http`; the packages follow the split.
