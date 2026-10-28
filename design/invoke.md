# The interface layer: the data view of a method (tson-invoke, the join in tson-service)

How a method is described below the HTTP projection, and why the description takes the data's side rather than the
language's. The modules are backlog items; this note fixes the paradigm they are built to. `BACKLOG.md`, "The module
split", says what each module holds and in what order they land.

**Invariants**

- The descriptor describes a method in the data view: a request shape (named, typed fields in order) and an outcome
  union (the response and the declared errors, no member privileged beyond the response being the success). It never
  describes a parameter list, a return type or a throwable as such.
- The bridge absorbs the language. A parameter list, a return type, a thrown exception, a future or whatever a
  language's idiom is are bridged into the request shape and the outcome union, as tson-bind's `DataClassBridge` stands
  a language form in for a data form.
- The join compares like with like: a resolved `!interface` against a descriptor in the same view, so the check is
  between two statements of one call and never between a schema and a language idiom.
- A method's identity is its name in its interface (`InterfaceMethod`), never the request type: two methods may take
  the same record.
- The descriptor holds `Class` and `MethodHandle` and nothing else, and knows no schema; what it cannot check, the join
  does.

## Why the data view, and not the language's

An rpc call is a request record, a response record and a set of error records. That is what a method is once the
language's spelling is bridged away, and it is what an `!interface` in `meta-service.tn` already says: `request`,
`response`, `errors`. A language says the same thing in its own idiom: Java with a parameter list, a return type and a
`throws` clause; a functional language with a function from a product to a sum; C with arguments and an out-parameter.
The two views differ only slightly in shape. They differ decisively in direction.

Taking the data's side means the mapping to a language always runs from the same side. Whether the target language is
object-oriented, functional, procedural or something else does not matter: each language supplies its bridges, and the
model, the join, every projection (HTTP, gRPC, the rpc wire form) and every port carry the same facade. Had the
descriptor been the language's view, every port would re-derive the join from its own idioms, and the projections would
diverge with them.

This is tson-bind's own philosophy, applied one level up. A `DataClass` describes a class in the data model's terms: a
`DataClassRecord` is fields present or absent with a constructor and accessors as `MethodHandle`s, a `DataClassUnion`
is a tagged union whether Java spells it as a sealed interface, an abstract class or an embedded union, and a
`DataClassBridge` (built from an `io.ltr8.annotation.DataBridge`) carries a `toData` and `toObject` pair between a
class and the data class that stands in for it. The language's representation is adapted to the data model, never the
reverse. `tson-invoke` does for a method what `ltr8-bind` does for a class, and depends on as little: the language alone.

## Where the two views differ, and how each resolves

**Arity.** A parameter list is a record shape, which is what tson-bind already sees in a record's canonical
constructor. A Java method taking one record and a method taking the same fields spread across its parameters both
bind to the same request shape, the second by name. Java keeps a method's parameter names only when compiled with
`-parameters`, where record components always carry theirs; the join refuses a spread form it cannot name rather than
falling back to position.

**Outcome.** The language has a privileged normal path and an exceptional one; the call has a union. The return type
and the declared throwables are the language's spelling of that union. A thrown type binds to an error record through a
bridge, since an exception cannot be a record but can carry one. Whether a projection throws or writes an error is the
projection's choice, and the interface layer makes none. That is how the HTTP boundary's invariant, that a business
error is written and not thrown (`design/service-description.md`, "Business errors compose `problem`"), holds at the
boundary and nowhere else.

**Absence.** A method with no parameters is a request absent; `void` is a response absent. Both are the data model's
absence, not a special case: `cancel_order` in the orders fixtures declares no response, so its Java method returns
`void`.

**Identity.** The request type cannot address a call: `get_order` and `cancel_order` both take `order_ref` in the
orders fixtures. The name does, as `InterfaceMethod` holds it: the interface's entry and the method's key in it. A
descriptor that keyed dispatch by request class would merge two calls the schema keeps apart.

**Context.** A network call carries what a method does not: headers, an idempotency key, the caller. The service's
placement maps it, and the descriptor never sees it. This is a difference between layers, not between views.

**Two directions from one descriptor.** The invoker handle dispatches a call that arrived as data, taking a target and
a request and returning the outcome. A proxy goes the other way, turning a language call into a request and the outcome
back into a return or a throw. Neither is written per projection: HTTP, gRPC and the rpc wire form all dispatch through
the one handle, and a client-side stub in any of them is the one proxy.

## The join

The join lives in `tson-service`, the layer that knows both sides. It holds a resolved `!interface` against an invoke
descriptor at startup, by naming convention (`place_order` to `placeOrder`), and fails startup on a mismatch in either
direction: a Java method with no schema method, a schema method with no Java method, a request or response bound to a
record other than the method's, or an outcome member that is neither the response nor a declared error. It is the
method-level counterpart of `prepareToRead` (`design/binding.md`): a mismatch is a startup failure, never a 500.

Because both sides are in the data view, every check is a comparison of shapes the join already has words for: a
request record against a request record, a union against a union. None of it asks what a Java exception or a
`CompletableFuture` means; the bridges answered that before the join ran. The same holds for the other direction, an
`!interface` document written from a descriptor: it writes the data view the descriptor already holds, and only what
has no language counterpart (`@safe`, `@idempotent`) must come from elsewhere.

## The same paradigm one layer up: the HTTP projection

The projection is the data view of HTTP, as the descriptor is the data view of a call. `meta-service.tn` takes its
vocabulary from the protocol's own registries and nothing else: RFC 9110's verbs, a header name as an RFC 9110 token
held folded as HTTP/2 and HTTP/3 put it on the wire, a media type per RFC 9110 §8.3.1, a path as an RFC 6570 template,
a status code. It describes an HTTP exchange in HTTP's terms, never in a framework's or a language's.

The bridge to the language is `placement` (`design/service-description.md`), which maps the exchange's path, query,
headers and body onto the request record; from there the join reaches the language. So the language is reached by two
bridges from the data side, and never by a framework's annotations. That is why an adapter is a translation layer with
no TSON knowledge of its own (`design/adapters.md`), and why three frameworks share one description.

HTTP's semantics are version-independent, as RFC 9110 is written to be, so HTTP/3 over QUIC changes nothing in the
vocabulary. What it adds is a reason the call's facts matter on the wire: QUIC's 0-RTT early data may be replayed, and
RFC 8470 admits only a safe or idempotent request in it, answering 425 (Too Early) to one that must not be replayed.
`@safe` and `@idempotent` on a method, which the projection checks against the verb, are exactly what a client needs to
decide on early data and a server needs to refuse it: a projection-level fact derived from a call-level one, which is
the layering doing its job. Priorities (RFC 9218) and trailers are the other constructs of that era a projection could
name. A streaming endpoint, over a long-lived stream or WebTransport, would be a new record composing `endpoint`, the
way the layer's own doc says a new kind of endpoint arrives.

The constructor's name follows the same paradigm: a projection is named for its protocol (`BACKLOG.md`, "The
description").

## What this layer is not

- **Not a client library.** The agent-facing surface is REST: the published description, the self-describing
  responses and the deployment descriptor (`BACKLOG.md`, "Client"). A proxy over the descriptor is a language
  convenience, not the contract a caller is held to.
- **Not the agent experiment.** `experiments/agent/` is future work the layer is not built toward
  (`design/service-description.md`, "What builds on the interface"); a decision that is the agent's alone does not
  move the descriptor.
