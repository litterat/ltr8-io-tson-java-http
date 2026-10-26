# `meta-service.tn` -- interfaces and operations in one meta layer

**Status: graduated.** `meta-service.tn` ships in `tson-http` (`tson-http/src/main/resources/meta-service.tn`),
replacing `meta-http.tn`, and the demos describe themselves with it; `CLAUDE.md`'s "Describing a service" is the
account of it as it ships, and the layer's own `@doc`s carry its reasoning. The reader is `TsonApiDescription`, the
bound records are in `io.ltr8.tson.http.api`, and the suites that check the shipped layer are `tson-http`'s
`TsonApiSchemaTest` and `TsonApiDescriptionTest`. What stays here probes it from directions its own suite does not:
the wire form (`rpc.tn`), the agent layers, the examples, and the findings the map design rests on.

**Scope: the descriptor, for RPC- and API-style endpoints.** `meta-service.tn` describing an interface, a web
service over it, and the endpoints of both is the work in hand; `rpc.tn` is its wire form and belongs to that
scope. **The agent is future work and is here only to shape the descriptor** -- a third consumer of an interface
whose demands say whether the meta layer holds up, in the way a second implementation says whether an API does.
Its layers are worth keeping resolved and probed for that reason and no other. Nothing in the order of work
below waits on them, and a decision that is the agent's alone is not a reason to move the descriptor.

## The question

`meta-http.tn`, the layer this replaced, described an HTTP API: a schema governed by it declared `!operation`
entries whose `request` and `response` are type references the compiler resolves. Underneath an HTTP API there is
usually a transport-neutral interface -- methods with a request, a response and errors -- and a service may want to
declare just that, or just its web service, or both, with an HTTP operation being one *projection* of a method.
Can one meta layer hold all three, and what does the third actually need?

## The layer

[`meta-service.tn`](../../tson-http/src/main/resources/meta-service.tn), with three uses as real documents under
[`examples/`](examples/) (guide: [`examples.md`](examples.md)). A governed schema writes two kinds of entry, and both
are **namespace values** -- [TSON-SCHEMA] §4.1's constructor whose instances are maps keyed by names:

- `interfaces => namespace & { key_type: = identifier  value_type: = interface }`, holding
  `interface => { extends?: [ref<interface>]  methods: methods }`, whose `methods => namespace & { key_type: =
  method_name  value_type: = method }`. A method is a map *value* under its name, so the name is scoped to its
  interface (two interfaces may both declare `place_order`), a `@doc` before the key documents it, and the `!method`
  tag is optional under the typed slot. `extends` names other interfaces whose methods this one also has.
- `apis => namespace & { key_type: = identifier  value_type: = api }`, holding `api => { implements?:
  [ref<interface>]  not_bound?: [exemption]  resources: {path_template => resource} }` and `resource => {
  endpoints: {http_verb => endpoint} }` -- resources keyed by **path**, each holding endpoints keyed by **verb**:
  OpenAPI's `paths`, arrived at from the key types. A path key is a `path_template`, so `/orders/{id}` needs no
  identifier minted from it and an endpoint needs no invented name. `resources` and `endpoints` are maps, not
  namespaces: their keys are not identifiers, so no qualified name enters them, and nothing names an endpoint.
- **`interface` and `api` are records held one level down, not the namespace types themselves**, because each
  carries fields beside its map (`extends`, `implements`, `not_bound`) and a namespace value is the map alone. A
  description writes one namespace entry per interface or api, named as its one key:
  `orders => !interfaces { orders => { place_order => … } }`. Everything inside is an ordinary record, because it
  only ever appears as a map value; `method => signature & {}` takes the empty body to be a type of its own rather
  than an alias that would be a hop to `signature`. An entry applying a namespace type is a value, never a type, so
  naming `orders` where a type is expected is a resolver error.
- `endpoint => placement & { status?: status_code ~ 200  encodings?: [encoding] }`, with `operation => endpoint &
  signature`, `binding => endpoint & { method: ref<method> }` and `document => endpoint & { … media_type: media_type }`
  composing it. An `!operation` carries its signature inline, for an api with no interface behind it; a `!binding`
  borrows a method's; a `!document` answers bytes of a media type, as a schema route does. The tag says which, and it
  is not optional: the base binds to a type with no bare form, so an untagged value is refused naming the subtypes --
  the one-or-the-other rule with no reader behind it. (A choice and a field group were both measured to work; the
  supertype won because the base is abstract, shared vocabulary is inherited rather than composed twice, and a new
  kind of endpoint is one more composition.) Documentation is annotation on the verb key -- `@summary` the short
  form, declared here as the kernel declares `doc`, and `@doc` the long -- not fields: an endpoint is a map value, and
  a map key carries annotations.
- `signature` and `placement` are plain records composed into the records above. `signature` is the
  transport-neutral contract: request, response, errors. What a caller composing calls needs to know beyond that
  -- `@safe`, `@idempotent` -- is a bare annotation on the method's key, because it describes the method rather than
  its request or response; the HTTP projection checks its verb against them and never derives one from the other.
  `placement` is **where the interface and the web service over it are allowed to look different**: a method has
  one request record, and the endpoint distributes its fields -- a `{segment}` in the path binds a field, `query`
  names query fields, `headers` maps a field to its header, `body` names one field carried unwrapped as the whole
  body, and the remainder is the body (or the query, for a GET/HEAD or once `body` named a field). Every field lands
  exactly once, and a field in the path, query or a header must be a scalar. There is no parameter list: a
  parameter-only endpoint declares a one-field request record and binds it from the path. (gRPC's HTTP transcoding
  is the precedent.)
- `implements` is a **claim** the reader holds the api to: every method of every implemented interface (through
  `extends`) has a binding naming it, or an `exemption` in `not_bound` giving the reason -- the
  `TsonApiCoverage.notServedHere` rule, derived from the description rather than from handler registration. Held per
  (interface, method), never per name.
- An error type pins the status it inherits from `problem` (`sku_not_found => problem & { status: = 404 … }`),
  which is how an endpoint's `errors` get one without a `responses` list.

**Naming is the resolver's; relating is the reader's.** What names something is a reference the resolver judges at
schema load: `extends` and `implements` are `[ref<interface>]`, and a binding's or an exemption's `method` is
`ref<method>`, a [TSON-SCHEMA] §5.12 qualified name through the interface's entry, its key, its `methods` field and
the method's key -- `orders.orders.methods.place_order`. A misspelt method, a reference to the wrong kind of thing,
and an ambiguous name are refused there. `not_bound` is a list of records rather than a map keyed by method, because
a map key is a name in the map's own key family, never a reference. `extends` is a reference and not a composition,
so an inherited method is named where it is declared; `&` over namespace values, which would shorten
`orders.orders.methods`, is not proposed, composition being over types. What relates two things -- a bound method
belonging to an interface the api implements, the `implements` claim, an `extends` cycle, placement, a verb or status
against the method -- is `TsonApiDescription`'s, checked when it reads a description.

The probes that stay here:

- `InterfaceMapProbe` -- the finding the map design rests on: a map of method records resolves, a value under the
  typed slot needs no tag, a name inside a value is judged at load, and a `@doc` on a key survives the read; and the
  namespace-only variant, whose entry is the map of methods with nothing beside it.
- `ExamplesProbe` -- the documents under `examples/` resolve against the layer, both apis read into routes, and the
  wire schema is derived from the interface rather than read.
- `NameRoleProbe` -- what a naming role buys at a map key, and that §8.2's hygiene judges a method map's keys.
- `RpcProbe` -- `rpc.tn` and the wire schema resolve; a call is typed by the interface's own types and a bad
  request is refused at its field; a return carries the declared response or error with its status pin enforced,
  and exactly one outcome.
- `AgentProbe` -- both agent layers resolve; a plan of references and a compiled agent read; a constant names its
  own type; a malformed or mixed-script step name is refused by the `name` role.

**Two binder facts met on the way**, both worth knowing before writing a bound record for a meta layer's
vocabulary: the binder finds a class by PascalCasing the type name (`interface_of_methods` →
`InterfaceOfMethods`), `@Typename` notwithstanding; and it hands a map's *keys* back as `String` whatever the
schema's key type, so `{http_verb => endpoint}` binds to `Map<String, Endpoint>` and a `Map<HttpVerb, …>`
component would hold strings and lie about it. A third: a slot typed by a record with no bare form needs a Java type
of that name even if nothing constructs it bare -- a sealed interface is the right thing to put there, and it makes
the base as abstract in Java as it is in the schema.

## Why a binding refers to a method rather than being one

"Both" in the sense that matters is not one entry carrying method and binding at once. It is `place_order`
declared once on the interface and a *separate* entry -- possibly in another document -- saying "POST /orders is
that method". That second entry has to **refer** to the first, and a method is a value inside an interface, not a
type. `ref<method>` is the reference form, so the binding names the method and the resolver checks the name as it
checks `request: order`.

The alternative measured on the way, under plain `meta.tn`, makes a method a type -- the type of its call record --
and an operation IS-A its method:

```
method => <Req, Resp> { request: Req  response?: Resp  safe?: boolean ~ false  idempotent?: boolean ~ false }
http   => { verb: http_verb  path: text  status?: status_code ~ 200 }

place_order  => method<order, order> & { errors?: [sku_not_found] }
create_order => place_order & http & { verb: = POST  path: = "/orders"  status?: = 201 }
```

It reads, and a plan step is then a value of the method type. Its cost is that schema facts stated as fields are
per-instance: `verb: = POST` pins a field whose name is unmarked, which [TSON-SCHEMA] §5.2 makes a *marker* the
value must write -- never injected -- so each call restates its verb and URL, and a refinement cannot relax the
base's required `verb` to an omissible one that would inject instead. A reference has none of that cost.

## What the maps are: borrowed namespaces

The observation the design rests on. A schema describes a namespace -- the type system's, keyed by type names under
[TSON-DATA] §7.7's identifier grammar. An interface and an api describe namespaces too, and **their key types are
naming authorities TSON does not own**:

| map | keys are | whose namespace |
|---|---|---|
| a schema | type names | TSON's own (§7.7) |
| an interface's `methods` | method names | a procedure-call namespace -- what gRPC and JSON-RPC address as `service.method` |
| an api's `resources` | URL path templates | RFC 3986's, written as RFC 6570 templates |
| a resource's `endpoints` | verbs | RFC 9110's method registry, via IANA |
| a request record | field names | the record's own -- the namespace `placement` maps the URL's segments, query keys and header names onto |

`place_order` is not a type; it is a name in the `orders` namespace that a method description sits at. That is
plainest in the api, where `"/orders"` is visibly a name in the URL path namespace. The map's key type is how a
TSON document borrows a namespace it does not govern and says what lives at each name -- which is why `http_verb` is
an enum copied from a registry and a path key is a `path_template`: the discipline comes from outside.

**One naming role per borrowed namespace.** The kernel shows how: `type_name`, `field_name` and `param_name` are
roles over one identifier grammar, so a rule stated on a role reaches every position of that kind. The layer
declares one per namespace it borrows and uses it as the map's key type: `method_name => identifier` for an
interface's methods; for the URL and header namespaces, whose grammars are not TSON's identifier, the authority's
grammar rides as a `pattern` (I-Regexp, matched against the whole token) on a text family -- `path_template` and
`header_name`, the second held case-folded as RFC 9110 §5.1 compares it. `NameRoleProbe` measures what a role buys:
the identifier grammar, enforced, with the refusal naming the role -- *"'method_name': 'place order': U+0020 at index
5 cannot appear in an identifier"* -- where `type_name` enforces the same grammar while misnaming the namespace, and a
namespace cannot be keyed by `text` at all, its keys being names. Because a method map's keys are names, §8.2's
hygiene judges them: a mixed-script key is refused, and two keys that read alike (`pass` and the all-Cyrillic
`раѕѕ`) are refused at the second, in a data document and in a schema governed by a meta layer alike.

Three things follow.

**It gives a method an identity without inventing one.** A schema is content-addressed by its `!!id`, so a method's
global name is `(schema identity, qualified name)` -- `…/orders-1.tn` and `orders.orders.methods.place_order` --
unambiguous across every service, with no `operationId` and no flat-namespace collision. It is what an agent plan
step should carry and what a route table could key on. Not a fragment: [TSON-DATA] §2.2.1 keeps fragments out of
identity, and a key is a member, not a second document.

**An api is a namespace by what it does, and a map in TSON.** An endpoint describes an unbounded set of exchanges, so
it is a declaration, and a URL is a reference to it by key -- `(api, path, verb)`. But its keys are data, two levels
deep, and a namespace's keys are identifiers (a qualified name's segment is one), so `resources` is a map, no
qualified name enters it, and a handler claims an endpoint by its verb and declared path (`TsonApiCoverage.serving`).

**It leaves two things unfinished**, recorded under "Open questions" below: the URL namespace is hierarchical and a
resource keyed by full template is flat over it; and a data document can bind only the type namespace (`!!schema`,
`!name`), so a plan document cannot tag a step `!place_order`.

## What a namespace is: the test

"A keyed set" is every map, so the word needs a test. **A namespace is a keyed set of declarations; a map is a keyed
set of values.** A declaration describes values and has instances of its own; a map's member *is* a value. Two
tempting tests fail: unique keys (every map has them), and being referenced by key (a foreign key references a table
row, and a row is a value).

| candidate | key | member | each member has instances? | container has instances? | a namespace by the test? |
|---|---|---|---|---|---|
| schema | `type_name` | a type | values | no | ✓ |
| interface | `method_name` | a method | calls | implementations | ✓ |
| record fields | `field_name`, the record's `name_type` | a field | the values at that key | record values | ✓ |
| api | path, then verb -- **data** | an endpoint | exchanges at that route | a server | ✓ |
| database | table name | a table | rows | no | ✓ |
| table columns | column name | a column | cells | rows | ✓ -- record fields again |
| table rows | primary key -- data | a row | none: it *is* one | -- | ✗ map |
| discriminated family ([TSON-SCHEMA] §5.2) | the selector pin -- **data** | a subtype | values | the base's values | ✓ |
| template parameters | `param_name` | a parameter | arguments | applications | ✓ -- lexically scoped |
| problem types | a type URI -- **data** | a problem declaration | problem bodies | -- | ✓ |
| AsyncAPI channels | a channel address -- **data** | a message declaration | messages | -- | ✓ |
| agent tools | tool name | a tool | invocations | -- | ✓ -- an interface |
| IANA header registry | header name | a field definition | header values | -- | ✓ |
| a request's headers | header name | a value | -- | -- | ✗ map |
| enum | a label | a label | none: each is one | values | ✗ set -- a label's `@doc` rides as a key annotation |
| config, environment | a key | a value | -- | -- | ✗ map |

**The declaration side is a namespace and the instance side a map, every time** -- registry and message, columns and
rows, schema and document, interface and calls. The kernel bears it out: its two namespace types are `schema` and
`field_map`, the first two rows. What the test admits and Revision 38 does not take is the data-keyed rows -- api,
discriminated family, problem types, channels: TSON's `namespace` is keyed by an identifier family, so each of those
is a map in TSON, reached by location rather than by a qualified name.

## Direction: interface, api, agent

Three projections of one interface, and the order they arrive in. The first two are the current scope; the third
is listed because what it needs of an interface is evidence about the descriptor, not because it is being built.

- **The interface is the canonical layer, and its wire form is the RPC packet.** [`rpc.tn`](rpc.tn):
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
- **The api is a gateway.** An api describes a translator, not a server: `TsonApiDescription` is its table, and
  `RequestPlacement` run in reverse -- `id` from the path segment, `order_query` from the query string, `order` from
  the body beside `Idempotency-Key` -- is its request path, producing a `call`. Which is why a generic gateway
  process can read an api and an RPC address and need no code per service.
- **The agent is three layers, as a compiler has** -- [`agent.tn`](agent.tn) and
  [`agent-vm.tn`](agent-vm.tn), below. A plan SOURCE (a surface grammar, still to write) is read to a
  `plan` (the AST: steps in order, references parsed to selectors, constants folded, `or` a field because it
  is executed), which compiles deterministically to an `agent` (a constant pool, write-once memory slots, and
  straight-line stack code with no jumps) that a server admits, verifies in one forward pass against the
  pinned interface, and executes -- each CALL being one `call` of rpc.tn. The likely deployment is a
  gateway agent processor whose calls are RPC to the box hosting each interface; the api gateway does the same
  for one call.

### The agent: three layers

**Future work, kept for what it says about the descriptor.** An agent executing a plan of calls is the consumer
that presses hardest on an interface -- it needs a method addressable, its request and response typed, and its
errors declared -- so sketching it is how the interface layer gets tested against something that is not HTTP.
Building it is not on the order of work below.

`agent.tn` is the plan, resolved -- named as a resolved schema is named against its source: the surface is
plan source, this is the plan, and an `agent` is what it compiles to. Everything the surface leaves implicit is
structural here: a reference is a `selector` (a step, then `segment`s -- `field`, `index`, or a `filter`),
distinguished from a constant by shape; an argument is an `arg` tree whose canonical form folds constants
maximally, so `record` and `array` appear only on the spine above a reference; `or` is a step's failure
substitute, a field because it is executed. `agent-vm.tn` is the lowering: memory slots are step indices, so
flattening is slot assignment; the pool interns names, constants, types and methods, kind-checked by index; the
`instruction` choice is `@disjoint` -- a bare mnemonic beside a labelled operand record. Admission is one forward
pass in both layers: straight-line code has no merge points, so checking and inference coincide, and the return's
inferred type is the derived response contract, known before execution.

This is the dataflow design arrived at as a compiler rather than a packet, with Cap'n Proto's promised answers as the
precedent; `rpc.tn` is `call` and `return` only.

Both resolve under this repo's identities, `…/io/ltr8/http/agent.tn` and `agent-vm.tn`. `name` is an identifier
family declared once in `agent.tn` and imported by the VM, so §8.2's hygiene judges a step name wherever it stands; a
constant is `constant => dynamic`, §7.8's widest scoped instance, so a literal names its own type -- from this
schema or from a foreign one through a nested `!!schema` -- and is validated in full, and a value naming no type is
refused. The AST's top type is `plan`, since the VM imports the AST and two `agent`s would collide.

**Decisions this leaves, in the order I would take them:**

1. **One interface per plan, or several?** Both layers carry one `interface: uri`, where the direction above
   has an agent calling across interfaces. The reconciliation that changes nothing here: the gateway publishes
   an interface that `extends` the ones it fronts, and a plan names that. `extends` was built for exactly this.
2. **Business errors.** A failed call without `or` aborts with `agent_error`, whose `detail` is text. The
   interface declared that call's errors as typed values composing `problem`; the abort should carry the one
   that occurred, and `agent_error` should compose `problem` like every other error here, so the api gateway
   and the agent processor answer failures in one shape.
3. **`version: uint8 = 2`** on the agent duplicates what the identity `agent-vm.tn` already says (§3.5); keep
   it only if the binary form wants a magic byte, and say so.
4. **The surface grammar reads schemaless**, method names as preserved unknown type refs and `@or`/`@interface`
   as preserved annotations -- which sidesteps "which namespace may a document bind" rather than answering it.
   One hazard to design out: a schemaless read resolves built-ins first, so a method named `date`, `uuid` or
   `text` would be parsed as the built-in, not preserved. The surface needs to reserve those or qualify method
   names.

**What the precedents contributed.** gRPC: the closed set of transport-level statuses, the deadline, and
frames-plus-end-status for a stream -- but no self-contained packet, its outcome in HTTP/2 trailers. JSON-RPC
2.0: the self-contained envelope, the correlation id, and batch -- but an unnamespaced `method`, untyped
`params`, and no dataflow between batched calls. Cap'n Proto RPC: promised answers -- a call may name an
earlier call's result by question id and a path of field reads into it -- which is the plan's wiring as a
shipped protocol. Avro RPC: errors declared per method and returned as typed values, not codes. Connect/Twirp:
the HTTP/1.1-friendly unary shape and an enveloped end-of-stream frame. Taken from none of them: numeric
method ids -- the identity URI plus key carries more for the same purpose.

**Two things TSON has that they lack, and the packet leans on both.** Templates closed per method give a typed
envelope with no per-service code: `rpc.tn` imports no service's types, the wire schema closes its templates
over them, and the packet is checked end to end by the resolver -- `quantity: two` in a call is an `int32`
violation at `/request/order/quantity`, an error's fixed `status` is enforced, and where a method declares several
errors the value carries the error's tag. A scope push at a `dynamic` slot would also type the payload, but the
templates type it at the schema rather than by each document. And a method's address is a content-addressed
identity -- versioned, unambiguous across every service a gateway fronts, with no registry.

**Where it lives.** The packet, dispatch and the processor are transport-neutral; `tson-http` is the wrong home.
A `tson-service` module (or repo) for interface + packet + dispatch + agent, with `tson-http`'s adapters
becoming the api gateway over it -- and the RPC transport over HTTP is then itself one api with a single
endpoint, described in the same vocabulary.

**Order:** packet schema and in-process dispatch, the `Orders` interface and implementation, a Java method-naming
rule, errors as thrown bound classes with fixed statuses → the JDK adapter as api gateway, hand-written handlers gone
→ remote RPC over HTTP and the other two adapters. `plan` and the processor come after all of that, if at all; they
are not part of this scope and nothing earlier in the order waits on them.

## Open questions, kept here

**The RPC address names a document, not an interface.** `rpc.tn`'s `call` addresses `(interface: uri, method:
text)`, and that pair is not unambiguous: `examples/orders-1.tn` declares two interfaces at one `!!id`, and two
unrelated ones declaring `place_order` would be indistinguishable in a call. The wire schema has the same flaw a layer
up -- `place_order_call` is a flat type name, so two interfaces per document collide there too. Either the address
carries the qualified name (`orders.orders.methods.place_order`), or one interface per document becomes a rule and
`orders_v2` moves to its own file. Dispatch is the next item in the order, and this is what it dispatches on.

**Which namespace may a document bind?** `!!schema` binds the type namespace and `!name` resolves against it,
one hop. A plan or batch document wants to bind an *interface* -- to say `!place_order { request: { … } }` and
have the step checked against the method -- and cannot: `place_order` is a key inside `orders`, not a type
name, and a qualified name stands only at a `ref<T>` slot in a schema. A reference in a data document is open,
with the one-level work (`experiments/one-level/README.md`, "The reference atom"); the URL namespace being hierarchical
(`/orders/{id}/items` under `/orders/{id}`) while a resource key is a flat template is the same question, and one
answer should serve both.

**A disjoint choice with an enum variant is not read tag-free.** `agent-vm.tn`'s `instruction => ( simple_op
| op )` is `@disjoint` -- string class beside brace class -- and the resolver accepts the assertion, but the
reader refuses a bare `RET` at that position and asks for a tag. Measured precisely in `AgentProbe`: `(text |
integer)` reads untagged, so tag-free dispatch exists; an enum variant is what it does not cover. Until it
does, a compiled agent writes `!simple_op RET` and `!op { make: 0 }`, and the VM's "tag-free" claim describes
the schema rather than the implementation. **Not filed** -- a library ask.

**Streams are not modelled, until a use arrives.** A stream is a *sequence* of documents of the declared type --
count unknown, each complete as it arrives -- a property of the call, not of the type, and so not `[order]`. Nothing
here uses one, and the format has no framing for a sequence of documents (a document is one value; readers reject
trailing content). When a use arrives, the framing question comes with it: `application/tson-seq` on RFC 7464's
model (an error mid-stream is a `problem` document in the sequence, so `errors` apply per element) over SSE,
multipart, or a lazily-read array; and a `shape: UNARY | SERVER_STREAM | CLIENT_STREAM | BIDI` beats two booleans
that admit the bidirectional shape by accident of encoding.

**Recorded, not acted on.** `http_verb` is a closed enum, so no custom or WebDAV verb can be described. Two of an
endpoint's errors may pin the same status, which is fine on the wire -- the value carries the error's tag -- but
means the gateway must set `type` rather than relying on the status to discriminate.

## Files

- `meta-service.tn` -- graduated: `tson-http/src/main/resources/meta-service.tn`, its reasoning in its own `@doc`s.
- `rpc.tn` -- the wire form: `call` and `return` as templates; the direction above, in its own `@doc`s.
- `examples/orders-wire-1.tn` -- the templates closed per method of the orders interface: what a generator emits.
- `agent.tn`, `agent-vm.tn` -- the plan (AST) and the agent (bytecode) it compiles to; the section above.
- `examples/` -- real documents: the shared types and errors, the interface only, the web service only, and
  both, each at its own `!!id`; `examples.md` is the guide to them and the placement table. `ExamplesProbe`
  resolves every file and reads both apis through `TsonApiDescription`.
- `java/…/experiment/metaservice/` -- the probes that stay, and `Experiment`, which hands them the shipped layer and
  its binder. The bound records are `tson-http`'s, in `io.ltr8.tson.http.api`.
