# Describing a service (`meta-service.tn`, `io.ltr8.tson.http.api`)

Why a service description is a schema and not a document, what the meta layer declares, what the reader checks, and how
a server is held to the description at startup. Current form only. The experiment it came from is
`experiments/meta-service/`.

**Invariants**

- Data can name a schema; only a schema can name a type. Anything whose job is to relate types is a schema.
- The description is a schema governed by `meta-service.tn`, resolved at startup (`tson.resolve(API)` then
  `TsonApiSchema.describedBy`); an unsound description fails startup.
- Naming is the resolver's (references, `type_kind`, `ref<…>`); relating is `TsonApiDescription`'s, in one reader.
- The description is the source of truth for what a server publishes (`referencedSchemas()`) and warms
  (`boundClasses(bindings)`). `TsonApiCoverage.requireComplete()` holds the server to it, and `notServedHere` with a
  reason is the only way out.
- A claim names the declared verb and path template, never the path the framework registers.
- `interface`, `api` and `resource` each have exactly one bare required field; adding a second withdraws the positional
  form and breaks every document written in it.
- A business error composes `problem` and is written by the handler, not thrown.

## Data can name a schema; only a schema can name a type

The rule behind several decisions here, and worth knowing before designing anything new.

Type-name resolution happens at type-ref positions in a **schema** document and nowhere else. A data document
can name a schema (`!!schema`) and select a type within the scope it names (a `!order` annotation), but a type
name in a value position is an inert token — no namespace is active to resolve it. §7.8's scoped family
confirms it: the sanctioned cross-schema mechanism carries a **value** with a visible scope switch, not a type
reference. A `scoped` position (`declared`, `extern`, `dynamic`, `extern_of<S>`, `extern_type<S, T>`) is where
the *data* chooses its type, by naming it — a bare `!type-ref` resolved in the governing schema, or a nested
`!!schema` plus a type-ref resolved there. It is not an `any`: a value naming no type at all is a validation
error, and what it names is checked in full.

Two consequences:

- **Anything whose job is to relate types must be a schema**, not a data document governed by one. That is why
  the service description is a **schema** governed by `meta-service.tn`, and why a description written as *data*
  cannot check anything it says.
- **The recurring `(schema identity, root type name)` pair is the data layer's workaround for this.**
  `describing(…)`, `readObjectAs(…)`, the `TSON-Schema` header plus a route-supplied type: two strings
  reassembled at every call site, because the thing they name cannot be referenced.

## Describing a service (`meta-service.tn`, `io.ltr8.tson.http.api`)

An OpenAPI-shaped description of a service whose payloads are TSON — minus the part OpenAPI mostly is. OpenAPI
embeds a schema language because JSON has none; TSON already has published, identity-addressed schemas, so an
endpoint **references** one. And it describes the interface underneath as well as the HTTP over it: methods
grouped into interfaces, and apis whose endpoints bind them.

**The description is a schema, not a document governed by one.** `meta-service.tn` is a meta layer whose two
constructors are namespace types, `interfaces` and `apis`; a service's description is a schema governed by it:

```
orders     => !interfaces { orders => { place_order => { request: order  response: order  errors: [sku_not_found] } } }
orders_api => !apis { orders_api => { implements: [orders.orders]
  "/orders"        => !resource { POST => !binding { method: orders.orders.methods.place_order  status: 201 } }
  "/{+schemaPath}" => !resource { @safe GET => !document { request: schema_ref  media_type: "application/tson" } } } }
```

Methods are keys inside their interface, endpoints are values under a path and then a verb, so neither is a global
entry and nothing needs an invented operationId. That is what makes `request: order` a *type reference the compiler
resolves* and `orders.orders.methods.place_order` a *reference to a value it resolves* — the property no data-shaped
design can have, because a data document can name a schema but cannot hold a reference. `demo/schemas/orders-api-1.tn`
is the both-shapes demo (an interface and an api binding it); `validate-api-1.tn` is web-service-only, every endpoint
an inline `!operation` or a `!document`.

**And it is at the schema level by design, not only by necessity.** Data is the projection of a schema; a request
and its data are the projection of an API — so an API's definition belongs at the schema level, above the documents
that project it. A `type_reference` value type in data was weighed as a way to write a description as data and avoid
the meta layer's restrictions, and set aside for that reason: the restrictions come with the level and are accepted.
**The meta layer owns the vocabulary its constructors use** — `http_verb`, `status_code`, `path_template`,
`header_name` and `media_type` are declared in `meta-service.tn` itself, not imported, since an import stands at the
importer's governing level — a meta layer imports no ordinary library and no ordinary schema imports a meta layer,
both refused at schema load (`UpstreamGapsTest.anImportStandsAtTheImportersGoverningLevel`) — and an ordinary schema
wanting the same terms declares its own: `problem.tn`'s `status` is an `int32`.

**An entry applying a namespace type is a value, never a type.** Revision 38 has no `data` kind (§4.1): a meta
layer's vocabulary is records held by namespace types, the kernel's `namespace` being the constructor whose instances
are maps. Naming such an entry where a type is expected is a resolver error ("a value and
not a type"). **A namespace value is the map alone**: it has no fields beside its keys, so a construct carrying both
— an interface's `extends` beside its methods, an api's `implements` beside its resources — is a record held under a
key, one level down, which is why a reference spells `orders.orders` (the entry, then its key), and a namespace type
declaring such a field is refused (`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`). The records inside —
`interface`, `api`, `method`, `resource`, `operation`, `binding`, `document` — are ordinary records and head no entry.

**Naming is the resolver's; relating is the reader's.** `request`, `response` and `errors` are typed `type_kind`,
`extends` and `implements` are `[ref<interface>]`, and a binding's or an exemption's `method` is `ref<method>` —
so a type nothing declares, a reference to the wrong kind of thing, and a method key an interface does not hold are
refused at schema load, with nothing declared by the bound classes, and a qualified name says which interface it
means, so no name is ambiguous. What relates two things is `TsonApiDescription`'s, checked when it reads a
description, and **an unsound description has no model** — a startup failure, as an undeclared type is:

- a binding's method belongs to an interface its api implements, through `extends`;
- the `implements` claim holds — every method of every implemented interface is bound, or exempted in `not_bound`
  with a reason — held per `InterfaceMethod` (interface, method), never per name, since two interfaces may both
  declare `get_order`;
- `extends` has no cycle, the one thing a reference cannot rule out; and it is a reference, not a composition, so an
  inherited method is named where it is declared — `&` over namespace values is not proposed, composition being over
  types, and may be revisited with a reference in a data document;
- each request record's fields are placed exactly once over the path, the query, the headers and the body
  (`RequestPlacement`) — a `{segment}` binds the field of that name, `query` and `headers` name fields, `body` names
  one field carried unwrapped, and the remainder is the body, or the query for a GET or HEAD; a field in the path,
  query or a header must be a scalar (a query or header value may repeat, so an array of scalars is admitted there);
- the verb agrees with what the method says about itself — `@safe` and `@idempotent` on the method's key, GET safe,
  PUT and DELETE idempotent — and a response-less endpoint answers a status that carries no body;
- each business error fixes its status (`sku_not_found => problem & { status: = 404 … }`), which is how a client tells
  an error from the response by status alone;
- no two endpoints share a verb and a path, across every api a description holds.

What a data-shaped predecessor did instead — resolving bare names against an import list, which reimplemented an
upstream namespace bug twice — is exactly what the references remove; what is left is relational, and stays in one
reader rather than in every consumer.

**The wiring is three things**: this schema reachable from the `schemaSource` (`TsonApiSchema.publishedById()`), the
bound classes in `io.ltr8.tson.http.api`, and `TsonApiSchema.metaNameBinder()` on the config — **not** `bindings`,
which binds the data a schema describes. A meta layer's vocabulary is a separate namespace on purpose. The binder
finds a class by the schema type's name in that package, so a public class there named like a schema record binds
to it whether meant to or not — which is why the computed placement is `RequestPlacement` and not `Placement`.

**Resolving the description is a startup check**, which is why each demo calls `tson.resolve(API)` and then
`TsonApiSchema.describedBy`: an unsound description fails the server's startup rather than being published as a
contract no client can act on. And because it is a schema, the catalog serves it like any other — no bespoke route.

**The description is the source of truth for what a server publishes and warms**, not a document beside it:

- `referencedSchemas()` — itself, its meta layer, and its imports transitively, minus the bundled standard
  library. That *is* the catalog, so a referenced-but-unpublished schema is impossible rather than merely tested for.
- `boundClasses(bindings)` — what to `prepareToWrite`, from every request, response and error the endpoints name.
  Hand-listing is how a response type added to a description never gets warmed: nothing connects the two lists, and
  the omission costs only latency, so nothing reports it.

**`TsonApiCoverage` holds a server to that contract at startup.** `serving(verb, path)` claims a declared endpoint
and hands back its `Route` — so the path and the encodings come from the description wherever the framework can take
them — and `requireComplete()` fails if anything declared went unclaimed. Both directions are caught: an endpoint the
description does not declare is refused where the handler is written, and a declared one with no handler fails
startup rather than 404ing for a client that read the contract and believed it. Whether every *method* has an
endpoint is a different question, about the description rather than the server, and the reader answers it.

**A claim names the declared verb and path, and that is what keeps it framework-agnostic.** An endpoint has no name
of its own, so a handler claims it by both, spelled as the description wrote them. That is a claim on the
description's template string, not a comparison with the path the framework registers, which may differ and often
must — the JDK demo serves `/{+schemaPath}` from a `createContext("/")` prefix, Javalin needs `/<path>` because an
identity path has slashes that only the angle form matches across, and Helidon uses `any()`. Comparing registered
paths would need a translation table per framework, and a wrong entry there is a route that silently never matches:
a worse failure than the one being prevented.

**The opinion has a way out.** "Everything declared is served" is an opinion, and a fixed one would be the wrong kind
of helper — an endpoint documented ahead of being built, or served by another process behind the same proxy, are
real cases, and a check that cannot express them gets switched off wholesale, taking the endpoints it was right about
with it. `notServedHere(verb, path, reason)` is the way out and the reason is required: an exemption somebody has to
justify in a greppable string is a decision, where a boolean is a hole. Readable back through `exemptions()`, and
`requireComplete()`'s failure message names the method, so the way out is discoverable from the error rather than
the Javadoc. The description's own `not_bound` is the same rule one level up, for a method an api implements and
does not bind.

**This is the shape to copy for any future helper here**, and it is upstream's: `TsonBindings.of` is opinionated but
`withDataBindContext` still takes a context you built yourself, and `SchemaMetaNameBinder.extendedWith` is public so
`metaNameBinder` is not a black box. The way out is a *named position with a rationale*, not a flag meaning "be
sloppy". An opinionated helper is fine. A fixed opinion is not.

**A path is a URI template.** A resource key is `path_template` — RFC 6570 levels 1 and 2, absolute, checked at
schema load — so `{+schemaPath}`, the reserved expansion an identity path with slashes in it needs, is what the demos
declare, where `{schemaPath}` would percent-encode the slashes. The fragment operator is not admitted, since a server
never receives a fragment. Its variables are fields of the endpoint's request record, so a parameter-only endpoint
declares a one-field record (`schema_ref => { schemaPath: text }`) and places it from the path: there is no parameter
list, and a URL segment carrying a record is refused rather than stated as a limit.

**An endpoint whose body is not a TSON value is a `!document`**: the schema route (schema documents served as the
bytes they were published as) and the validator's page. It states a `media_type` where an operation states a response
type, may still take a request record to place and name business errors, and its success is labelled, not judged.

**An endpoint says which encodings it speaks** — `encodings?: [encoding]`, `encoding => !enum [TSON JSON]`, absent
meaning TSON alone and a list read beside TSON, which every endpoint speaks. An *encoding* and not a media type,
because that is the contract: `application/tson+json` and `application/json` are two labels for the one JSON
encoding, and which a reply carries is `Accept`'s to negotiate. It covers both directions, since a codec built
`acceptingJson` admits JSON and answers in it. `Route.speaksJson()` is what a demo builds the route's codec from, so
the description stays the one statement; `TsonApiConformanceTest.everyEndpointSpeaksTheEncodingsItDeclares` holds
the running server to it both ways.

**Documentation is annotation on a key.** `@summary` (the short form, declared in the layer) and `@doc` before a verb
key document that endpoint; a method's sit before its key in the interface, and a binding falls back to them —
`Route.summary()` and `Route.doc()`. **A schema entry has two annotation positions and they land in different
places**: `@doc:"…" op => …` annotates the **entry**, read from `entries.getAnnotations(name)`; `op => @doc:"…" { …
}` annotates the **definition**. Both are retained, so reading only the second and concluding `@doc` is dropped is a
mistake that looks exactly like a library bug. Pinned by
`UpstreamGapsTest.anEntrysTwoAnnotationPositionsLandInDifferentPlaces`.

**THE POSITIONAL FORM, AND WHAT IT COSTS.** `interface`, `api` and `resource` each have exactly one bare required
field, so §5.6 lets the map stand in for it — `orders => { place_order => … }` for `{ methods: { … } }` — and the
demos use it. Adding a second required field to any of the three withdraws the positional form from that type, and
every document written in it stops parsing, so a new field there is optional or it is a new type.

**A templated api is applied by name.** An endpoint is a value two maps down, so it is no entry and is not applied on
its own; a template holds a whole `!apis` value. `fetching => <T> !apis { fetch => { … response: T … } }` declares,
and `orders => fetching<order>` resolves: [TSON-SCHEMA] §8.2 makes a declaration naming an application *that
application's entry*, so `orders` is itself the namespace value with the argument substituted into its endpoints.
`TsonApiDescription` finds it as it finds a written-out one, by what the entry is. Pinned by
`UpstreamGapsTest.aTemplatedApiIsAppliedByName`.

**Deliberately absent**, each a decision: security schemes, response headers, links, callbacks, examples, tags,
servers, multi-media-type negotiation, more than one success response, and a declaration of the transport's own
refusals — every endpoint has those, and the error boundary answers each with a `problem`. `security` is the one
that would disqualify this for a real service. Adding any is an edit in place until this is published, and a new
identity after.

**The description is checked, not just written.** `TsonApiConformanceTest` fetches it **from the running server**,
resolves it through a schema source that fetches from that same server — which proves in one step that every
referenced schema is published and every payload type exists — and then holds the server to what no compiler can
know: that a reply at the success status carries the response type (or, for a document, its media type), one at a
business error's fixed status carries that error, and any other is a 4xx or 5xx `problem`.

Still not built: the route table proper (registering from the description rather than claiming against it), which
is where that per-adapter path seam would finally have to exist.

**The experiment it came from.** `experiments/meta-service/` keeps what probes the layer from directions the shipped
suite does not: `rpc.tn`, the interface's wire form (whose address — a document and a method — is still an open
question there), the agent layers, and the findings the map design rests on. **The agent sketches are future work**:
`agent.tn` and `agent-vm.tn` are there to press on the interface layer from a direction that is not HTTP, so that the
layer is shaped by more than one consumer; they are kept resolved and probed for that and nothing else. Do not build
toward them, and do not let a decision that is the agent's alone move the descriptor.

## Business errors compose `problem`

A business error — the request was schema-valid and the domain still said no — is **written by the handler, not
thrown**. It composes `problem` (§5.8) so it carries RFC 9457's members and adds its own, which means the error
boundary cannot produce it: the boundary only knows how to render a `problem`, and a `sku_not_found` has a field
`problem` does not.

These types belong in the **application's** schema, importing `problem.tn` — `tson-http` owns the transport
envelope, the service owns "SKU not found". One rule that costs time otherwise:

- **Imports are transitive, but name what you use anyway.** A name reaches you through what you import, so
  `orders-errors-1.tn` would get `text` through `problem.tn` without saying so. Name `core.tn` too: a
  collision is judged by the *declaring schema's identity*, not by how many routes reach it, so naming a
  shared dependency twice is redundant rather than an error.
- **`errors` stays data-level.** A business failure carries `errors: []` and its own fields. They never
  co-occur anyway — validation is a gate, so business logic is not reached on a document that failed it.


