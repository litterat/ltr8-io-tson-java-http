# Describing a service (`service.tn`, `io.ltr8.tson.http.api`)

Why a service description is a schema, what the layer declares, what the reader checks, and how a server is held to it
at startup. The orders examples under `tson-http/src/test/resources/io/ltr8/tson/http/api/orders/` are one real
document per use of the layer, reached through `OrdersExamples`; `experiments/rpc/` and `experiments/agent/` read them
there too.

**Invariants**

- A document that exports a namespace is a schema; a document with at most a local scope is data; any document may
  refer to a type or a value in the namespace it names. Anything others refer into is a schema.
- The description is a schema importing `service.tn`, resolved at startup (`tson.resolve(API)` then
  `TsonApiSchema.describedBy`); an unsound description fails startup.
- Every entry of `service.tn`, its annotations included, is a member of the one schema map `service` at its root, so
  the layer reserves one root name in every importer. A description writes `!service.protocol`, `@service.binds`; the
  layer refers between its own entries by the same path; and the reader, the bindings and every `@Typename` use the
  qualified names. Nothing else is declared at the root.
- Naming is the resolver's (references, `type_ref`, `ref<…>`), except a reference inside an annotation's value,
  which the reader checks; relating is `TsonApiDescription`'s, in one reader.
- The description is the source of truth for what a server publishes (`referencedSchemas()`), warms
  (`boundClasses(bindings)`) and speaks (`Route.mediaTypes()`). `TsonApiCoverage.requireComplete()` holds the server
  to it, and `notServedHere` with a reason is the only way out.
- A claim names the declared verb and path template, never the path the framework registers.
- `service.protocol` and `service.http` are namespace types, so a value of either is the map alone; a projection's
  claims, `@service.binds` and `@service.not_bound`, are annotations on its entry, and the names in them are checked by
  the reader.
- A media type is listed, never implied, except `application/tson` on every endpoint but a `!service.document`; a
  document listing none, and a non-document listing a type no codec here speaks, are startup failures.
- A namespace's `key_type` is required and an identifier family, so `service.path_template` is an `identifier_type`
  and §8.2 judges a path key.
- `service.resource` alone has the positional form, one bare required field; adding a second withdraws it and breaks
  every document written in it.
- A business error composes `problem` and is written by the handler, not thrown.

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
  description is a schema importing `service.tn`: a binding's `meta.ref<service.exchange>`, a claim in `@service.binds`,
  and `TsonApiCoverage` naming an operation all refer into it. A description written as data could refer into a schema's
  types, but nothing could refer into it. The deployment descriptor is data for the same reason turned round
  (`design/deployment-descriptor.md`).
- **The recurring `(schema identity, root type name)` pair is how the data layer names a type from outside any
  document.** `describing(…)`, `readObjectAs(…)`, the `TSON-Schema` header plus a route-supplied type: two strings
  reassembled at every call site, because a reference exists only inside a document that imports the closure.

## The layer: a protocol, and an http projection of it

An OpenAPI-shaped description of a service whose payloads are TSON, minus the embedded schema language OpenAPI needs
because JSON has none: an endpoint **references** a published, identity-addressed schema. It describes the service
from the protocol's side, not the host language's (`design/invoke.md`): a **protocol** is a named set of
**exchanges**, each a request record, a response record and error records; an **http projection** is HTTP's own
constructs bridged to an exchange's request record by placement.

```
orders => !service.protocol {
  @service.safe get_order => { request: order_ref  response: order  errors: [order_not_found] }
  place_order     => { request: new_order  response: order  errors: [sku_not_found] }
}

@service.binds:[orders]
orders_http => !service.http {
  "/orders"      => { POST => !service.binding { exchange: orders.place_order  status: 201 } }
  "/orders/{id}" => { GET  => !service.binding { exchange: orders.get_order } }
}
```

**The names.** `protocol` is Avro's word for a named set of messages each with request, response and errors; `exchange`
is HTTP's own for a request and what answers it (RFC 9110, RFC 9113) and WSDL 2.0's. `http` is named for the transport
it projects a protocol onto, as a gRPC projection would be: one projection among several, not the description.
`@service.binds` takes the word `!service.binding` and `@service.not_bound` use. `http_verb` calls RFC 9110's request
methods verbs so that `method` names nothing in the layer; `operation` names the inline endpoint, and `service` the
layer, its one root map and its module. Each is written by its path from that map: the words here are
`service.protocol`, `service.exchange`, `service.http` and the rest in a document.

**One root name.** Every root name of an import closure is reserved in each importer, and a clash is unfixable by the
importer ([TSON-SCHEMA] §2.2.3). A description imports the layer beside the domain schemas it describes, so a layer
declaring `document`, `resource` or `operation` at its root would forbid a domain those names. So every entry of
`service.tn`, `@service.summary`, `@service.safe`, `@service.idempotent`, `@service.binds` and `@service.not_bound`
included, is a member of the schema map `service => !schema { … }` nested in its root, as the sibling's `policy.tn`
nests `policy`, and the layer costs an importer the one name `service`. A name resolves in the schema's one namespace,
so an entry refers to another by its path from inside the map as from outside it, `exchange => service.signature & {}`
and `value_type: service.exchange`; a bare `signature` there is an unresolved reference. A qualified annotation is
admitted at a use site and reported under the name written, so the reader asks `Annotations` for `service.binds`, an
entry applying a namespace type reports `service.protocol` or `service.http` as the type it is a value of, and each
bound record's `@Typename` is its qualified name (`service.binding`), as `TsonApiSchema.bindings()` keys it.

Exchanges are keys inside their protocol and endpoints are values under a path and then a verb, so neither is a global
entry and nothing needs an invented operationId. `demo/schemas/orders-api-1.tn` binds a protocol in an http projection;
`validate-api-1.tn` is web-service-only, every endpoint an inline `!service.operation` or `!service.document`;
`OrdersExamples` holds all three forms.

**The maps borrow namespaces TSON does not own.** A protocol is keyed by exchange names, a projection by URL path
templates, a resource's `endpoints` by RFC 9110's verbs, and `service.placement` maps a request record's fields onto URL
segments, query keys and header names. A key type is how a document borrows a namespace it does not govern, with **one
naming role per borrowed namespace**, as the kernel has `type_name` for a schema map's keys and `field_name` for a
record's field names, over one grammar. A namespace's key is a name, so its role is an identifier family
(`UpstreamGapsTest.aNamespaceKeyTypeMustBeAnIdentifierFamily`): `exchange_name => meta.identifier`, or an
`identifier_type` widened with `start_add` and `continue_add` and carrying a `pattern`, as `service.path_template` is. A map
field's key may be a text family with a `pattern`, as `service.header_name` is, held case-folded as RFC 9110 §5.1 compares
it. A role names the namespace in its refusal (`NameRoleTest`), and §8.2's hygiene refuses a mixed-script or confusable
key, a path key included. So an exchange's identity is `(schema identity, qualified name)`: `…/orders-1.tn` and
`orders.place_order`, held as `ExchangeName`.

**A namespace is a map refined to name keys**: `meta.map ^ { key_type: meta.identifier_ref  voidable?: = false }`, so
its `key_type` is required and an identifier family, no value is `_`, and `ordered` and the size bounds are map's. A
qualified name walks its keys, `orders.place_order`. A projection's paths are a namespace keyed by an identifier family
widened to a path's characters, but nothing references an endpoint, so no qualified name walks a path key and an
endpoint is reached by location. A record's fields are no namespace: they are a text-keyed map, each name judged by the
record's `name_type`, and no qualified name reaches a field declaration.

**A binding refers to an exchange rather than being one.** An exchange could be a type under plain `meta.tn`, with an
operation refining it, but a schema fact stated as a field is per-instance: `verb: = POST` pins an unmarked field, which
[TSON-SCHEMA] §5.2 makes a *marker* each value must write (`TsonApiDescriptionTest`, the comparison kept at its end).

**An import crosses no level**, so the layer takes `media_type` from `net.tn` and its kernel names from `meta.tn`, and
an importer uses every name the layer declares (`UpstreamGapsTest.anImportCrossesNoLevel`,
`everyNameAnImportedLayerDeclaresResolvesInTheImporter`). An ordinary schema wanting `service.status_code` imports the
layer or declares its own: `problem.tn`'s `status` is an `int32`.

**An entry applying a namespace type is a value, never a type.** Naming one where a type is expected is a resolver error
("a value and not a type", `UpstreamGapsTest.aNamespaceEntryCannotBeUsedWhereATypeBelongs`). **A namespace value is the
map alone**: a namespace type declaring a field beside its facets is refused
(`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`), so what a projection claims about the protocols behind it is
annotation before its entry's name,
`@service.binds:[orders] @service.not_bound:[{ exchange: orders.refund  reason: "…" }]`, each declared in the layer as
an `@meta.annotation`. Everything inside (`service.exchange`, `service.resource`, `service.endpoint`,
`service.operation`, `service.binding`, `service.document`, `service.exemption`) is an ordinary record and heads no
entry.

## What the reader checks

**Naming is the resolver's; relating is the reader's.** `request`, `response` and `errors` are typed `type_ref`, and a
binding's `exchange` is `meta.ref<service.exchange>`, so a type nothing declares, a reference to the wrong kind of
thing, and an exchange key a protocol does not hold are refused at schema load; a qualified name says which protocol it
means. **The exception is a reference inside an annotation's value**, which the resolver does not judge
(`UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`): `@service.binds` and an exemption's `exchange` keep
their `ref<…>` typing to say what they mean, and the reader checks the names. `Binding.exchange` and
`Exemption.exchange` are `ValueRef<Exchange>`: a binding's is delivered its `Exchange` when the description links, an
exemption's never is, and the reader builds `ExchangeName` from the name in both. **An unsound description has no
model**, a startup failure rather than a published contract no client can act on:

- each `@service.binds` name is an entry applying `service.protocol`, and each exemption's `exchange` a key of a
  protocol the projection binds;
- a binding's exchange belongs to a protocol its projection binds. There is no inheritance between protocols, and a
  projection binding several names each;
- the `@service.binds` claim holds: every exchange of every bound protocol is bound, or exempted in `@service.not_bound`
  with a reason, held per `ExchangeName`, never per name, since two protocols may both declare `get_order`;
- each request record's fields are placed exactly once over the path, the query, the headers and the body
  (`RequestPlacement`): a `{segment}` binds the field of that name, `query` and `headers` name fields, `body` names one
  field carried unwrapped, and the remainder is the body, or the query for a GET or HEAD; a field in the path, query or
  a header is a scalar (an array of scalars in a query or header, which may repeat). A dependent field (`payload:
  kind!`, a `TypeInline` holding a `DependentBody` in `RecordField.type()`, its sibling's name in
  `RecordField.sibling()`) travels in the remainder body with its sibling, the one place a record instance holds both;
- a bound request record holding a dependent field loads only if every type a sender may name in its sibling binds
  (`ChosenTypes`): a free `meta.type_ref` sibling admits the kernel's records and never does, while a pinned sibling
  or a discriminator over bound subtypes does;
- the verb agrees with what the exchange says about itself, `@service.safe` and `@service.idempotent` on its key: GET
  safe, PUT and DELETE idempotent; and a response-less endpoint answers a status that carries no body;
- each business error fixes its status (`sku_not_found => problem & { status: = 404 … }`), which is how a client tells
  an error from the response by status alone; two errors of one endpoint pinning one status is refused;
- a `!service.document` lists a media type, and every other endpoint lists only types a codec here speaks (below);
- no two endpoints share a verb and a path, across every projection a description holds.

## Media types

`service.endpoint` carries `media_types?: [media_type]`, net.tn's `media_type`. **Every endpoint but a
`!service.document` speaks `application/tson`**, and the list says what it speaks beside it; absent is
`application/tson` alone. **A label is listed, never implied**: an endpoint answering JSON lists `application/json`,
`application/tson+json` or both, and Accept chooses among what is listed. Media types rather than an enum of encodings:
HTTP already negotiates them, so a new encoding is a registered name and never an edit to the layer. One set serves both
directions, as the codec does; a split between what an endpoint accepts and what it produces is a later field.

`Route.mediaTypes()` is the resolved set, folded to lower case: `application/tson` first for a non-document, then what
is listed. `Route.speaksJson()` is membership of either JSON label, and a demo builds the route's codec from it
(`codec.acceptingJson()`), keeping the description the one statement.
`TsonApiConformanceTest.everyEndpointSpeaksTheMediaTypesItLists` holds the server to it, each listed JSON label sent
and asked for, and JSON refused where none is listed.

**The closed world is checked at startup, the shape of `prepareToRead`** (`design/binding.md`): `TsonApiDescription`
refuses a media type no codec it serves can speak, so a description naming an encoding the server lacks fails to start
and never answers 500. "Can speak" is `application/tson`, `application/json` and `application/tson+json` for an endpoint
writing a TSON value; a document's body is bytes, so any type labels it. And it refuses a `!service.document` listing
none: `application/tson` is not implied for a document, and the base record declares the field optional, so only the
reader can require it.

**An endpoint whose body is not a TSON value is a `!service.document`**: the schema route (schema documents served as
published bytes, `application/tson`) and the validator's page (`text/html`). Its media types are the whole contract of
its body; it may still take a request record to place and name business errors, and its success is labelled, not
judged.

## Wiring, and what a server derives

**The wiring is two things**: this schema reachable from the `schemaSource` (`TsonApiSchema.publishedById()`), and
`TsonApiSchema.bindings()` in the config's one bind context beside the application's own, merged by
`TsonApiSchema.bindingsWith`. The map is explicit: a class in `io.ltr8.tson.http.api` binds only by being named there,
and a name both maps bind is refused, since within one closure a name means one thing. Map keys bind as `String`
whatever the key type, so `{http_verb => endpoint}` is `Map<String, Endpoint>`, never a `Map<HttpVerb, …>` holding
strings. A slot typed by a record with no bare form needs a Java type of that name: `Endpoint` is a sealed interface,
as abstract in Java as the base is in the schema.

**What a server publishes and warms comes from the description**, not a document beside it:

- `referencedSchemas()`: itself, the layer, and its imports transitively, minus the bundled standard library. That is
  the catalog, so a referenced-but-unpublished schema is impossible, not merely tested for.
- `boundClasses(bindings)`: what to `prepareToWrite`, from every request, response and error the endpoints name.
  Hand-listing lets a new response type go unwarmed, which costs only latency, so nothing reports it.

**`TsonApiCoverage` holds a server to that contract at startup.** `serving(verb, path)` claims a declared endpoint and
hands back its `Route`, so the path and media types come from the description wherever the framework can take them; an
undeclared endpoint is refused where the handler is written, and `requireComplete()` fails startup on a declared one
left unclaimed rather than 404ing for a client that believed the contract.

**An endpoint has no name, so a claim names the description's verb and template string**, which keeps it
framework-agnostic: the JDK demo serves `/{+schemaPath}` from a `createContext("/")` prefix, Javalin needs `/<path>` to
match across slashes, and Helidon uses `any()`. Comparing registered paths would need a per-framework translation
table, where a wrong entry is a route that silently never matches.

**"Everything declared is served" has a way out, and the reason is required.** `notServedHere(verb, path, reason)` makes
the exemption a greppable, justified decision where a boolean would be a hole; `@service.not_bound` is the same rule one
level up. Future helpers take this shape: a *named position with a rationale*, never a flag meaning "be sloppy".

## Smaller rules

**A path is a URI template**: RFC 6570 levels 1 and 2, absolute, checked at schema load. The demos declare
`{+schemaPath}`, the reserved expansion an identity path with slashes needs, where `{schemaPath}` would
percent-encode them. Its variables are fields of the endpoint's request record, so a parameter-only endpoint declares a
one-field record (`schema_ref => { schemaPath: text }`) placed from the path: there is no parameter list.

**Documentation is annotation on a key.** `@service.summary` and `@doc` before a verb key document that endpoint; an
exchange's sit before its key in the protocol, and a binding falls back to them (`Route.summary()`, `Route.doc()`).
**A schema entry has two annotation positions and they land in different places**: `@doc:"…" op => …` annotates the
**entry**, read from `entries.getAnnotations(name)`; `op => @doc:"…" { … }` annotates the **definition**. Reading only
the second and concluding `@doc` is dropped looks exactly like a library bug
(`UpstreamGapsTest.anEntrysTwoAnnotationPositionsLandInDifferentPlaces`).

**The positional form, and what it costs.** `service.resource` has exactly one bare required field, so §5.6 lets the map
stand in for it: `"/orders" => { POST => … }` for `{ endpoints: { POST => … } }`. A second required field withdraws the
positional form, and every document written in it stops parsing, so a new field there is optional or a new type.

**A projection is a value of its own, never a template's application.** An endpoint is no entry, so the one thing a
template could hold is a whole `!service.http` value, and a template's body is a record body ([TSON-SCHEMA] §5.10):
`fetching => <T> !service.http { "/orders/{id}" => { … response: T … } }` is refused at its declaration, the diagnostic
naming the remedy, one value per instantiation (`UpstreamGapsTest.aTemplateOverAnHttpValueIsRefused`). Each projection
is written as a `!service.http` value of its own, and `TsonApiDescription` finds every one by what the entry is, a
value of the namespace type `service.http`, never by its name.

**Deliberately absent**, each a decision: security schemes, response headers, links, callbacks, examples, tags,
servers, more than one success response, custom and WebDAV verbs (`service.http_verb` is a closed enum), and a declaration of
the transport's own refusals, which every endpoint has and the error boundary answers with a `problem`. `security` is
the one that would disqualify this for a real service. Adding any is an edit in place until this is published, and a
new identity after.

**The description is checked, not just written.** `TsonApiConformanceTest` resolves it through a schema source
fetching from the running server, proving that every referenced schema is published and every payload type exists,
then holds the server to what no compiler can know: a reply at the success status carries the response type (a
document, a media type it lists), one at a business error's fixed status that error, and any other is a 4xx or 5xx
`problem`.

**What builds on the protocol, and is not built.** `experiments/rpc/` is its wire form, `call` and `return` as
templates applied per exchange, next in the order of work and outside `tson-http` once it lands. `experiments/agent/`, a
plan of calls, is **future work** kept so a consumer that is not HTTP shapes the layer: do not build toward it, and do
not let a decision that is the agent's alone move the layer.

## Business errors compose `problem`

A business error (the request was schema-valid and the domain still said no) is **written by the handler, not
thrown**. It composes `problem` (§5.8) so it carries RFC 9457's members and adds its own, which means the error
boundary cannot produce it: the boundary only renders a `problem`, and a `sku_not_found` has a field `problem` does not.

These types belong in the **application's** schema, importing `problem.tn`: `tson-http` owns the transport envelope,
the service owns "SKU not found".

- **Imports are transitive, but name what you use anyway.** `order-1.tn` would get `text` through `problem.tn`; name
  `core.tn` too. A collision is judged by the *declaring schema's identity*, so naming a dependency twice is no error.
- **`errors` stays data-level.** A business failure carries `errors: []` and its own fields. They never co-occur
  anyway: validation is a gate, so business logic is not reached on a document that failed it.
