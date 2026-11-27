# Describing a service (`service.tn`, `io.ltr8.tson.service`)

The protocol records (`Protocol`, `Exchange`, `Signature`) are in `io.ltr8.tson.service.schema`; `ExchangeName` and
`TsonServiceSchema`, the holder of `service.tn`, are in the root `io.ltr8.tson.service`, and the module, `tson-service`,
names nothing of HTTP. The layer is two schemas: `service.tn`, the protocol side and the exchange facts, naming no
transport, and `http.tn`, the HTTP projection, which imports it (`TsonHttpSchema` in `io.ltr8.tson.http`, in
`tson-http`).

Why a service description is a schema, and what `service.tn` declares at the protocol level. What the reader checks, how
a server's bindings, catalog and warm-up derive from the description, coverage and business errors are
`design/service-reader.md`; the reader is `tson-http`'s. Everything HTTP, the http projection, its
endpoints, placement, statuses, media types, security schemes, response headers, the verb checks and `Route`, is
`design/http-projection.md`. The orders examples under `tson-http/src/test/resources/io/ltr8/tson/http/orders/`
(in `tson-http`'s test tree, since they import `problem.tn`) are one real document per use of the layer, reached through
`OrdersExamples`; `experiments/rpc/` and `experiments/agent/` read them there too.

**What the layer is for.** This project builds service capabilities that a model, as a caller, integrates with better
than today's conventions allow. The Model Context Protocol and RFC 9880 (SDF) are design input for those capabilities,
not targets: nothing here emits JSON Schema or implements the MCP specification as it stands. The aim is an alternative
that keeps what MCP gets right, discovery, callable tools, resources and change subscriptions, and goes beyond it where
TSON does: payloads that are the description's own types, self-describing and judged; business errors a model acts on;
references that are judged names; a security scheme declared, not negotiated. Its shape is a projection of a protocol
beside `http.projection` (`BACKLOG.md`, "An LLM-facing projection of a protocol"), and `experiments/agent/` is its draft.
A fact a model needs about an exchange belongs to the protocol, as `@service.destructive` does; how a model is offered
the protocol belongs to the projection.

**Invariants**

- A document that exports a namespace is a schema; a document with at most a local scope is data; any document may
  refer to a type or a value in the namespace it names. Anything others refer into is a schema.
- The description is a schema importing `http.tn`, or `service.tn` alone when it declares protocols only, resolved at
  startup (`tson.resolve(API)` then `TsonHttpSchema.describedBy`); an unsound description fails startup. `http.tn`
  imports `service.tn`, so a description importing `http.tn` alone sees every `service.*` name through its import closure.
- Every entry of `service.tn`, its annotations included, is a member of the one schema map `service` at its root, and
  every entry of `http.tn` a member of `http`, so each costs an importer one root name, reserved only against a root that
  is no schema map. A description writes `!service.protocol`, `@service.safe`, `!http.projection`; a schema refers
  between its own entries by the same path; and the reader, the bindings and every `@Typename` use the qualified names.
  Nothing else is declared at either root. The facts annotations are `service.*` also before a verb key in a projection.
- `service.protocol` is a record whose one required field is the namespace `exchanges`, so a qualified name walks the
  field: `orders.exchanges.place_order`. A namespace's `key_type` is required and an identifier family.
- `@service.safe`, `@service.idempotent` and `@service.destructive` are facts of an exchange, written before its key in
  the protocol; safe implies idempotent, safe and destructive exclude each other, and nothing derives one from a verb.
- A schema entry has two annotation positions and they land in different places.

## A schema exports a namespace; data refers into one

A data document declares nothing others resolve against. It imports its schemas (`!!import`), selects a type among
them by its root's tag (a `!order` annotation), and may refer into the closure it imports: a name in a value position
is a reference where the slot is typed by a reference (`meta.type_ref`, a `meta.ref<…>`), to a type or to a value
alike, judged against that closure (`UpstreamGapsTest.aValueReferenceInADataDocumentIsJudged`), and an inert token
anywhere else. An argument list after a name is a parse error in any value ([TSON-DATA] §7.2.5), and so is a
directive, so nothing switches scope mid-document.

So the line is not whether a document may name a type: **a document that exports a namespace is a schema, a document
with at most a local scope is data, and any document may refer to a type or a value in the namespace it names.**

Two consequences:

- **Anything others refer into must be a schema**, not a data document governed by one. That is why the service
  description is a schema importing `service.tn`: a binding's `meta.ref<service.exchange>` and a claim in `binds`
  refer into it, and a description written as data could refer out but never be referred into. The deployment
  descriptor is data for the same reason turned round (`design/deployment-descriptor.md`).
- **The recurring `(schema identity, root type name)` pair is how the data layer names a type from outside any
  document**, `describing(…)`, `readObjectAs(…)`, the `TSON-Schema` header plus a route's type, because a reference
  exists only inside a document that imports the closure.

## The layer: a protocol, and projections of it

An OpenAPI-shaped description of a service whose payloads are TSON, minus the embedded schema language OpenAPI needs
because JSON has none: an endpoint **references** a published, identity-addressed schema. It describes the service
from the protocol's side, not the host language's (`design/invoke.md`): a **protocol** is a named set of
**exchanges**, each a request record, a response record and error records; an **http projection** is HTTP's own
constructs bridged to an exchange's request record by placement (`design/http-projection.md`).

```
orders => !service.protocol {
  exchanges: {
    @service.safe get_order => { request: order_ref  response: order  errors: [order_not_found] }
    place_order             => { request: new_order  response: order  errors: [sku_not_found] }
  }
}

orders_http => !http.projection {
  binds: [orders]
  resources: {
    "/orders"      => { POST => !http.binding { exchange: orders.exchanges.place_order  status: 201 } }
    "/orders/{id}" => { GET  => !http.binding { exchange: orders.exchanges.get_order } }
  }
}
```

**The names.** `protocol` is Avro's word for a named set of messages each with request, response and errors; `exchange`
is HTTP's own for a request and what answers it (RFC 9110, RFC 9113) and WSDL 2.0's. `http` is named for the transport
it projects a protocol onto, as a gRPC projection would be: one projection among several, not the description.
`service.signature` is the transport-neutral request, response and errors an exchange and an inline operation share;
`exchange => service.signature & {}` is a type of its own, where `exchange => service.signature` would be an alias and
its values would read back as signatures. `operation` names the inline endpoint, and `service` and `http` the two
layers, each its one root map. Each name is written by its path from its map.

**One root name per schema.** Every root name of an import closure is reserved in each importer, and a clash is
unfixable by the importer ([TSON-SCHEMA] §2.2.3), unless both declare a schema map of that name: a nested schema map is
open, so `service => !schema { … }` or `http => !schema { … }` in another schema adds its members to the layer's, and only
a path both declare collides, naming both schemas (`SchemaSplitProbeTest.aRootOfTheHttpSchemasOwnIsOpenToEveryImporter`,
`SchemaSplitProbeTest.theHttpRootIsReservedInEveryDescriptionAndTheServiceMapIsOpenToAll`). A record or type named
`service` or `http` collides. A layer declaring `document`, `resource` or `operation` at its root would forbid the
domain schemas beside it those names, so every entry of `service.tn`, the annotations included, is a member of the
schema map `service => !schema { … }` and every entry of `http.tn` of `http`, as the sibling's `policy.tn` nests
`policy`. A name resolves in the schema's one namespace, so an entry refers to another by its path from inside the map
as from outside it (`value_type: service.exchange`); a bare `signature` there is unresolved. A qualified annotation is
reported under the name written, so the reader asks `Annotations` for `service.safe`, an entry applying
`service.protocol` reports that qualified name, and each bound record's `@Typename` is its qualified name
(`http.binding`, `service.exchange`), as `TsonServiceSchema.bindings()` and `TsonHttpSchema.bindings()` key them.

Exchanges are keys inside their protocol and endpoints are values under a path and a verb, so nothing needs an invented
operationId. `demo/schemas/orders-api-1.tn` binds a protocol in an http projection; `validate-api-1.tn` is web-service
only, every endpoint inline.

**The maps borrow namespaces TSON does not own**: exchange names, URL path templates, RFC 9110's verbs, header names. A key
type is how a document borrows one, with **one naming role per borrowed namespace**, as the kernel has `type_name` and
`param_name` over one grammar; a record stating no `name_type` has `meta.identifier`'s. A namespace's key is a name, so its
role is an identifier family (`UpstreamGapsTest.aNamespaceKeyTypeMustBeAnIdentifierFamily`), `exchange_name =>
meta.identifier` or an `identifier_type` widened with `start_add` and `continue_add`. A plain map's key is no name, and its
role may be a text family with a `pattern`, as `http.path_template` and `http.header_name` are. Which a key is follows
from whether anything references it: a binding's reference walks into a protocol's exchanges, so they are a namespace and an
exchange name is a name; nothing references a path or a header, so a projection's resources and an endpoint's response
headers are plain maps, and a path is where the schema stops and HTTP begins. A role names itself in its refusal
(`NameRoleTest`), and §8.2's hygiene refuses a mixed-script or confusable name, a namespace key's and never a text key's. So
an exchange's identity is `(schema identity, qualified name)`, `…/orders-1.tn` and `orders.exchanges.place_order`, held as
`ExchangeName`. The HTTP roles and their `ASCII_CASEFOLD` are `design/http-projection.md`.

**A namespace is a map composed to name keys**: `meta.map & { key_type: meta.identifier_ref  voidable?: = false }`, a
composition restating `key_type` under a narrower type and pinning `voidable`, so its `key_type` is required and an
identifier family and no value is `_`. A family whose profile admits `.` is a resolver error as a key type, since a qualified
name splits at each dot ([TSON-SCHEMA] §4.2, §5.12;
`AffordanceProbeTest.aConcretePathKeyIsAnIdentifierFamilyWidenedAsAPathTemplateIs`). A qualified name walks its keys, and a
record value's field to reach one: `orders.exchanges.place_order` is the protocol's entry, its `exchanges` field and a key
(`UpstreamGapsTest.aReferenceThroughARecordValuesFieldIsJudged`), so `orders.place_order` is refused at load naming the
fields a protocol declares (`TsonServiceDescriptionTest.aTwoSegmentExchangeNameIsRefusedNamingTheField`). A record's fields
are no namespace, and no qualified name reaches a field declaration.

**A protocol is a record holding a namespace.** A namespace type declares its facets and nothing else
(`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`), so a value of one is the map alone, with nowhere to write a
fact about the whole. So `service.protocol` is a record whose one field, `exchanges`, is a `service.exchanges`, and a
fact about the protocol as a whole is a field beside it. `http.projection` is a record too, its one required field the
plain map `resources`, since nothing references an endpoint (`design/http-projection.md`). An entry applying either
record is a value, never a type. `service.exchange` is an ordinary record and heads no entry. `service.protocol` has the
positional form, one bare required field; what that costs is `design/http-projection.md`, "The positional form".
`proposal/namespace-fields.md` holds the measurement (`NamespaceFieldProbeTest`).

**An import crosses no level**, so `http.tn` takes `media_type` from `net.tn`, each layer its kernel names from `meta.tn`,
and an importer uses every name an imported layer declares (`UpstreamGapsTest.anImportCrossesNoLevel`,
`everyNameAnImportedLayerDeclaresResolvesInTheImporter`). An ordinary schema wanting `http.status_code` imports `http.tn`
or declares its own: `problem.tn` declares `problem.status`, the same refinement.

## An exchange's facts

`@service.safe`, `@service.idempotent` and `@service.destructive` are bare markers before an exchange's key in its
protocol: `@service.idempotent @service.destructive cancel_order => { … }`. Annotations, not fields, because they
describe the exchange, not its request or response; they are the facts a caller composing calls needs, what may be retried,
reordered or must be confirmed first. `safe` and `idempotent` are RFC 9110's words; safe implies idempotent.
`@service.destructive` is an effect the caller cannot undo, the one fact an LLM tool-calling convention asks for that
the other two do not state. No verb implies it, since a `DELETE` may cancel a reservation and a `POST` may wipe a table.
It excludes `@service.safe`, and the reader refuses an exchange marked both, a bound protocol's exempted ones included.
A projection checks its verb against these facts and never derives them (`design/http-projection.md`, "Verb checks").

## Documentation, and the two annotation positions

**Documentation is annotation on a key.** `@service.summary` and `@doc` before a verb key document that endpoint; an
exchange's sit before its key in the protocol, and a binding falls back to them (`Route.summary()`, `Route.doc()`).
**A schema entry has two annotation positions and they land in different places**: `@doc:"…" op => …` annotates the
**entry**, read from `entries.getAnnotations(name)`; `op => @doc:"…" { … }` annotates the **definition**. Reading only
the second and concluding `@doc` is dropped looks exactly like a library bug
(`UpstreamGapsTest.anEntrysTwoAnnotationPositionsLandInDifferentPlaces`).

## What builds on the protocol, and is not built

`experiments/rpc/` is its wire form, `call` and `return` as parametric records applied per exchange, outside `tson-http`, in
`tson-service`, once it lands. `experiments/agent/`, a plan of calls, is the draft of the LLM-facing projection (above): a
consumer that is not HTTP, pressing on the protocol for what a model needs. A decision that is a projection's alone lands in
the projection.
