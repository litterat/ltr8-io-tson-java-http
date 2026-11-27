# Affordances beyond the exchange, and a namespace of live objects

**Status:** a proposal for review; nothing adopted. The probe measures an event kind, a property kind and an instance
namespace in a layer of its own (`tson-http/src/test/java/io/ltr8/tson/http/probe/AffordanceProbeTest.java`), not in
`service.tn` or `http.tn`. Verdicts: **an event kind, adopt later**; **a property kind, not proposed**; **a live namespace of
objects at a deployment, a sketch kept as an open question**. It asks nothing of the spec, and reports one library gap.

## Summary

- RFC 9880 (SDF) is a witness here, not a source. Its applicability is models of physical Things (Abstract, §1), and its
  data definitions "are not intended to constrain the formats of data used for communication over network interfaces"
  (§2.2.2), where this layer's types *are* the payloads. Each idea below is judged by its value to HTTP services.
- **A property kind is not proposed.** In HTTP, state read and written is a resource with a safe `GET` and an idempotent
  `PUT`, which the layer states today as two exchanges and two bindings; a kind would add a grouping, not a capability.
- **An event kind is adopted later**, because server-initiated output is a gap for HTTP services in general, with
  OpenAPI 3.2.0's `itemSchema` over sequential media types as the HTTP-native witness: a `!http.stream` endpoint kind
  composing `http.endpoint`, and `events?: service.events` beside `exchanges`, measured to break no document.
- **A live namespace of objects** (a data document keyed by concrete path, each object referring back into the
  description) is sketched as a discovery and conformance surface, with W3C WoT's Thing Description and CoRE's
  `/.well-known/core` as prior art. Its key family and back-references are measured; so is a library gap it would hit.
- One confirmation: SDF arrived at the same split between a model and its protocol binding and leaves the binding to a
  companion document base SDF does not define (§1.2.3), so nothing checks it; this layer judges the join.
- Not proposed: `sdfRequired` profiles, `observable` as a quality, `sdfRef`'s merge patch, a lexicographic `version`,
  extension by registry string, and WebSocket inside the http projection.

## SDF in two paragraphs

An `sdfObject` groups affordances (§2.2.1) and an `sdfThing` composes objects (§2.2.6). SDF
has three affordance kinds (§1.2.2): a Property "can potentially be used to read, write, and/or observe state", with
`readable`, `writable` and `observable` booleans all defaulting to true (§5.2, Table 7); an Action performs "a named
operation", with one `sdfInputData` and one `sdfOutputData` (§5.3, Table 8), and "may have (or lack) the
characteristics of idempotence and side-effect safety" in RFC 9110 §9.2's sense (§2.2.3); an Event informs "about what
happened", with `sdfOutputData` alone (§5.4, Table 9). `sdfRequired` lists the declarations a grouping must represent
(§4.5). How any of it travels is "left to companion protocol bindings" (§2.1), a Protocol Binding being "a companion
document to an SDF Model that defines how to map the abstract concepts in the model into the protocols" and supplying
"URL components, numeric IDs, and similar details" (§1.2.3); base SDF defines no such mechanism.

So `service.protocol` is SDF's actions, with the same facts: `@service.safe` and `@service.idempotent` are RFC 9110's
words, as §2.2.3's are. WoT's Thing Description carries them on an action too (`safe`, `idempotent`, §5.3.1.4), both
defaulting to false (§5.4, Table 31), the layer's own reading of an absent marker. The http projection is SDF's protocol
binding, except that its `exchange` is a reference the resolver judges, `binds` a claim the reader holds per exchange,
and an unbound exchange needs a reason. SDF arrived at the split from the IoT side and left the join unchecked; that
convergence is evidence the split is right, and the only thing taken from SDF.

## Idea 1: the two affordance kinds the layer does not capture

**The extension point is free.** `service.protocol` is a record whose one required field is `exchanges`, so optional
siblings break nothing: the positional form needs one bare *required* field and keeps it.

```
protocol => { exchanges: service.exchanges  events?: service.events }
```

The record form supersedes tagging every exchange so a later kind could share its namespace: each kind is a namespace
of its own beside `exchanges`, and a reference spells the field, `lamp.events.overheated`. A protocol offering only
events writes `exchanges: {}`: making `exchanges` optional too would leave no required field and withdraw the
positional form from every document written in it (measured).

**A property kind: not proposed.** It would be `property => { type: meta.type_ref }` in a `properties?` namespace, its
facts markers on its key, `@service.writable @service.observable brightness => { type: int32 }`, as `@service.safe` sits
on an exchange's key because it describes the affordance, not its data. Markers rather than SDF's booleans: absent is
"no", so a property missing `@service.writable` would have its `PUT` binding refused at startup, where SDF's default of
true publishes a write nobody serves. The probe loads all of it, `GET` and `PUT` under one path naming the property.

What it would add is a name over two exchanges. `readable` is a safe exchange answering the type, bound at `GET`;
`writable` an idempotent exchange taking it, bound at `PUT`, its one field carried unwrapped by `body:`. Both are
written and checked today. The kind would also strain the layer's best rule, that an exchange takes exactly one record
and `placement` distributes its fields: a property's read takes nothing, its write a bare value, so placement has no job
and the one-record rule bends for one kind. SDF can afford a property kind because it has no placement to protect, and
because a device's state is the model's centre; an HTTP service's resources are already its state. The one quality a
property has beyond two exchanges, `observable`, is an event whose payload is the property's type, so it folds into
idea 1's other half.

**An event kind: adopt later.** Output the server initiates is common across HTTP services, not peculiar to devices:
server-sent events, long-poll, webhooks. The HTTP-native witness is OpenAPI 3.2.0, whose `itemSchema` describes "each
item within a sequential media type" (§4.14.1), applied "to each item in the stream independently" (§4.14.3.1.1), for
types such as `text/event-stream`, `application/jsonl`, `application/json-seq` and `multipart/mixed` (§4.14.3.1): the
stream stays one response and its item is typed. The layer cannot state any of it, and lists callbacks as deliberately
absent. Its counterpart is an endpoint kind, in the layer's two forms:

```
stream => http.endpoint & { items?: meta.type_ref  event?: meta.ref<service.event> }
event  => { payload: meta.type_ref }
events => !meta.namespace { key_type: service.exchange_name  value_type: service.event }

lamp => !service.protocol { exchanges: { … }  events: { overheated => { payload: heat } } }
"/lamp/events" => { GET => !http.stream { event: lamp.events.overheated  media_types: ["text/event-stream"] } }
```

Inline, `items` types each item as an operation carries its signature; bound, `event` names a protocol's event as a
binding names an exchange, and the reader requires exactly one. `http.stream` composes the base as
`http.document` does for a body that is no TSON value, so the one-record rule is untouched: a stream takes a request
record placed as any endpoint's, and answers a sequence. The reader's rules extend the existing ones: a bound event
belongs to a protocol its projection binds, `binds` covers events as it covers exchanges, and a stream lists a
sequential media type. The probe measures the bound form; the inline form is a `meta.type_ref` field, as
`http.document`'s `request` is. A webhook, where the server requests a URL the client supplied, is no stream and is
an open question.

## Idea 2: a live namespace of objects at a deployment

The http projection describes the **model**: path templates, verbs, types. A running deployment serves an **instance**:
concrete paths, each holding a live object of some kind, a schema document, an exchange's endpoint at `/orders/42`, a
protocol's call route (`BACKLOG.md`'s endpoint naming a protocol, one `POST` taking call documents), an event source.
That set is itself a namespace. WoT draws the same line: a Thing Model "does not provide details about a single Thing
instance due to the lack of communication and security metadata" (TD 1.1 §1.2), and a TD's form reads "To perform an
operation type operation on form context, make a request method request to submission target" with `href` the target
(§5.3.4.2). CoRE serves the instance side as links: `/.well-known/core` returns "links to the entry points of resource
interfaces on a server" (RFC 6690 §4), each typed by `rt` and `if` (§3.1, §3.2), and a Resource Directory holds them for
servers that cannot answer themselves (RFC 9176 §1).

**A second document beside the deployment descriptor**, not a schema: a namespace value keyed by concrete path whose
values are a tagged family of object kinds.

```
!!import:"https://api.example.com/2026/40/orders-api-1.tn"
!service.instance { name: "orders-1"  objects: {
  "/orders/42"                       => !service.served_exchange { exchange: orders.exchanges.get_order }
  "/lamp/events"                     => !service.event_source { event: lamp.events.overheated }
  "/schemas/service"                 => !service.served_document { media_type: "application/tson"  identity: "…" }
} }
```

- **The key** is a concrete path, not an `http.path_template`, and a namespace key must be an identifier family.
  `path_template`'s character set without the braces and without `.` does it, and admits `/` and `/orders/42`; a
  template variable, a relative path, a query and a path holding a dot are refused, and §8.2 judges the key as it
  judges a name. A family admitting `.` is refused as a key type, so neither the well-known path nor a schema's
  identity path can be a key.
  `http.path_template` has since become a text family keying a plain map, a path being no name, and the probe's
  layer keeps the shape measured; nothing references a concrete path either, so whether the instance needs a
  namespace at all, or a plain map keyed by an ASCII-exact text family, is part of the open question.
- **The back-reference** names what the model declares by its qualified name: an exchange, an event, a protocol. It
  cannot name a resource or an endpoint without quoting the whole reference, `"orders_http.resources./orders"`, and
  then only in a namespace keyed by a dotless path family (`NamespaceFieldProbeTest
  .aPathKeyIsReachedByAReferenceQuotedWhole`); the exchange is the better referent anyway, being what a client acts on.
- **Data, not a schema.** Nothing names into it, it changes as objects come and go, and it has no identity to pin,
  the descriptor's reasons (`design/deployment-descriptor.md`); like the acceptance profile, a hint at a well-known
  path. Both readers deliver every reference.
- **Where its vocabulary lives** is decided by a measured gap: a data document importing the description and a
  separate instance vocabulary throws at the first reference walking a protocol's field, so the vocabulary would be
  members of `service.tn` and `http.tn` until upstream links a several-import closure with a bind context.

What it is for: an agent discovering which objects exist rather than which could; a conformance client checking each
listed path answers its kind; cache invalidation keyed on a path's schema identity; a reverse proxy composing
deployments, two instances listing one path being a conflict found before traffic. What it
costs: enumerating objects is unbounded (every order) and can leak, so it lists entry points, as RFC 6690 §4 does; it
goes stale; and for static routes it repeats the description.

## What is measured

Each point is a test of `AffordanceProbeTest`, against a probe layer declaring bare names at its root (`!protocol`,
`'protocol'`, where the layer would write `!service.protocol`), the property kind and an `interface` endpoint among them.

**Optional siblings** (`optionalSiblingNamespacesKeepEveryExchangesOnlyDocument`). With `protocol => { exchanges:
exchanges  properties?: properties  events?: events }`, a protocol writing exchanges only loads in the full form and in
the positional form, `!protocol { get_order => … }`; one writing all three loads; an absent sibling binds as `null`.

**No required field** (`aProtocolWithNoRequiredFieldLosesThePositionalForm`). With `exchanges?` optional the positional
form is refused: *'orders': the body is no value of the type 'protocol' -- expected a record for 'protocol', found a
map*. With it required, a properties-only protocol is refused, *… at /exchanges: missing required field 'exchanges' for
'protocol'*, and loads with `exchanges: {}`.

**A property's facts** (`aPropertysFactsAreAnnotationsOnItsKey`). `@writable @observable brightness` survive as the
key's annotations in the bound `Namespace`; `@obsrvable` is refused, *… at /properties/?: annotation '@obsrvable' names
no type the governing schema declares (§6)*. An exchange and a property may share a name.

**Endpoint kinds** (`aStreamEndpointIsAdmittedBesideBindingAndDocument`, `aPropertyBindsAtTwoVerbsOfOnePath`).
`!stream`, `!state` and `!interface` load beside `!binding` and `!document`, references delivered; a
`meta.ref<source>` over an empty base of `property` and `event` reaches either. Refused:

- an exchange as a stream's source: *… /GET/source wants a 'source', and 'lamp.exchanges.brightness' is an
  'exchange', which is no 'source'*
- an untagged endpoint: *… at /resources/~1lamp~1events/endpoints/GET: 'endpoint' has no data of its own to bind --
  provide an explicit type annotation (!typeName) naming one of its subtypes [binding, document, stream, state,
  interface]*
- an event at a property slot: *… /PUT/property wants a 'property', and 'lamp.events.overheated' is an 'event', which
  is no 'property'*

**The instance key** (`aConcretePathKeyIsAnIdentifierFamilyWidenedAsAPathTemplateIs`). A text family is refused as a
namespace's key type, *'objects' key_type names 'concrete_path', which is no identifier family*. `meta.identifier` is
accepted and refuses the key, *'concrete_path': '/orders/42': U+002F at index 0 cannot start an identifier*. An
`identifier_type` starting with `/` and continuing with `/+%-_~!$&()*,;=:@`, its `pattern` `/([^%{}]|%[0-9A-Fa-f]{2})*`,
admits `/` and `/orders/42`, and refuses (each `ATOM_FORM_INVALID` but the last):

- `/.well-known/tson-deployment` and `/2026/40/io/ltr8/http/service.tn`: *U+002E at index 1 cannot appear in an
  identifier*, and *at index 29*
- `/orders/{id}`: *U+007B at index 8 cannot appear in an identifier*
- `orders/42`: *U+006F at index 0 cannot start an identifier -- its Start characters are '/'*
- `/orders/42?x=1`: *U+003F at index 10 cannot appear in an identifier*
- `/pаth`, Cyrillic `а`: `RESTRICTED_SCRIPT`, *the name '/pаth' mixes the scripts [LATIN, CYRILLIC] …*

The same family with `.` added to its continue characters is a `SCHEMA_ERROR` at the key type, *'objects' key_type
names 'concrete_path', which cannot name a namespace's keys -- its profile admits '.' (U+002E), which joins the segments
of a qualified name, so a name holding one could not be reached by a reference* ([TSON-SCHEMA] §5.12, §4.2). The
probe's instance keys the description document as `/schemas/service` for that reason.

**An instance as data** (`anInstanceIsADataDocumentReferringIntoTheDescription`). Importing the description alone, it
validates clean; the tree reader's atom at `/objects/~1orders~142/exchange` holds a `ValueRef` delivered its `Exchange`;
the object reader binds an `Instance` whose `Namespace<Live>` keeps written order, each object its kind, each reference
delivered. Refused at the object: a missing key (*'orders.exchanges' has no key 'nope' -- it holds get_order*), the
wrong kind (*'lamp.properties.brightness' is a 'property', which is no 'exchange'*), and an untagged object, read as the
empty base: *unknown field 'exchange' on 'live' -- a record is closed under its type (§7.2), whose fields are ()*.

**The gap** (`anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField`). Importing the description and a separate
instance vocabulary, `validate` and both readers throw `IllegalStateException`, warm or cold: *… 'orders.exchanges' is
a field of a record value, and this schema was linked with no bind context to read it through …*, since
`TsonCompiledSchemaRegistry.get(List, …)` links a several-import closure without one. Staged in `UPSTREAM.md`.

## What changes in Java, if the event kind is adopted

A sketch. `Event` in `io.ltr8.tson.service.schema` and `Stream` in `io.ltr8.tson.http.schema`, bound as
`service.event` and `http.stream`;
`Stream` joins `Endpoint`'s permits; `Protocol` gains `events`. `TsonExchangeName` generalises to `(protocol, field, key)` so
`binds` holds per event. `TsonServiceDescription` checks a stream's items or event and its media type; `TsonServiceCoverage`
is unchanged. Each adapter writes `text/event-stream` its own way (a chunked body under `com.sun.net.httpserver`, Javalin's
and Helidon's SSE support).

## Costs

- A stream commits its `200` before the first item, so a failure mid-stream cannot become a `problem.details`
  (`design/adapters.md`); whether it ends with a final problem item or just closes is a rule still to write.
- An events-only protocol writes `exchanges: {}`, since the required field keeps the positional form.
- Each adapter's concurrency suite gains long-lived responses.
- The `objects` namespace cannot be keyed by a concrete path holding a dot, which rules out the well-known path and a
  schema's identity path it was meant to list; a document is listed under a dotless alias (`/schemas/service`) or the
  instance is a plain map, which no reference enters.

## Not proposed

- **A property kind**: a grouping over a safe `GET` exchange and an idempotent `PUT` exchange the layer already states,
  bending the one-record rule for no new capability. It earns its place in SDF, whose subject is a device's state.
- **`observable` as a quality**: an observed property is an event whose payload is the property's type.
- **`sdfRequired` as a client-side profile** (§4.5): it serves many vendors implementing one device model; an HTTP
  service publishes its own description, and `binds` holds a projection to every exchange.
- **`sdfThing` composition** (§2.2.6): a projection binding several protocols already composes them.
- **`sdfRef`'s copy and merge patch** (§4.4): overrides are "a convention as it cannot be checked" (§6.2.1); the layer
  has no inheritance between protocols, and a field restated in the kernel's composition keeps IS-A.
- **A lexicographic `version`** (§3.1): a schema is immutable at its identity (§3.5); a change is a new identity.
- **Extension by registry string** (§2.3.3): an annotation is declared in a schema and typed, so a misspelt one is
  refused (measured above), where an unregistered prefix is merely unknown.
- **WebSocket in the http projection**: no RFC describes a WebSocket service, and a connection's one HTTP-level fact
  is its subprotocol name (RFC 6455 §11.5, RFC 7936), so it is a projection peer of `http.projection`, as `BACKLOG.md`'s
  gRPC one is, carrying a protocol's call, return and event documents.

## Order of work, if the event kind is adopted

1. `service.tn`: `service.event`, `service.events`, `events?`; `http.tn`: `http.stream`; and their `@doc`s.
2. The bound records, `TsonServiceSchema.bindings()` and `TsonHttpSchema.bindings()`, `TsonExchangeName` over the field.
3. `TsonServiceDescription`'s stream rules and `binds` over events, with `TsonServiceDescriptionTest`.
4. A stream in the JDK demo, read by `TsonServiceConformanceTest`; then Javalin and Helidon.
5. `design/service-description.md` and `design/http-projection.md`; `AffordanceProbeTest` stays as the record of why.

## Open questions

- **One event or several per stream**: `event: meta.ref<service.event>`, or a list with SSE's `event:` field naming the
  key?
- **An item's wire form**: TSON text in `text/event-stream`'s `data:` lines, or a TSON sequential media type of its own,
  and what `TSON-Schema` states for a stream, once or per item?
- **Webhooks**: the server requests a URL the client supplied. Is that an exchange the client serves, so callbacks
  stay absent, or a subscription exchange plus an event?
- **The join**: bind's `DataProtocol` refuses a `Flow.Publisher` return, a promise-typed one of any kind, so what form
  does an event take in Java: a listener interface whose method is an exchange the client serves, a `DataExchangeHandler`
  of its own, or none, with events staying outside `DataProtocol`?
- **The live namespace**: keyed by a namespace, which no path with a dot can key, or by a plain map? A document of its
  own at a well-known path, or a field of the acceptance profile? Entry points only, or every object with paging? Its
  vocabulary in `service.tn`, or its own once a several-import closure links with a bind context (`UPSTREAM.md`, the
  data document importing two schemas)?

## Sources

- RFC 9880, Semantic Definition Format (SDF): §1, §1.2.2, §1.2.3, §2.1, §2.2.1 to §2.2.6, §2.3.3, §3.1, §4.4, §4.5,
  §5.2 to §5.4, §6.2.1. https://www.rfc-editor.org/rfc/rfc9880.html
- W3C, Web of Things (WoT) Thing Description 1.1: §1.1, §1.2, §5.3.1.3 to §5.3.1.5, §5.3.4.2, §5.4.
  https://www.w3.org/TR/wot-thing-description11/
- RFC 6690, CoRE Link Format: §3.1, §3.2, §4. RFC 9176, CoRE Resource Directory: §1.
- RFC 9110, HTTP Semantics: §9.2.1 (safe), §9.2.2 (idempotent).
- OpenAPI Specification 3.2.0: §4.14.1, §4.14.3.1, §4.14.3.1.1. https://spec.openapis.org/oas/v3.2.0.html
- RFC 6455, The WebSocket Protocol: §11.5. RFC 7936, Clarifying Registry Procedures for the WebSocket Subprotocol Name
  Registry.
