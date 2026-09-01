# `meta-service-1.tn` -- interfaces and operations in one meta layer

**Status: experiment.** Nothing here is served, published or depended on. `meta-http-1.tn` remains the meta layer
the demos use.

## The question

`meta-http-1.tn` describes an HTTP API: a schema governed by it declares `!operation` entries whose `request`
and `response` are type references the compiler resolves. Underneath an HTTP API there is usually a
transport-neutral interface -- methods with a request, a response and errors -- and a service may want to
declare just that, or just its web service, or both, with an HTTP operation being one *projection* of a method.
Can one meta layer hold all three, and what does the third actually need?

## The sketch

[`meta-service-1.tn`](meta-service-1.tn), with three uses as real documents under [`examples/`](examples/)
(guide: [`examples.md`](examples.md)). Two entries a governed
schema writes, and both are **maps**:

- `interface => ~data & { extends: [type_name]?  methods: {method_name => method} }` -- a named, documented map
  of methods. A method is a map *value* under its name, so the name is scoped to its interface (two interfaces
  may both declare `place_order`), a `@doc` before the key documents it, and the `!method` tag is optional under
  the typed slot. `extends` names other interfaces whose methods this one also has.
- `api => ~data & { implements: [type_name]?  not_bound: {method_name => text}?  resources: {path_template => resource} }`
  and `resource => ~data & { endpoints: {http_verb => endpoint} }` -- resources keyed by **path**, each holding
  endpoints keyed by **verb**: OpenAPI's `paths`, arrived at from the key types. A path key is data, so
  `/orders/{id}` needs no identifier minted from it and an endpoint needs no invented name.
- `endpoint => ~data & placement & { status ~ 200 }`, with `operation => ~endpoint & signature & {…}`
  and `binding => ~endpoint & { method: method_name  interface: type_name? }` deriving from it at constructor
  level. An `!operation` carries its signature inline, for an api with no interface behind it; a `!binding`
  borrows a method's. The tag says which, and it is not optional: the base has no data of its own to bind, so an
  untagged value is refused by the resolver naming both subtypes -- the one-or-the-other rule with no reader
  behind it. The verb and path are the keys an endpoint sits under. (A choice `(operation | binding)` and a field
  group `( method | signature )` were both measured to work; the supertype won because the base is abstract for
  free, shared vocabulary is inherited rather than composed twice, and a new kind of endpoint is one more
  derivation.) Documentation is annotation on the verb key -- `@summary` the short form, declared here as the kernel
  declares `documentation`, and `@doc` the long -- not fields: an endpoint is a map value, and a map key carries
  annotations.
- `signature` and `placement` are plain records composed into the constructors above. `signature` is the
  transport-neutral contract (request, response, errors, `safe`/`idempotent`, the two stream flags, defined
  below). `placement` is **where the interface and the web service over it are allowed to look different**: a
  method has one request record, and the operation distributes its fields -- a `{segment}` in the path binds a
  field, `query` names query fields, `headers` maps a field to its header, `body` names one field carried
  unwrapped as the whole body, and the remainder is the body (or the query, for a GET/HEAD or once `body` named
  a field). Every field lands exactly once, and a field in the path, query or a header must be a scalar. There
  is no parameter list: a parameter-only endpoint declares a one-field request record and binds it from the
  path. (gRPC's HTTP transcoding is the precedent.)
- `implements` is a **claim** the reader holds the api to: every method of every implemented interface (through
  `extends`) has an operation naming it, or an entry in `not_bound` giving the reason -- the
  `TsonApiCoverage.notServedHere` rule, derived from the description rather than from handler registration.
- An error type pins the status it inherits from `problem` (`sku_not_found => problem & { status: = 404 … }`),
  which is how an operation's `errors` get one without a `responses` list.

Everything that relates the two maps -- a method name, an `implements`, an `extends`, a `{segment}` -- is an
identifier the resolver does not check, because a `kind: DATA` entry cannot be referenced and a map key is data.
`Routes` in the Java is the reader that checks all of it at startup: it resolves each operation's signature,
computes its `Placement`, and holds the `implements` claim. The three probes:

- `MetaServiceSketchProbe` -- the sketch resolves and its constructs behave as assumed: a `~data` constructor
  with a record mixin and no body; a fixed value in a constructor body (`request_stream: = false`) a governed
  schema cannot lift; an error type's `REQUIRED_FIXED` status readable from the resolved schema; and the rule the
  design bends around, a `~data` *instance* named as a type refused at load.
- `InterfaceMapProbe` -- the finding the map design rests on, below.
- `ExamplesProbe` -- the documents under `examples/` resolve against the sketch and both apis read into route tables.
- `ApiProbe` -- an interface and the web service that maps it, through `Routes`: the placement of each request
  record; an inline-only api; the `implements` claim failing and then exempted; `extends` walked; the tag rule
  the resolver enforces; a typo the resolver passes and the reader catches; an ambiguous name needing
  `interface:`; a container in a path; the three borrowed grammars at their keys; and the method-as-type
  alternative kept for comparison.
- `SupertypeProbe` -- the mechanism `!binding` rides on: a derived constructor's instance admitted at its
  base-typed slot, the base abstract for free, its constraints inherited.
- `NameRoleProbe` -- what a naming role buys at a map key, and the hygiene gap below.

**Two binder facts met on the way**, both worth knowing before writing a bound record for a `~data`
constructor: the binder finds a class by PascalCasing the type name (`interface_of_methods` →
`InterfaceOfMethods`), `@Typename` notwithstanding; and it hands a map's *keys* back as `String` whatever the
schema's key type, so `{http_verb => endpoint}` binds to `Map<String, Endpoint>` and a `Map<HttpVerb, …>`
component would hold strings and lie about it. A third: a slot typed by a constructor needs a Java type of that
name even if nothing constructs it bare -- a sealed interface is the right thing to put there, and it makes the
base as abstract in Java as it is in the schema.

## The real problem, and the two ways it can be modelled today

"Both" in the sense that matters is not one entry carrying method and binding at once. It is `place_order`
declared once on the interface and a *separate* entry -- possibly in another document -- saying "POST /orders is
that method". That second entry has to **refer** to the first, and [TSON-SCHEMA] §4.1 makes a `kind: DATA` entry
something that can be declared and applied but never named: field type, element, variant, argument,
composition operand, refinement source -- all refused. `operation => method & { … }` is refused for the same
reason. `ApiProbe` measures the two shapes that work:

**A. Relate by identifier; the reader checks it.** This is what the sketch does: `POST => !operation { method:
place_order … }` inside an api that `implements: [orders]`. Resolves, reads back, and a typo (`method:
plaec_order`) loads clean -- the resolver treats a `type_name` slot as data. The check is a lookup in the
implemented interfaces at startup, the same tier as `TsonApiCoverage`, and `Routes` is that check. Keeps "an
operation is not a type"; becomes a compiler check the day the kernel has a reference form for data entries.
The map shape softens the cost: the names being related are keys inside two entries the resolver did build, so
"is `place_order` a method of `orders`" is one map lookup, not a namespace search.

**B. A method is a type -- the type of its call record.** Under plain `meta.tn`, no meta layer at all:

```
method => <Req, Resp> { request: Req  response: Resp?  safe: boolean ~ false  idempotent: boolean ~ false }
http   => { verb: http_verb  path: text  status: status_code ~ 200 }

place_order  => method<order, order> & { errors: [sku_not_found]? }
create_order => place_order & http & { verb: = POST  path: = "/orders"  status: = 201 }
```

The operation IS-A its method, the binding reads back as `REQUIRED_FIXED` fields, and a plan step is a value
of the method type -- `!create_order { request: { … } }` reads, `verb: GET` on it is refused. Two things it
taught: `place_order => method<order, order>` alone is an alias to an instantiation with no body to compose
with (give it `& { … }`); and schema facts stated as fields are injected into every instance, so each value
carries its own URL. That cost is what the third option removes.

**C. The spec change.** The kernel's own 2×2 -- record (names → data), schema (names → declarations), map
(data → data) -- has an empty cell: a keyed set of declarations whose keys are values, `!api { "/orders" => … }`.
With a namespace as a value, an `!interface { place_order => method<…> }` is a scoped set, an api member is
keyed by route and needs no invented name, `&`/`^`/`-` already mean extends/tighten/subset on it, and templates
over interfaces are the CRUD payoff at the right level. Filed as tson-java `SPEC-FEEDBACK.md` *"a namespace
should be a value -- the kernel's 2×2 has an empty cell"*, with the argument in full.

## What the maps are: borrowed namespaces

The observation the design now rests on. A `!schema` describes a namespace -- the type system's, keyed by type
names under [TSON-DATA] §7.7's identifier grammar. An `!interface` and an `!api` describe namespaces too, and
**their key types are naming authorities TSON does not own**:

| entry | keys are | whose namespace |
|---|---|---|
| `!schema` | type names | TSON's own (§7.7) |
| `!interface` | method names | a procedure-call namespace -- what gRPC and JSON-RPC address as `service.method` |
| `!api` | URL path templates | RFC 3986's |
| `!resource` | verbs | RFC 9110's method registry, via IANA |
| a request record | field names | the record's own -- the namespace `placement` maps the URL's segments, query keys and header names onto |

`place_order` is not a type; it is a name in the `orders` namespace that a method description sits at. That is
plainest in the api, where `"/orders"` is visibly a name in the URL path namespace. The map's key type is how a
TSON document borrows a namespace it does not govern and says what lives at each name -- which is why
`http_verb` is an enum copied from a registry and a path key is `text`: the discipline comes from outside.

**Separating the names: one naming role per borrowed namespace.** The kernel already shows how: `type_name`,
`field_name` and `param_name` are each `=> identifier` -- roles over one grammar, so a rule stated on a role
reaches every position of that kind. The sketch declares one per namespace it borrows and uses it as the map's key
type (`placement` already used the kernel's `field_name` for the record's; method names were filed under
`type_name` until this was seen):

```
method_name   => identifier                                             an interface's keys
path_template => !text ^ { pattern: "/([^/{}]+|\\{[A-Za-z_][A-Za-z0-9_]*\\})*" }
                                                    an api's keys: an RFC 3986 path with {segments}
header_name   => !text ^ { pattern: "[!#$%&'*+.^_`|~0-9A-Za-z-]+" }    a `headers` value: an RFC 9110 token

interface => ~data & { extends: [type_name]?  methods: {method_name => method} }
api       => ~data & { … resources: {path_template => resource} }
```

`NameRoleProbe` measures what a role buys at a map key, and `ApiProbe` pins the three in the sketch: the identifier
grammar, enforced, with the refusal
naming the role -- *"'method_name': 'place order': U+0020 at index 5 cannot appear in an identifier"* -- where a
`text` key accepts anything and `type_name` enforces the same grammar while misnaming the namespace. For the
URL and header namespaces the authority's grammar rides as a `pattern`, I-Regexp over the whole token, the
same device `deployment-1.tn` uses for a script name. What stays a `type_name` honestly is the *interface's own*
name (`orders`) and `implements`/`extends`: an interface is still an entry in the type namespace, which is the
seam the `namespace` kind would move.

Four things follow.

**It says what the `data` kind was.** `operation => ~data & { … }` declared "an entry in the type namespace that
is not a type": a foreign namespace's member filed in the wrong map, which is exactly why it could be declared
but never referenced -- the type namespace has no reference form for non-types because it should hold none. The
map shape moves the members out. What remains under `~data` is `orders` and `orders_api`, the *namespaces
themselves*, and only because `data` is the one non-type kind there is. So the fifth kind the spec-feedback
proposal ("a namespace should be a value") reaches for is not `data` but `namespace`; `data`'s one surviving job
is to hold one, and it retires the day `namespace` exists.

**It says precisely why the resolver cannot check the relations.** `implements`, `method: place_order`, `{id}` in
a path, `body: order`, `headers: { idempotency_key => … }` are all *cross-namespace references* -- api to
interface, interface to type, URL to a record's fields -- and the resolver checks references within the one
namespace it knows. `Routes` and `Placement` are the checks for the others. A resolver that knew namespace
*kinds* could check `method: place_order` as it checks `request: order`: a namespace has a key type and a member
bound, and a reference is a (namespace, key) pair whose validity is that namespace's business. That is the
mechanism behind the spec-feedback proposal, stated.

**It gives a method an identity without inventing one.** A type namespace is content-addressed; an interface
published at its `!!id` is too. A method's global name is therefore `(interface identity, key)` --
`…/orders-1.tn` and `place_order` -- unambiguous across every service, with no `operationId` and no flat-namespace
collision. It is what an agent plan step should carry and what a route table could key on. Not a fragment:
[TSON-DATA] §2.2.1 keeps fragments out of identity, and a key is a member, not a second document.

**It exposes two things as unfinished**, recorded with the other open questions below: the URL namespace is
hierarchical and a resource keyed by full template is flat over it (the qualified-name question wearing a
slash); and a data document can bind only the type namespace (`!!schema`, `!name`), so a plan document cannot
tag a step `!place_order` -- it writes `method: place_order` as data, which is the one-hop rule doing exactly
what it says.

## Open questions, kept here

**Which namespace may a document bind?** `!!schema` binds the type namespace and `!name` resolves against it,
one hop. A plan or batch document wants to bind an *interface* -- to say `!place_order { request: { … } }` and
have the step checked against the method -- and cannot: `place_order` is a key inside `orders`, not a type
name. `method: place_order` as data is the workaround. Whether a document may bind a namespace of another kind,
or more than one, is a spec question; the URL namespace being hierarchical (`/orders/{id}/items` under
`/orders/{id}`) while a resource key is a flat template is the same question as `orders.place_order`, and one
answer should serve both.


**A framing for a stream of documents.** `signature` defines `request_stream`/`response_stream` as a sequence
of documents of the declared type, count unknown, each complete as it arrives -- a property of the call, not of
the type. The format has no spelling for that: a document is one value and the readers reject trailing content,
so a stream needs an outer framing. Three candidates, in the order this experiment would rank them:

1. **`application/tson-seq`, on RFC 7464's model** (`application/json-seq`: texts delimited by RS, U+001E). Each
   element is a complete TSON document, self-describing or governed by the method's schema; truncation is
   detectable per element; and an error mid-stream is a `problem` document in the sequence, so the interface's
   `errors` apply per element with no new machinery. If streaming is in scope for the format at all, this is the
   proposal, and [TSON-DATA] §7.1 -- where the media type lives -- is where it would be filed. **Not filed:**
   the experiment is confined until that question is answered.
2. **SSE or multipart** -- real HTTP framings, each element a document. `response_stream` only, and a second
   specification's event model comes with it.
3. **One array, consumed lazily** through the existing `TsonDataStream` events. No new format, but it is the
   "a stream is a list" misreading made official: an incomplete stream is an invalid document, and nothing can
   follow a failed element.

What follows for the HTTP projection once a framing exists: `response_stream` is one media type on the
response; `request_stream` over HTTP/1.1 is a chunked request body a server can only answer after; the
bidirectional shape needs HTTP/2 or a WebSocket and is probably not `operation`'s business. `operation` pins
both flags `false` in this version for that reason.

A naming point recorded rather than taken: two booleans admit "both true" as the bidirectional shape by accident
of encoding. If that shape is not meant to be expressible over HTTP, a `shape: UNARY | SERVER_STREAM |
CLIENT_STREAM | BIDI` on the signature says which four and lets `operation` pin one field.

**An interface as a map of methods works today -- and §4.1 reads as if it should not.** `InterfaceMapProbe`
measures `interface => ~data & { methods: {type_name => method} }`, written `orders => !interface { place_order
=> !method { … } }`: it resolves, the values are built as methods, the tag is optional under the typed slot, an
unresolved reference inside a value is caught at load (through the owner's `references()`), and a `@doc` before
a map key survives into `AnnotatedMap.getAnnotations`. That is most of what the flat namespace was costing --
methods are keys inside their interface, so two interfaces may both declare `place_order`; the interface is one
entry with a name and a doc -- with no reference to a data entry anywhere. The question for the spec: [TSON-SCHEMA]
§4.1 refuses "naming a `kind: DATA` entry" as an element type, and a map's value type is one; the implementation
admits the DATA *constructor* there and refuses only an *instance*. The kernel's own `top`-typed slots hold DATA
instances, so the implementation is consistent with the kernel; whether "entry" was meant to include a
constructor is what to ask. "What the maps are", above, is the argument for answering it with a `namespace`
kind rather than by widening `data`. **Not filed** -- confined here with the rest.

**Name hygiene does not reach map keys.** Measured in `NameRoleProbe`: two confusable method names in one
interface (`admin`, `аdmin`), or a mixed-script one, are admitted under `type_name` and `method_name` alike,
where the same names as two fields of a record or two declarations of a schema are refused under the default
identifier policy. An interface's method map is a naming scope in every sense [TSON-DATA] §8.2 means -- names a
reader must tell apart -- and once methods live in maps rather than as declarations, the spoofing surface §8.2
exists for moves with them. Whether the fix is the implementation applying the identifier policy to
identifier-role-keyed maps, or the spec naming such a map a scope, is the question; the probe is written to fail
when either lands. **Not filed** -- confined here with the rest.

## Files

- `meta-service-1.tn` -- the sketch, its reasoning in its own `@doc`s.
- `examples/` -- real documents: the shared types and errors, the interface only, the web service only, and
  both, each at its own `!!id`; `examples.md` is the guide to them and the placement table. `ExamplesProbe`
  resolves every file and runs both apis through `Routes`.
- `java/…/experiment/metaservice/` -- the bound records the `~data` constructors need (`Method`, `Interface`,
  `Endpoint` -- a sealed interface, the base -- `Operation`, `Binding`, `Resource`, `Api`, `HttpVerb`;
  `Signature`, `InterfaceOfSignatures`, `ByTypeName`, `ByMethodName`, `ByText` for the probes that need their
  own constructors), `Placement` and `Routes` (the reader-side checks the maps need), and five probes. Field names match the
  schema's exactly (`request_stream`), because binding does no case conversion; a Java keyword as a field name
  (`extends`, `interface`) is renamed with `@Field`.
