# The protocol layer: the data view of a call (bind's `DataProtocol`, the join in `tson-service`)

How a call is described below the HTTP projection, and why the description takes the data's side rather than the
language's. The description is `ltr8-bind`'s and is built: `DataBindContext.getProtocol` reads a Java interface as a
`DataProtocol` of `DataExchange`s, all in `io.ltr8.bind`, and `DataExchangeHandler` is the seam a call is answered
through. The join, which holds a resolved `!service.protocol` against a `DataProtocol`, is built: `TsonServiceProtocol` in
`io.ltr8.tson.service` (`tson-service`), tested by `TsonServiceProtocolTest`. This note fixes the paradigm they are
built to. `BACKLOG.md`, "The module split", says what is still to build. `proposal/bind-invoke.md` is this
project's superseded proposal for the same descriptor.

**Invariants**

- An exchange is a request record or none, a response or none, and declared errors, each a `DataClass` of one bind
  context. It never describes a parameter list, a return type or a throwable as such.
- The bridge absorbs the language. A thrown exception stands in for an error record through a `DataClassBridge`
  (`ToData` on the throwable), as `ltr8-bind` stands a language form in for a data form everywhere else.
- The join compares like with like: a resolved `!service.protocol` against a `DataProtocol`, so the check is between two
  statements of one call and never between a schema and a language idiom.
- An exchange's identity is its name in its protocol (`TsonExchangeName`), never the request type: two exchanges may take
  the same record. A `DataExchange` holds its name and its invoker and no `Method`.
- Bind knows no schema; what it cannot check, the join does.
- Per-call context is never a parameter of an exchange. A caller binds a `ScopedValue` around the call.

## Why the data view, and not the language's

An rpc call is a request record, a response record and a set of error records. That is what a method is once the
language's spelling is bridged away, and it is what an exchange in a `!service.protocol` of `service.tn` already says:
`request`, `response`, `errors`. A language says the same thing in its own idiom: Java with a parameter list, a return
type and a `throws` clause; a functional language with a function from a product to a sum; C with arguments and an
out-parameter. The two views differ only slightly in shape. They differ decisively in direction.

Taking the data's side means the mapping to a language always runs from the same side. Whether the target language is
object-oriented, functional, procedural or something else does not matter: each language supplies its bridges, and the
model, the join, every projection (HTTP, gRPC, the rpc wire form) and every port carry the same facade. Had the
description been the language's view, every port would re-derive the join from its own idioms, and the projections would
diverge with them.

This is `ltr8-bind`'s own philosophy, applied one level up. A `DataClass` describes a class in the data model's terms: a
`DataClassRecord` is fields present or absent with a constructor and accessors as `MethodHandle`s, a `DataClassUnion`
is a tagged union whether Java spells it as a sealed interface, an abstract class or an embedded union, and a
`DataClassBridge` (built from an `io.ltr8.annotation.DataBridge`) carries a `toData` and `toObject` pair between a
class and the data class that stands in for it. The language's representation is adapted to the data model, never the
reverse. The description is bind's own because every piece of it is already a `DataClass` of the same context: a request
is a `DataClassRecord`, a response is whatever the context describes, and a declared error is a bridged descriptor whose
`typeClass()` is the throwable and whose `dataClass()` is its record. And bind has the constraints the description has:
it knows no schema and depends on `ltr8-annotation` alone.

## Where the two views differ, and how each resolves

**Arity.** A method takes one record or nothing; a spread of a request over several parameters is refused, and so is a
single parameter that is no record. That is the data view and not a restriction of it: `service.tn` gives an exchange one
request type, so the call's input is one record, and a method that takes its fields as parameters has no counterpart in
the schema to be compared with.

**Outcome.** The language has a privileged normal path and an exceptional one; the call has a response and declared
errors. At the Java edge the response is returned and a declared error is thrown, untouched. At the data edge an error is
a record: `DataExchange.error(thrown)` finds the declared error a throwable is an instance of, and that error's bridge
gives its record. There is no outcome carrier. Whether a projection writes that record or rethrows is the projection's
choice, and the protocol layer makes none. That is how the HTTP boundary's invariant, that a business error is written
and not thrown (`design/service-reader.md`, "Business errors compose `problem.details`"), holds at the boundary and
nowhere else: the projection catches a declared error and writes its record. A throwable the exchange does not declare is
the caller's fault path and is wrapped by nothing.

**Absence.** A method with no parameters is a request absent, and its handler is given `null`; `void` is a response
absent, and the exchange answers `null`. Both are the data model's absence, not a special case: `cancel_order` in the
orders fixtures declares no response, so its Java method returns `void`. A promise-typed return (`Optional`, `Future`,
`CompletionStage`, `Flow.Publisher`, a stream) and `Void` are refused, not bridged: a response is a value and not a
promise of one, which also settles `CompletableFuture`.

**Identity.** The request type cannot address a call: `get_order` and `cancel_order` both take `order_ref` in the orders
fixtures. The name does, as `TsonExchangeName` holds it: the protocol's entry and the exchange's key in it, written
`orders.exchanges.get_order`, the reference that reaches it. A descriptor that keyed dispatch by request class would
merge two calls the schema keeps apart. In bind the name is Java's (`placeOrder`), and `DataProtocol.exchange(name)`
finds it from the data's spelling: every `_` is removed and the next character upper-cased wherever it sits, so
`place_order`, `place__order` and `placeOrder_` all find `placeOrder`. The map runs from data to Java only, since no
function runs back (`getHTTPStatus`); bind refuses an interface with two names that are one under the rule.

**Context.** A network call carries what a method does not: headers, an idempotency key, the caller. The service's
placement maps it, and an exchange never sees it. Within a process a caller binds a `ScopedValue` around
`DataExchangeHandler.handle`, and a decorator rebinds it. The binding is not visible on another thread, which is why
`handle` blocks and returns the response rather than a promise.

**Two directions.** Inward, the invoker dispatches a call that arrived as data: a handle of type
`(Object target, Object request) -> Object` that returns the response and lets every throwable through, and
`DataProtocol.handler(target)` wraps it as a `DataExchangeHandler`. Outward, a `DataExchangeHandler` that is a remote
stub turns a call into a request on the wire and the reply into a return or a throw. The handler is the one seam: an
implementation, a router, a decorator and a remote stub are all handlers, and nothing that calls one knows which it
holds. Bind ships no proxy, so the `java.lang.reflect.Proxy` over a handler, which turns a Java call into a handler call,
is this layer's to build. Neither direction is written per projection: HTTP, gRPC and the rpc wire form all dispatch
through the one handler, and a client-side stub in any of them is one handler.

## The join

`TsonServiceProtocol.join(tson, schemaId, protocol, type)` holds a resolved `!service.protocol` entry against the
`DataProtocol` that `DataBindContext.getProtocol` reads from an interface, finding each exchange with
`DataProtocol.exchange(name)`. It is the exchange-level counterpart of `prepareToRead` (`design/binding.md`): a
disagreement is an `IllegalArgumentException` at startup naming the schema, the exchange and the Java method, never a
500. The result holds each exchange by its `TsonExchangeName`, in the order the schema writes them, with the `DataExchange`
serving it, and the `DataProtocol` an invoker or a handler comes from. It lives in `tson-service`, the layer that knows
both sides.

**What it refuses.** A schema exchange with no Java method; two exchange keys that find one method (`place_order` and
`placeOrder`), which the name rule makes possible; a Java exchange no schema exchange names; a request or a response one
side declares and the other does not; a request, a response or an error whose type and descriptor disagree; a declared
error the method does not declare and the reverse; and one error named twice, two names of the schema that bind to one
class. It refuses too a schema that is not resolved, an entry that is no `service.protocol` or whose value did not bind,
and an interface bind refuses, naming the protocol.

**How types agree.** A type the schema writes is landed as a reader lands it (`internal.Landed`): past an extern to the
foreign entry, past every plain alias hop, each name looked up in the namespace of the entry that writes it. Then a
record agrees by the class its landed entry binds to, asked of the `Tson`'s bind context by the declaring schema and
the entry's name, compared with the descriptor's `dataClass()`, and a record nothing binds is refused. An array agrees
by kind and element, a map by kind, key and value. An atom or an enum agrees when the atom family a read uses produces
the descriptor's class, the question the library asks at a record's atom field, so `int32` agrees with `int` and not
with `String`. A choice agrees with a closed union, one whose members are all known at startup, when each variant is a
record bound to a member and each member is the class of a variant. A request and an error are records on both sides.

**What it does not judge.** A tuple, a namespace type, a record written in place, a reference, `void`, a dependent type and
an application are refused as shapes the join does not judge, with what to write instead; so a protocol answering a namespace
type cannot be joined. A choice against an open union is not judged either. Nor are an exchange's facts: one marked both safe
and destructive is refused only when an http projection binding its protocol is read, and a protocol no projection binds is
not judged on them.

**What it does not check.** A record's fields: the join compares the class a record binds to and relies on the schema
having been prepared for reading (`design/binding.md`), where a field with no component, or a component no field fills,
is refused. Facets: an array's item bounds, a map's, an atom's `min`, `max` or `pattern`, since the family is asked
whether it produces the class and never whether every value of the class is one the type admits. A bridge's conversion:
the invoker takes the bridged class and returns it, so a caller converts a bridged request with the bridge's `toObject`
before invoking and a response with its `toData` before writing it.

**It asks past every alias hop; the HTTP reader asks as written.** The join asks the bind context for the entry a type
lands on past every plain alias hop, which is what a TSON read asks. The HTTP reader (`ChosenTypes`, `TsonPayloadType`)
asks a plain alias as written (`design/service-reader.md`), so a payload type that is an alias of a record is the
alias's name to a route and the record to the join. `BACKLOG.md`, "A plain-alias payload type is named as what a read
asks", holds the question.

**What is built and what is not.** Dispatch from a call document, the rpc wire form, a `!service.protocol` document
written from a `DataProtocol` and the proxy over a handler are not built; a lookup from a `DataExchange` back to its
`TsonExchangeName` is not either. The join's atom check copies one rule from the compiler for the kernel's `boolean`, and
its closure test for a union is its own; both are pinned upstream (`UpstreamGapsTest`).

`getProtocol` analyses an interface whole on its first call and caches the result per context, so every call for one
interface returns the same `DataProtocol`, which is immutable; a refusal is a `DataBindException` at that call. The join
therefore calls it during startup on the startup thread, as it resolves schemas, and request threads share the result for
reads only (`CLAUDE.md`, "The startup rule").

Bind has already refused what is wrong with the interface by the time the join runs, so the join does not re-check it:
anything but an interface, overloads and names that are one under the rule, a method with type parameters or a type
variable in its signature, more than one parameter or one that is no record, a promise-typed return, a thrown type that
does not bridge through `ToData`, and two declared errors related by subclassing or bridging one record. A sealed
interface is accepted; default and static methods and the signatures of `equals`, `hashCode` and `toString` are not
exchanges. The interface and every class its signatures name must be public in a package its module exports to every
module: a package exported to `io.ltr8.bind` alone is refused, since the handles come from the public lookup.

Because both sides are in the data view, every check is a comparison of shapes the join already has words for: a request
record against a request record, an error set against an error set. None of it asks what a Java exception means; the
bridges answered that before the join ran. The same holds for the other direction, a `!service.protocol` document
written from a `DataProtocol`: it writes the data view the protocol already holds, and only what has no language
counterpart (`@service.safe`, `@service.idempotent`, `@service.destructive`) must come from elsewhere. The Java name to
the data name is the join's to own, since bind maps no Java name to a data name.

## The same paradigm one layer up: the HTTP projection

The projection is the data view of HTTP, as a `DataProtocol` is the data view of a call. `http.tn` takes its
vocabulary from the protocol's own registries and nothing else: RFC 9110's verbs, a header name as an RFC 9110 token
held folded as HTTP/2 and HTTP/3 put it on the wire, a media type per RFC 9110 §8.3.1, a path as an RFC 6570 template,
a status code. It describes an HTTP exchange in HTTP's terms, never in a framework's or a language's.

The bridge to the language is `placement` (`design/http-projection.md`), which maps the exchange's path, query,
headers and body onto the request record; from there the join reaches the language. So the language is reached by two
bridges from the data side, and never by a framework's annotations. That is why an adapter is a translation layer with
no TSON knowledge of its own (`design/adapters.md`), and why three frameworks share one description.

HTTP's semantics are version-independent, as RFC 9110 is written to be, so HTTP/3 over QUIC changes nothing in the
vocabulary. What it adds is a reason the call's facts matter on the wire: QUIC's 0-RTT early data may be replayed, and
RFC 8470 admits only a safe or idempotent request in it, answering 425 (Too Early) to one that must not be replayed.
`@service.safe` and `@service.idempotent` on an exchange, which the projection checks against the verb, are exactly what
a client needs to decide on early data and a server needs to refuse it: a projection-level fact derived from a
call-level one, which is the layering doing its job. Priorities (RFC 9218) and trailers are the other constructs of that
era a projection could name. A streaming endpoint, over a long-lived stream or WebTransport, would be a new record
composing `http.endpoint`, the way the layer's own doc says a new kind of endpoint arrives.

The constructor's name follows the same paradigm: a projection is named for the transport it projects a protocol onto,
so `tson-http` implements `!http.projection` as a gRPC projection of the same protocol would be `!grpc`, one projection
among several and not the description of the service.

## What this layer is not

- **Not a client library.** A model calls a projection of the protocol (`BACKLOG.md`, "An LLM-facing projection of a
  protocol"), and any other caller REST: the published description, the self-describing responses and the deployment
  descriptor (`BACKLOG.md`, "Client"). A proxy over a handler is a language convenience, not the contract a caller is
  held to.
- **Not the agent experiment.** `experiments/agent/` is the draft of the LLM-facing projection, which sits over the
  protocol beside the HTTP one (`design/service-description.md`, "What the layer is for"); a decision that is the
  projection's alone does not move the protocol description.
