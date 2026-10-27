# `rpc.tn` -- the wire form of an interface

**Status: not landed, and first in the order of work after the descriptor.** `meta-service.tn` describes an
interface, a web service over it, and the endpoints of both; this is what calling a method of that interface looks
like on a wire. The interface it is measured against is the orders example that ships as `tson-http`'s test fixture
(`OrdersExamples`, under `tson-http/src/test/resources/io/ltr8/tson/http/api/orders/`), so this directory holds only
what is its own: the packet schema, the wire schema a generator would emit for that interface, and the probe.

**Where it lives when it lands: not `tson-http`.** The packet, dispatch and the processor are transport-neutral. A
`tson-service` module (or repo) for interface + packet + dispatch, with `tson-http`'s adapters becoming the api
gateway over it -- and the RPC transport over HTTP is then itself one api with a single endpoint, described in the
same vocabulary.

## The question

An interface is the canonical, transport-neutral layer, and an HTTP api is one projection of it. What is the other
projection -- the packet a call is when nothing is translating it -- and can TSON type it end to end with no code
per service?

## The design

[`rpc.tn`](rpc.tn) declares two templates: `call => <Req> { … }` -- address `(interface !!id, method key)`,
correlation `id`, `deadline`, opaque `meta`, the method's `request` -- and `return => <Resp, Err> { … }`, exactly
one of `response`, a declared `error` (a typed value composing `problem`, its status pinned on the type) or a `fault`
(the transport's or processor's own `problem`, its `type` from the same closed set the HTTP problem types use).

**Both are closed once per method by a wire schema derived from the interface** -- `place_order_call =>
call<new_order>`, `place_order_return => return<order, sku_not_found>`, a choice of errors where a method declares
several, `return_plain` where it declares none. [`orders-wire-1.tn`](orders-wire-1.tn) is that schema for the orders
interface, written by hand to show what a generator emits. A packet is then `!place_order_call { … }`: a fully typed
document whose request the resolver checks against the interface's own types, with `rpc.tn` importing no service's
types. The wire schema is deterministic in the interface, so it is generated, published at its own identity, and
fetched like any other; `RpcProbe.theWireSchemaIsWhatTheInterfaceDetermines` derives it from the interface and
holds the hand-written file to it, which is the check a generator makes unnecessary.

**Both gateways translate into this.** An api describes a translator, not a server: `TsonApiDescription` is its
table, and `RequestPlacement` run in reverse -- `id` from the path segment, `order_query` from the query string,
`order` from the body beside `Idempotency-Key` -- is its request path, producing a `call`. Which is why a generic
gateway process can read an api and an RPC address and need no code per service. An agent processor
(`experiments/agent/`) does the same for several calls in one request. In-process Java dispatch is the first
transport: an `Orders` implementation wires to the interface once, and everything else translates into a call.

**The method's address is a content-addressed identity**: the interface's `!!id` and the method's key, unambiguous
across every service a gateway fronts, versioned by the identity, with no registry and no numeric ids -- and
redundant with the closed type's name, kept because a packet should say where it is going without anyone parsing a
type name. (See the first open question: the pair is not yet unambiguous.)

**What the precedents contributed.** gRPC: the closed set of transport-level statuses, the deadline, and
frames-plus-end-status for a stream -- but no self-contained packet, its outcome in HTTP/2 trailers. JSON-RPC 2.0:
the self-contained envelope, the correlation id, and batch -- but an unnamespaced `method`, untyped `params`, and no
dataflow between batched calls. Cap'n Proto RPC: promised answers -- a call may name an earlier call's result by
question id and a path of field reads into it -- which is the agent plan's wiring as a shipped protocol. Avro RPC:
errors declared per method and returned as typed values, not codes. Connect/Twirp: the HTTP/1.1-friendly unary shape
and an enveloped end-of-stream frame. Taken from none of them: numeric method ids -- the identity URI plus key
carries more for the same purpose.

**Two things TSON has that they lack, and the packet leans on both.** Templates closed per method give a typed
envelope with no per-service code: the packet is checked end to end by the resolver -- `quantity: two` in a call is
an `int32` violation at `/request/order/quantity`, an error's fixed `status` is enforced, and where a method declares
several errors the value carries the error's tag. A scope push at a `dynamic` slot would also type the payload, but
the templates type it at the schema rather than by each document. And a method's address is a content-addressed
identity -- versioned, unambiguous across every service a gateway fronts, with no registry.

## Order of work

Packet schema and in-process dispatch, the `Orders` interface and implementation, a Java method-naming rule, errors
as thrown bound classes with fixed statuses → the JDK adapter as api gateway, hand-written handlers gone → remote RPC
over HTTP and the other two adapters. The agent's plan and processor come after all of that, if at all; nothing here
waits on them.

## Open questions

**The RPC address names a document, not an interface.** `call` addresses `(interface: uri, method: text)`, and that
pair is not unambiguous: `orders-1.tn` declares two interfaces at one `!!id`, and two unrelated ones declaring
`place_order` would be indistinguishable in a call. The wire schema has the same flaw a layer up --
`place_order_call` is a flat type name, so two interfaces in one document declaring one method name collide there too.
Either the address carries the qualified name (`orders.place_order`, the interface's entry and then its key), or one
interface per document becomes a rule and `orders_v2` moves to its own file. Dispatch is the next item in the order,
and this is what it dispatches on.

**Streams are not modelled, until a use arrives.** A stream is a *sequence* of documents of the declared type --
count unknown, each complete as it arrives -- a property of the call, not of the type, and so not `[order]`. Nothing
here uses one, and the format has no framing for a sequence of documents (a document is one value; readers reject
trailing content). When a use arrives, the framing question comes with it: `application/tson-seq` on RFC 7464's
model (an error mid-stream is a `problem` document in the sequence, so `errors` apply per element) over SSE,
multipart, or a lazily-read array; and a `shape: UNARY | SERVER_STREAM | CLIENT_STREAM | BIDI` beats two booleans
that admit the bidirectional shape by accident of encoding.

## Files

- `rpc.tn` -- the wire form: `call` and `return` as templates; the design above, in its own `@doc`s.
- `orders-wire-1.tn` -- the templates closed per method of the orders interface: what a generator emits.
- `java/…/experiment/rpc/RpcProbe` -- both schemas resolve; a call is typed by the interface's own types and a bad
  request is refused at its field; a return carries the declared response or error with its status pin enforced,
  and exactly one outcome; and the wire schema is what the interface determines.
