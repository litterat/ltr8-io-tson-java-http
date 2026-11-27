# Splitting `service.tn` into a protocol schema and an http schema

**Status:** a proposal for review; nothing is adopted. The probe is written and passing, and the proposal awaits the user's
verdict. Measured by `tson-http/src/test/java/io/ltr8/tson/http/probe/SchemaSplitProbeTest.java`, with its bound records
under `probe/schemasplit/`. It concerns this project's own vocabulary, `service.tn`, and asks of tson-java nothing beyond one
library gap, staged in `UPSTREAM.md` (below). The probe measures a cut-down pair of schemas written in the test, not
`service.tn` itself, so what it pins is the shape of the split and not the real file's every entry.

## Summary

`service.tn` is one schema holding two things that reference one way. The protocol side declares what a service says
about its exchanges. The http side declares the projection of a protocol onto HTTP and reaches the protocol side at four
points; nothing on the protocol side names anything HTTP. The Java side already separates them
(`io.ltr8.tson.service.protocol` and `io.ltr8.tson.service.http`); the schema does not. The question is whether the
file can follow, and what it costs.

It can, and it costs no rename. The protocol schema keeps the root map `service`; an http schema imports it and adds its
entries to the same `service` map, since a nested schema map is open ([TSON-SCHEMA] §2.2.3): one path declared by
several documents of a closure is one map, the union of their members. Every name keeps today's spelling
(`!service.http`, `!service.binding`, `service.endpoint`), and so does every key of the bind map
(`SchemaSplitProbeTest.theHttpSchemaMayAddItsEntriesToTheOpenServiceMap`). A root of its own for the http side also
works and costs the renames listed under "Open questions".

**Recommended verdict: split the file and keep the root.** Two schemas, the http one importing the protocol one and
adding to the open `service` map, no name changed. The alternatives and the decisions that are the user's are below.

## What is measured

Every claim is pinned by a test of `SchemaSplitProbeTest`, cited by method name; the probe's http root, where it is
not `service`, is a placeholder.

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
  no import of the protocol schema, so an existing description that imports the one file need add no import
  (`aDescriptionImportingOnlyTheHttpSchemaSeesTheProtocolSchemaThroughItsClosure`). The closure is only what is
  imported: the http schema must itself import `net.tn` for `media_type`, which the protocol schema does not.
- **The facts, the positional form and binding hold.** `@service.safe`, `@service.idempotent`, `@service.destructive` and
  `@service.summary` read through, on an exchange's key and before a verb key in the projection, and a misspelt one is
  refused (`theFactsAnnotationsReadThroughTheSplit`). `!service.protocol` written as the map alone still works, and the
  binding's reference still walks the field the document did not write
  (`thePositionalFormHoldsWithTheProjectionInAnotherSchema`). One bind context keyed by qualified name binds both layers,
  including a record composing an imported one and a slot typed over an imported record (`oneBindContextBindsBothLayers`).
- **A root of its own works too**, including a root map holding a member of its own name, `http.http`
  (`aRootMapMayHoldAMemberOfItsOwnName`).

## Caveats

1. **A data document importing a protocol description and the http schema separately throws** at a reference through a
   record value's field, from `validate`, the tree reader and the object reader alike
   (`aDataDocumentImportingTheProtocolDescriptionAndTheHttpSchemaThrows`). It is the open `UPSTREAM.md` entry "A data
   document importing two schemas throws at a reference through a record value's field", pinned from the other side by
   `AffordanceProbeTest.anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField`. It does not reach a schema
   document, which is what a description is; it reaches a data document naming two schemas. The split makes that shape
   ordinary: under one `service.tn` a protocol-only description already carries the http types through the one schema,
   and after the split a document wanting both beside the protocol imports two schemas, or a description importing both,
   which reads.
2. **The open `service` map is open to every importer.** A domain schema may declare `service => !schema { thing => … }`
   and its `service.thing` reads; only a member path two schemas both declare collides, naming both schemas. A record
   named `service` collides, since it is no schema map. So "the layer reserves one root name" holds only against things
   that are not schema maps (`theHttpRootIsReservedInEveryDescriptionAndTheServiceMapIsOpenToAll`). A root of the http
   side's own is reserved against everything, as a closed root is.
3. **A library gap, staged in `UPSTREAM.md`.** A local declaration colliding with an imported one, such as the http
   schema declaring `service.exchange`, is refused with a message that names neither schema and a diagnostic whose
   `schemaId` is empty, where §2.2.3 says the diagnostic names both declaring schemas; two imports colliding do name
   both (`aMemberBothSchemasDeclareCollidesNamingNeitherSchema`). `UPSTREAM.md`'s entry "A local declaration colliding
   with an imported one names neither schema" stages it. This proposal does not depend on it, since a collision is
   refused either way.

## What adopting it would change

No design here, only what is touched.

- **The schema files.** `tson-service/src/main/resources/service.tn` becomes two files. The protocol side: `signature`,
  `exchange`, `exchanges`, `protocol`, `exchange_name` and the annotations `summary`, `safe`, `idempotent` and
  `destructive`. The http side: `http_verb`, `status_code`, `path_template`, `header_name`, `placement`, `endpoint`,
  `document`, `operation`, `binding`, `resource`, `exemption`, `resources`, `http`, `security_scheme`, `http_auth`,
  `api_key`, `auth_scheme` and `api_key_in`. The `@doc` of `service.tn` is split with them. `schema-authoring.md`'s
  `!!id` rules apply to the new file.
- **`TsonServiceSchema`.** Its source and its published set (`publishedById`) hold two schemas, and `ID` and the
  resolution of the layer follow whichever identity the user chooses. Its `bindings()` map keeps its keys under the
  keep-the-root verdict, and every bound record's `@Typename` is unchanged. The bound records are already in two
  packages, so the Java follows the schemas without a move.
- **The notes.** `design/service-description.md`, `design/http-projection.md`, `design/service-reader.md` and
  `design/schema-authoring.md` describe `service.tn` as one schema and say so in their invariants.
- **Descriptions.** What changes in an existing description depends on question (a).

## Open questions

- **(a) Which half keeps `service.tn`'s `!!id`.** If the http half keeps it and imports a new protocol schema, every
  existing description and every published reference to `service.tn` changes nothing at all, and the protocol schema is
  the new identity. If the protocol half keeps it, the identity names the smaller schema, and each description using
  HTTP changes one `!!import`, to the new http schema. A description importing only the protocol schema is the one that
  gains, since it no longer carries the http types. Nothing is published, so either is cheap now.
- **(b) The new schema's name and `!!id`.** Under `https://tson.io/2026/40/io/ltr8/http/` with no version in the name,
  per `design/schema-authoring.md`; the file name, and whether a protocol schema lives under `http/` at all, are the
  user's to choose.
- **(c) Whether the http side takes a root of its own instead of adding to `service`.** It works
  (`aRootMapMayHoldAMemberOfItsOwnName`), and it makes the http root closed: no importer may add members, and a
  protocol schema's consumers see no http names under `service`. The cost is a rename of every http-side name
  above, each spelling in every description, fixture, note and bound record's `@Typename` and every key of the bind map,
  and the facts annotations stay under `service`, so a description writes two roots. It is cheap only while nothing is
  published. Keeping `service` is also what leaves a layer's names extensible by an importer, which is caveat 2's other
  face: a domain schema can add `service.*` members, and one that collides with a layer member is refused only at
  resolution.
- **(d) Whether caveat 1 blocks anything planned.**
  - The rpc wire form (`experiments/rpc/README.md`) closes a packet by a wire schema derived from the protocol, a packet
    being `!place_order_call { … }`. As written there a packet names that one wire schema, not a description beside
    another schema, so it is not the shape the gap hits. Whether a call document ever imports a description and a
    separate schema is not stated there, and I have not measured it.
  - The affordances proposal's instance document (`proposal/affordances.md`) imports the description alone, which reads
    (`aDataDocumentImportingTheDescriptionReadsABindingThroughEveryEntryPoint` pins the same shape for a binding). Its
    vocabulary is placed in `service.tn` because of this very gap; after a split it would be members of one of the two
    schemas, or of the description's closure, and would only meet the gap if a document imported it as a third schema
    beside the description.
  - The gRPC projection is a second schema importing the protocol; a description importing it and the http schema is
    the schema-document shape, which reads.

  So nothing planned is found to need the failing shape; the proposal does not claim more than that.

## Not proposed

- A rename of the http side's names under the keep-the-root verdict.
- Splitting the Java further, or moving a type between `protocol` and `http`; the packages already follow the split.
