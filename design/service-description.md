# Describing a service (`meta-service.tn`, `io.ltr8.tson.http.api`)

Why a service description is a schema, what the meta layer declares, what the reader checks, and how a server is held
to it at startup. The orders examples under `tson-http/src/test/resources/io/ltr8/tson/http/api/orders/` are one real
document per use of the layer, reached through `OrdersExamples`; `experiments/rpc/` and `experiments/agent/` read
them there too.

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

An OpenAPI-shaped description of a service whose payloads are TSON, minus the embedded schema language OpenAPI needs
because JSON has none: an endpoint **references** a published, identity-addressed schema. It describes the interface
underneath as well as the HTTP over it — methods grouped into interfaces, and apis whose endpoints bind them.

**The description is a schema, not a document governed by one.** `meta-service.tn` is a meta layer whose two
constructors are namespace types, `interfaces` and `apis`; a service's description is governed by it:

```
orders     => !interfaces { orders => { place_order => { request: order  response: order  errors: [sku_not_found] } } }
orders_api => !apis { orders_api => { implements: [orders.orders]
  "/orders"        => !resource { POST => !binding { method: orders.orders.methods.place_order  status: 201 } }
  "/{+schemaPath}" => !resource { @safe GET => !document { request: schema_ref  media_type: "application/tson" } } } }
```

Methods are keys inside their interface, endpoints are values under a path and then a verb, so neither is a global
entry and nothing needs an invented operationId. `demo/schemas/orders-api-1.tn` binds an interface in an api;
`validate-api-1.tn` is web-service-only, every endpoint an inline `!operation` or `!document`; `OrdersExamples` holds
all three forms.

**The maps borrow namespaces TSON does not own.** A schema's own namespace is keyed by type names under [TSON-DATA]
§7.7's identifier grammar; an interface's `methods` by method names, a procedure-call namespace; an api's `resources`
by URL path templates; a resource's `endpoints` by RFC 9110's verbs; and a request record's fields are the namespace
`placement` maps URL segments, query keys and header names onto. A map's key type is how a document borrows a
namespace it does not govern, with the discipline from outside (`http_verb` is the registry's enum), and **one naming
role per borrowed namespace**, as the kernel has `type_name`, `field_name` and `param_name` over one grammar:
`method_name => identifier`, and where the authority's grammar is not TSON's identifier, a `pattern` on a text family
— `path_template`, and `header_name` held case-folded as RFC 9110 §5.1 compares it. A role enforces the grammar *and
names the namespace in the refusal* (`NameRoleTest`), where `type_name` would misname it; a namespace cannot be keyed
by `text`; and §8.2's hygiene refuses a mixed-script or confusable method key in a governed schema as in data. So
`place_order` is not a type but a name in the `orders` namespace, and a method's identity is
`(schema identity, qualified name)` — `…/orders-1.tn` and `orders.orders.methods.place_order` — unambiguous across
every service.

**What a namespace is: a keyed set of *declarations*, where a map is a keyed set of *values*.** A declaration
describes values and has instances of its own; a map's member *is* one (unique keys and reference by key fail as
tests — every map has unique keys, and a foreign key references a row). The pair recurs — schema and document,
interface and calls, columns and rows, registry and message, the kernel's `schema` and `field_map`. What the test
admits and Revision 38 does not take is a data-keyed namespace — an api's paths, a discriminated family, problem
types — each a map in TSON, so no qualified name enters an api and an endpoint is reached by location.

**A binding refers to a method rather than being one.** A method could be a type under plain `meta.tn` (its call
record's), with an operation refining it, but a schema fact stated as a field is per-instance: `verb: = POST` pins an
unmarked field, which [TSON-SCHEMA] §5.2 makes a *marker* each value must write, never injected, so every call
restates its verb and URL, and a refinement cannot relax the base's required `verb`. A reference has no such cost.

**And it is at the schema level by design, not only by necessity.** Data projects a schema and a request projects an
API, so an API's definition belongs above its documents; a `type_reference` value type, to write a description as
data and escape the meta layer's restrictions, was set aside — the restrictions come with the level. **The meta layer
owns the vocabulary its constructors use** — `http_verb`, `status_code`, `path_template`, `header_name` and
`media_type` are declared in `meta-service.tn`, not imported: an import stands at the importer's governing level, so
a meta layer imports no ordinary library and no ordinary schema imports a meta layer, both refused at schema load
(`UpstreamGapsTest.anImportStandsAtTheImportersGoverningLevel`). An ordinary schema wanting those terms declares its
own: `problem.tn`'s `status` is an `int32`.

**An entry applying a namespace type is a value, never a type.** Revision 38 has no `data` kind (§4.1); the kernel's
`namespace` is the constructor whose instances are maps, and a meta layer's vocabulary is records they hold. Naming
one where a type is expected is a resolver error ("a value and not a type"). **A namespace value is the map alone**:
it has no fields beside its keys, so a construct carrying both — an interface's `extends` beside its methods, an
api's `implements` beside its resources — is a record held under a key, one level down, which is why a reference
spells `orders.orders` (the entry, then its key), and a namespace type declaring such a field is refused
(`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`). The records inside — `interface`, `api`, `method`,
`resource`, `operation`, `binding`, `document` — are ordinary records and head no entry.

**Naming is the resolver's; relating is the reader's.** `request`, `response` and `errors` are typed `type_kind`,
`extends` and `implements` are `[ref<interface>]`, and a binding's or an exemption's `method` is `ref<method>` — so a
type nothing declares, a reference to the wrong kind of thing, and a method key an interface does not hold are
refused at schema load, with nothing declared by the bound classes; a qualified name says which interface it means,
so no name is ambiguous and nothing re-resolves bare names against an import list. What relates two things is
`TsonApiDescription`'s, checked when it reads a description, and **an unsound description has no model** — a startup
failure, as an undeclared type is, rather than a published contract no client can act on:

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
  an error from the response by status alone — two errors of one endpoint may pin the same status, which is fine on
  the wire since the value carries the error's tag, so a gateway sets `type` rather than relying on the status to
  discriminate;
- no two endpoints share a verb and a path, across every api a description holds.

**The wiring is three things**: this schema reachable from the `schemaSource` (`TsonApiSchema.publishedById()`), the
bound classes in `io.ltr8.tson.http.api`, and `TsonApiSchema.metaNameBinder()` on the config — **not** `bindings`,
which binds the data a schema describes; a meta layer's vocabulary is a separate namespace on purpose. The binder
finds a class by PascalCasing the schema type's name in that package, `@Typename` notwithstanding, so a public class
there named like a schema record binds to it, meant or not — hence `RequestPlacement`, not `Placement`. Map keys bind
as `String` whatever the key type, so `{http_verb => endpoint}` is `Map<String, Endpoint>`, never a
`Map<HttpVerb, …>` holding strings. A slot typed by a record with no bare form still needs a Java type of that name:
a sealed interface, as abstract in Java as the base is in the schema.

**What a server publishes and warms comes from the description**, not a document beside it:

- `referencedSchemas()` — itself, its meta layer, and its imports transitively, minus the bundled standard library:
  the catalog, so the description needs no bespoke route and a referenced-but-unpublished schema is impossible, not
  merely tested for.
- `boundClasses(bindings)` — what to `prepareToWrite`, from every request, response and error the endpoints name.
  Hand-listing lets a new response type go unwarmed, which costs only latency, so nothing reports it.

**`TsonApiCoverage` holds a server to that contract at startup.** `serving(verb, path)` claims a declared endpoint
and hands back its `Route`, so the path and encodings come from the description wherever the framework can take them;
an undeclared endpoint is refused where the handler is written, and `requireComplete()` fails startup on a declared
one left unclaimed rather than 404ing for a client that believed the contract.

**An endpoint has no name, so a claim names the description's verb and template string**, which keeps it
framework-agnostic: the registered path may differ and often must — the JDK demo serves `/{+schemaPath}` from a
`createContext("/")` prefix, Javalin needs `/<path>` because an identity path has slashes only the angle form matches
across, and Helidon uses `any()`. Comparing registered paths would need a per-framework translation table — the seam
only the unbuilt route table, registering from the description, would need — where a wrong entry is a route that
silently never matches, a worse failure than the one prevented.

**"Everything declared is served" has a way out, and the reason is required.** An endpoint documented before it is
built, or served by another process behind the proxy, is real; a check that cannot say so gets switched off
wholesale. `notServedHere(verb, path, reason)` makes the exemption a greppable, justified decision where a boolean
would be a hole; `exemptions()` reads it back, and `requireComplete()`'s failure names the method, so the way out is
found from the error. `not_bound` is the same rule one level up. Future helpers here take this shape, as upstream's
do (`TsonBindings.of` still takes your own `withDataBindContext`; `SchemaMetaNameBinder.extendedWith` is public): a
*named position with a rationale*, never a flag meaning "be sloppy".

**A path is a URI template.** A resource key is `path_template` — RFC 6570 levels 1 and 2, absolute, checked at
schema load — so the demos declare `{+schemaPath}`, the reserved expansion an identity path with slashes needs, where
`{schemaPath}` would percent-encode them. No fragment operator: a server never receives a fragment. Its variables are
fields of the endpoint's request record, so a parameter-only endpoint declares a one-field record
(`schema_ref => { schemaPath: text }`) placed from the path: there is no parameter list, and a URL segment carrying a
record is refused rather than stated as a limit.

**An endpoint whose body is not a TSON value is a `!document`**: the schema route (schema documents served as
published bytes) and the validator's page. It states a `media_type` where an operation states a response type, may
still take a request record to place and name business errors, and its success is labelled, not judged.

**An endpoint says which encodings it speaks** — `encodings?: [encoding]`, `encoding => !enum [TSON JSON]`; absent
means TSON alone, and a list is read beside TSON, which every endpoint speaks. The contract is an *encoding*, not a
media type: `application/tson+json` and `application/json` label one JSON encoding, `Accept` negotiates which a reply
carries, and a codec built `acceptingJson` admits JSON and answers in it. A demo builds the route's codec from
`Route.speaksJson()`, keeping the description the one statement;
`TsonApiConformanceTest.everyEndpointSpeaksTheEncodingsItDeclares` holds the server to it both ways.

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
servers, multi-media-type negotiation, more than one success response, custom and WebDAV verbs (`http_verb` is a
closed enum), and a declaration of the transport's own refusals — every endpoint has those, and the error boundary
answers each with a `problem`. `security` is the one that would disqualify this for a real service. Adding any is an
edit in place until this is published, and a new identity after.

**The description is checked, not just written.** `TsonApiConformanceTest` resolves it through a schema source
fetching from the running server, proving in one step that every referenced schema is published and every payload
type exists, then holds the server to what no compiler can know: a reply at the success status carries the response
type (a document, its media type), one at a business error's fixed status that error, and any other is a 4xx or 5xx
`problem`.

**What builds on the interface, and is not built.** `experiments/rpc/` is its wire form — `call` and `return` as
templates closed per method, their address (a document and a method) still open — next in the order of work, outside
`tson-http` once it lands. `experiments/agent/` is a plan of calls and the agent it compiles to: **future work**,
kept so the layer is shaped by a consumer that is not HTTP. Do not build toward it, and do not let a decision that is
the agent's alone move the descriptor.

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
