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
- `endpoint => placement & { status ~ 200 }`, with `operation => endpoint & signature` and `binding =>
  endpoint & { method: method_name  interface: type_name? }` composing it. An `!operation` carries its
  signature inline, for an api with no interface behind it; a `!binding` borrows a method's. The tag says
  which, and it is not optional: the base binds to a type with no bare form, so an untagged value is refused
  naming both subtypes -- the one-or-the-other rule with no reader behind it. The verb and path are the keys an
  endpoint sits under. (A choice `(operation | binding)` and a field group `( method | signature )` were both
  measured to work; the supertype won because the base is abstract, shared vocabulary is inherited rather than
  composed twice, and a new kind of endpoint is one more composition.)
- **Only `interface` and `api` are `~data &` constructors** -- the two entries a governed schema writes, and
  the only things that may head one (`x => !method { }` is refused: not a constructor). Everything inside them
  is an ordinary record, because it only ever appears as a map value; `method => signature & {}` takes the empty
  body to be a type of its own rather than an alias that would flatten to `signature`. So no `kind: DATA` entry
  is named at a type slot anywhere in the design, §4.1 has nothing to say about it, and a `resource` needs no
  tag under its path key. Documentation is annotation on the verb key -- `@summary` the short form, declared here as
  the kernel
  declares `documentation`, and `@doc` the long -- not fields: an endpoint is a map value, and a map key carries
  annotations.
- `signature` and `placement` are plain records composed into the constructors above. `signature` is the
  transport-neutral contract: request, response, errors. What a caller composing calls needs to know beyond that
  -- `@safe`, `@idempotent` -- is a bare annotation on the method's key, declared here as the kernel declares
  `annotation`, because it describes the method rather than its request or response; the HTTP projection checks
  its verb against them and never derives one from the other. `placement` is **where the interface
  and the web service over it are allowed to look different**: a
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
  with a record mixin and no body; an error type's `REQUIRED_FIXED` status readable from the resolved schema; and the
  rule the
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
- `RpcProbe` -- `rpc-1.tn` and the wire schema resolve; a call is typed by the interface's own types and a bad
  request is refused at its field; a return carries the declared response or error with its status pin enforced,
  and exactly one outcome.
- `AgentProbe` -- both agent layers resolve; a plan of references and a compiled agent read, the `@disjoint`
  instruction tag-free; a constant is the same gap; a malformed step name is refused by the `name` role.

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
map shape moves the members out, and they are ordinary records there. What remains under `~data` is `orders` and
`orders_api`, the *namespaces themselves*, and only because `data` is the one non-type kind there is. So the fifth
  kind the spec-feedback
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

## Direction: interface, api, agent

Three projections of one interface, and the order they arrive in.

- **The interface is the canonical layer, and its wire form is the RPC packet.** [`rpc-1.tn`](rpc-1.tn):
  `call => <Req> { … }` -- address `(interface !!id, method key)`, correlation `id`, `deadline`, opaque `meta`,
  the method's `request` -- and `return => <Resp, Err> { … }`, exactly one of `response`, a declared `error` (a
  typed value composing `problem`, its status pinned on the type) or a `fault` (the transport's or processor's
  own `problem`, its `type` from the same closed set the HTTP problem types use). Both are **templates, closed
  once per method by a wire schema derived from the interface** -- `place_order_call => call<new_order>`,
  `place_order_return => return<order, sku_not_found>`, a choice of errors where a method declares several
  ([`examples/orders-wire-1.tn`](examples/orders-wire-1.tn), written by hand to show what a generator emits) --
  so a packet is `!place_order_call { … }`, a fully typed document whose request the resolver checks against the
  interface's own types. In-process Java dispatch is the first transport: an `Orders` implementation wires to
  the interface once, and everything else translates into a call.
- **The api is a gateway.** An `!api` describes a translator, not a server: `Routes` is its table, and
  `Placement` run in reverse -- `id` from the path segment, `order_query` from the query string, `order` from
  the body beside `Idempotency-Key` -- is its request path, producing a `call`. Which is why a generic gateway
  process can read an `!api` and an RPC address and need no code per service.
- **The agent is three layers, as a compiler has** -- [`agent-1.tn`](agent-1.tn) and
  [`agent-vm-1.tn`](agent-vm-1.tn), below. A plan SOURCE (a surface grammar, still to write) is read to a
  `plan` (the AST: steps in order, references parsed to selectors, constants folded, `or` a field because it
  is executed), which compiles deterministically to an `agent` (a constant pool, write-once memory slots, and
  straight-line stack code with no jumps) that a server admits, verifies in one forward pass against the
  pinned interface, and executes -- each CALL being one `call` of rpc-1.tn. The likely deployment is a
  gateway agent processor whose calls are RPC to the box hosting each interface; the api gateway does the same
  for one call.

### The agent: three layers

`agent-1.tn` is the plan, resolved -- named as a resolved schema is named against its source: the surface is
plan source, this is the plan, and an `agent` is what it compiles to. Everything the surface leaves implicit is
structural here: a reference is a `selector` (a step, then `segment`s -- `field`, `index`, or a `filter`),
distinguished from a constant by shape; an argument is an `arg` tree whose canonical form folds constants
maximally, so `record` and `array` appear only on the spine above a reference; `or` is a step's failure
substitute, a field because it is executed. `agent-vm-1.tn` is the lowering: memory slots are step indices, so
flattening is slot assignment; the pool interns names, constants, types and methods, kind-checked by index; the
`instruction` choice is `@disjoint` -- a bare mnemonic beside a labelled operand record -- and reads tag-free.
Admission is one forward pass in both layers: straight-line code has no merge points, so checking and
inference coincide, and the return's inferred type is the derived response contract, known before execution.

This is the dataflow design the README argued for by another route -- Cap'n Proto's promised answers as the
precedent -- arrived at as a compiler rather than a packet, and it supersedes the `plan`/`step` sketch rpc-1.tn
briefly carried. `rpc-1.tn` is now `call` and `return` only.

**Brought to fit, mechanically** (measured by `AgentProbe`): both at Revision 34 under this repo's identities,
`…/ltr8/http/agent-1.tn` and `agent-vm-1.tn`, with no placeholder pins -- a malformed `?sha256=` is refused
outright; `token`, which is not in core, replaced by a `name` role declared once in `agent-1.tn` (the identifier
grammar as a pattern, since a user schema cannot reach `identifier`) and imported by the VM; the inline
`filter` record at a group-member position named, as §5.2 requires; `value`, which is the kernel's and not
core's, replaced by `constant => unknown`; and the AST's top type renamed `plan`, since the VM imports the AST
and two `agent`s collide in a flat namespace.

**Decisions this leaves, in the order I would take them:**

1. **One interface per plan, or several?** Both layers carry one `interface: uri`, where the direction above
   has an agent calling across interfaces. The reconciliation that changes nothing here: the gateway publishes
   an interface that `extends` the ones it fronts, and a plan names that. `extends` was built for exactly this.
2. **Business errors.** A failed call without `or` aborts with `agent_error`, whose `detail` is text. The
   interface declared that call's errors as typed values composing `problem`; the abort should carry the one
   that occurred, and `agent_error` should compose `problem` like every other error here, so the api gateway
   and the agent processor answer failures in one shape.
3. **`version: uint8 = 2`** on the agent duplicates what the identity `agent-vm-1.tn` already says (§3.5); keep
   it only if the binary form wants a magic byte, and say so.
4. **The surface grammar reads schemaless**, method names as preserved unknown type refs and `@or`/`@interface`
   as preserved annotations -- which sidesteps "which namespace may a document bind" rather than answering it.
   One hazard to design out: a schemaless read resolves built-ins first, so a method named `date`, `uuid` or
   `text` would be parsed as the built-in, not preserved. The surface needs to reserve those or qualify method
   names.
5. `name` is a pattern over `text`, so §8.2's hygiene does not reach it (nor would it reach a map key); the
   admission verifier refuses confusable step names, as `agent-1.tn` now says.

**What the precedents contributed.** gRPC: the closed set of transport-level statuses, the deadline, and
frames-plus-end-status for a stream -- but no self-contained packet, its outcome in HTTP/2 trailers. JSON-RPC
2.0: the self-contained envelope, the correlation id, and batch -- but an unnamespaced `method`, untyped
`params`, and no dataflow between batched calls. Cap'n Proto RPC: promised answers -- a call may name an
earlier call's result by question id and a path of field reads into it -- which is the plan's wiring as a
shipped protocol. Avro RPC: errors declared per method and returned as typed values, not codes. Connect/Twirp:
the HTTP/1.1-friendly unary shape and an enveloped end-of-stream frame. Taken from none of them: numeric
method ids -- the identity URI plus key carries more for the same purpose.

**Two things TSON has that they lack, and the packet leans on both.** Templates closed per method give a typed
envelope with no per-service code: `rpc-1.tn` imports no service's types, the wire schema closes its templates
over them, and the packet is checked end to end by the resolver -- `quantity: two` in a call is an `int32`
violation at `/request/order/quantity`, an error's fixed `status` is enforced, and where a method declares several
errors the value carries the error's tag. (An earlier draft scoped the payload in place with `!!schema` at an
`unknown` slot; the templates are better and need no reader the library lacks.) And a method's address is a
content-addressed identity -- versioned, unambiguous across every service a gateway fronts, with no registry.

**Where it lives.** The packet, dispatch and the processor are transport-neutral; `tson-http` is the wrong home.
A `tson-service` module (or repo) for interface + packet + dispatch + agent, with `tson-http`'s adapters
becoming the api gateway over it -- and the RPC transport over HTTP is then itself one `!api` with a single
endpoint, described in the same vocabulary.

**Order:** swap the meta layer (three decisions first: the transport's 400 is not declared per endpoint;
`Routes` checks the verb against `@safe`/`@idempotent`; the map-key hygiene gap is filed) → packet schema and
in-process dispatch, the `Orders` interface and implementation, a Java method-naming rule, errors as thrown
bound classes with fixed statuses → the JDK adapter as api gateway, hand-written handlers gone → remote RPC
over HTTP and the other two adapters → `plan` and the processor.

## Open questions, kept here

**`unknown` has no reader, and the agent's constants lean on it.** `AgentProbe` shows any constant in a plan or
an agent (`constant => unknown`) reporting `NOT_IMPLEMENTED`: the library has no compiled reader for `unknown`
(nor `extern`), the gap the tson-java skill lists. The RPC packet no longer depends on it -- its templates are
closed per method, so the payload is typed -- but a plan's literals cannot be given a type the same way, since a
generic plan schema cannot be closed per plan. Until the reader lands, a plan carrying a literal cannot be read.
The probe is written to flip when it does. **Not filed** -- confined here with the rest, though it is the first of
these that is a library ask
rather than a spec question.


**Which namespace may a document bind?** `!!schema` binds the type namespace and `!name` resolves against it,
one hop. A plan or batch document wants to bind an *interface* -- to say `!place_order { request: { … } }` and
have the step checked against the method -- and cannot: `place_order` is a key inside `orders`, not a type
name. `method: place_order` as data is the workaround. Whether a document may bind a namespace of another kind,
or more than one, is a spec question; the URL namespace being hierarchical (`/orders/{id}/items` under
`/orders/{id}`) while a resource key is a flat template is the same question as `orders.place_order`, and one
answer should serve both.


**A disjoint choice with an enum variant is not read tag-free.** `agent-vm-1.tn`'s `instruction => ( simple_op
| op )` is `@disjoint` -- string class beside brace class -- and the resolver accepts the assertion, but the
reader refuses a bare `RET` at that position and asks for a tag. Measured precisely in `AgentProbe`: `(text |
integer)` reads untagged, so tag-free dispatch exists; an enum variant is what it does not cover. Until it
does, a compiled agent writes `!simple_op RET` and `!op { make: 0 }`, and the VM's "tag-free" claim describes
the schema rather than the implementation. **Not filed** -- a library ask, like the `unknown` reader above.

**Streams are not modelled, until a use arrives.** An earlier sketch carried `request_stream`/`response_stream`
on the signature, meaning a *sequence* of documents of the declared type -- count unknown, each complete as it
arrives -- a property of the call, not of the type, and so not `[order]`. Removed because nothing here uses
them and the format has no framing for a sequence of documents (a document is one value; readers reject
trailing content). When a use arrives, the framing question comes with it: `application/tson-seq` on RFC 7464's
model (an error mid-stream is a `problem` document in the sequence, so `errors` apply per element) over SSE,
multipart, or a lazily-read array; and a `shape: UNARY | SERVER_STREAM | CLIENT_STREAM | BIDI` beats two booleans
that admit the bidirectional shape by accident of encoding.

**A `~data` constructor as a map's value type is admitted, and §4.1 reads as if it should not.** No longer
relied on -- the sketch's inner types are records now -- but still measured in `InterfaceMapProbe` on a probe-only
`{type_name => data_method}`, because it is a live question for the spec: [TSON-SCHEMA] §4.1 refuses "naming a
`kind: DATA` entry" as an element type, and a map's value type is one; the implementation admits the DATA
*constructor* there and refuses only an *instance*. The kernel's own `top`-typed slots hold DATA instances, so the
implementation is consistent with the kernel; whether "entry" was meant to include a constructor is what to ask.
"What the maps are", above, is the argument for answering it with a `namespace` kind rather than by widening
`data`. **Not filed** -- confined here with the rest.

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
- `rpc-1.tn` -- the wire form: `call` and `return` as templates; the direction above, in its own `@doc`s.
- `examples/orders-wire-1.tn` -- the templates closed per method of the orders interface: what a generator emits.
- `agent-1.tn`, `agent-vm-1.tn` -- the plan (AST) and the agent (bytecode) it compiles to; the section above.
- `examples/` -- real documents: the shared types and errors, the interface only, the web service only, and
  both, each at its own `!!id`; `examples.md` is the guide to them and the placement table. `ExamplesProbe`
  resolves every file and runs both apis through `Routes`.
- `java/…/experiment/metaservice/` -- the bound records the `~data` constructors need (`Method`, `Interface`,
  `Endpoint` -- a sealed interface, the base -- `Operation`, `Binding`, `Resource`, `Api`, `HttpVerb`;
  `Signature`, `InterfaceOfSignatures`, `DataMethod`, `InterfaceOfDataMethods`, `ByTypeName`, `ByMethodName`,
  `ByText` for the probes that need their own constructors), `Placement` and `Routes` (the reader-side checks the
  maps need), and five probes. Field names match the
  schema's exactly (`idempotency_key`), because binding does no case conversion; a Java keyword as a field name
  (`extends`, `interface`) is renamed with `@Field`.
