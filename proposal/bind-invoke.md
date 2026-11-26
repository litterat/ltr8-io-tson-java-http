# Method descriptors in `ltr8-bind`: a Java interface in the data view

**Status:** superseded by what landed in `ltr8-bind`: `DataBindContext.getProtocol` with `DataProtocol`, `DataExchange`
and `DataExchangeHandler`, all in `io.ltr8.bind`. Nothing here was adopted, and no probe was written in this repo. The
body below is the record of what was proposed here, and `design/invoke.md` holds the contract as built.

**Where what landed departs from this proposal**, so the body is not read as the design:

- **Names and placement.** The descriptors are `DataProtocol` and `DataExchange`, in `io.ltr8.bind` beside `DataClass`,
  reached through `DataBindContext.getProtocol`. There is no `io.ltr8.bind.invoke` package, and the analysis stays in
  the unexported `io.ltr8.bind.analysis`.
- **One record or nothing.** A method takes one record as its request or no parameter. The spread form is out, and a
  single parameter that is not a record (`getOrder(String id)`) is refused.
- **An error is thrown.** The invoker returns the response and lets every throwable through untouched, so there is no
  outcome carrier. The exchange maps a throwable to its declared error on request (`error(Throwable)`).
- **One seam, `DataExchangeHandler`.** An implementation, a router, a decorator and a remote stub are all handlers, and
  `DataProtocol.handler(target)` is the one direction bind builds. There is no proxy in the library at all: a Java call
  turned into a handler call is the consumer's to write.
- **Names run from the data's spelling to Java's.** `place_order` and `placeOrder` both find the exchange `placeOrder`.
  Nothing maps a Java name to a data name, so there is no override annotation, and writing a protocol document from a
  descriptor is this project's mapping to own.
- **Refusals.** A `throws` clause naming a type that bridges to nothing is refused, where this proposal lets it
  propagate. A promise-typed return (`Optional`, `Future`, `CompletionStage`, a publisher, a stream) and two declared
  errors related by subclassing or bridging one record are refused as well. A sealed interface is accepted, where this
  proposal refuses one.
- **A `ToData` inherited from a superclass.** Bind refuses it with a message naming the fault, where this proposal
  stated only that it does not qualify.

## Summary

`ltr8-bind` (JPMS module `io.ltr8.bind`) describes a Java class in the data view: a `DataClassRecord` is fields with a
constructor and accessors as `MethodHandle`s, a `DataClassUnion` is a union, a `DataClassBridge` stands one class in for
another. It describes no method. The proposal: a new exported package `io.ltr8.bind.invoke` describing a Java interface's
methods the same way, so a call is a request record, an outcome union and a handle, and one descriptor drives both
directions, an invoker that dispatches a call that arrived as data and a proxy that turns a Java call into data. The
paradigm is `design/invoke.md`; this proposal is where it lands in code.

The proposal holds wherever bind lives. It is a library that depends on the language and `ltr8-annotation` alone, and
moving it out of the sibling repo changes where the work is done and nothing in what is asked.

Examples use the orders protocol of `tson-http`'s test fixtures (`orders-1.tn`), written as the interface that serves it:

```java
public interface Orders {
    Order placeOrder(NewOrder request) throws SkuNotFoundException;
    Order getOrder(OrderRef request) throws OrderNotFoundException;
    OrderPage listOrders(OrderQuery request);
    void cancelOrder(OrderRef request) throws OrderNotFoundException;
}
```

## What it is not

- **No schema.** The descriptor holds `Class`, `Type` and `MethodHandle` and nothing else, and checks nothing against a
  protocol. The join, which compares a resolved `!service.protocol` with this descriptor at startup, stays in this
  project's `tson-service` (`design/invoke.md`, "The join").
- **No naming of exchanges.** A method has a data name; which exchange of which protocol it serves is the join's.
- **No service facts.** `@service.safe`, `@service.idempotent` and `@service.destructive` have no Java counterpart and are
  not bind's.
- **No transport.** A handler taking a request and returning an outcome is the seam; what carries it is a projection's.

## The design

### `DataInterface`

The descriptor of a Java interface, obtained through a `DataBindContext` (a `getDescriptor`-style entry), so the request,
response and error classes of a method are described by the same context that binds them elsewhere. It holds the
interface's `DataMethod`s in a stable order by data name, independent of declaration order, which reflection does not
promise.

Analysis refuses, with a `DataBindException` naming the interface and method, and a call never discovers it:

- two methods whose names mangle to one data name (overloads);
- a generic method, whose request or response is not a class the context can describe;
- a spread parameter (below) that cannot be named.

### `DataMethod`

- **the data name**: the method name mangled from camelCase to snake_case (`placeOrder` to `place_order`), the counterpart
  of the snake_case to PascalCase mangling `DataNameBinder` applies to type names;
- **the request**, an optional `DataClass`;
- **the response**, an optional `DataClass`;
- **the declared errors**, each a `DataClass`, one per declared throwable that bridges to a data class;
- **the invoker**, a `MethodHandle`.

### The request, one shape in two spellings

A method taking one parameter whose class binds as a record has that record as its request: `placeOrder(NewOrder)`.

A method taking several parameters is the same record's fields spread. `order_query` is `{ status?  page?  page_size? }`,
so the one exchange has both spellings:

```java
OrderPage listOrders(OrderQuery request);
OrderPage listOrders(Optional<String> status, Optional<Integer> page, Optional<Integer> pageSize);
```

The two are alternatives for one interface, not overloads. An optional field is held as the record holds it.

Each parameter is named by the rule bind already applies to a constructor's parameters (`RecordComponentFinder`): a
`@Field` on the parameter, otherwise `Parameter.getName()` when the class was compiled with `-parameters`, otherwise the
method is refused. `@Profile` has no counterpart here, since it targets a constructor only. A spread request has no class
of its own, so its value is the field values in order, as `io.ltr8.bind.mapper.ArrayMapper` already carries an object as
an `Object[]`.

The naming rule is why the code belongs in bind: it is bind's, and `io.ltr8.bind.analysis` is not exported, so a module
outside would copy it.

No parameters is a request absent, and `void` is a response absent. Both are the data model's absence and neither is a
special case: `cancelOrder` declares no response in `orders-1.tn`.

### The outcome

The invoker has type `(Object target, Object request) -> Object`. It never throws a declared error: it catches a declared
throwable and returns its data value through the bridge's `toData`, so every member of the outcome union arrives as data.
An undeclared throwable is no member of the union and propagates. What a projection makes of it (a 500 over HTTP) is the
projection's.

A declared error is a `Throwable` subclass implementing `io.ltr8.annotation.ToData<S>` with a public constructor taking
`S`, the shape `DefaultRecordBinder.resolveBridge` builds a `DataClassBridge` from. `SkuNotFoundException` extends
`Exception`, carries the `SkuNotFound` record and is described as that record. Read from the source and not measured:
the binder reads the class's own generic interfaces, so the class must declare `ToData<S>` itself and one inherited
from a superclass does not qualify; and the handles come from `MethodHandles.publicLookup()`, so the class and its
constructor taking `S` must be public. Whether a `Throwable` passes is for the probe.

### The proxy

From a `DataInterface` and a handler taking a `DataMethod` and a request and returning an outcome, a
`java.lang.reflect.Proxy` implementing the interface. A Java call becomes a request, and the outcome becomes a return or,
for an error member, the bridge's `toObject` and a throw. Dispatch is through `java.lang.invoke` handles bound at
analysis, never `Method.invoke`.

### Outside the first cut

`CompletableFuture` and `CompletionStage` returns, where the response is the future's argument, and streaming. Each is
another bridge over the same descriptor and neither changes its shape.

## Why bind, and what it costs

Each piece already has a bind type: a request is a `DataClassRecord`, an outcome member a `DataClass`, an error a
`DataClassBridge`, a handle a `MethodHandle`. The parameter-naming rule is bind's and unexported. The constraints are the
same as bind's, the language and `ltr8-annotation` alone and no schema. And it makes bind whole as a library describing
Java in the data view, classes and calls both, useful to an rpc consumer with no TSON.

It costs:

- **A wider job.** Bind binds a data value to a Java object; this describes calls. The method side lives in its own
  package, `io.ltr8.bind.invoke`, so the class-binding core is untouched and a consumer of bind alone loads none of it.
- **A repo this project does not edit.** The work lands in the library, so this project's join waits on it.

## What this project does with it

`tson-service` requires `io.ltr8.bind` directly and builds the join over a `DataInterface`. This project's main sources
already import `io.ltr8.bind.DataBindContext`, `DataNameBinder` and `DataBindException`, reached through `io.ltr8.tson`
today; a direct requirement states what the layer uses. The change this asks of the library is recorded in
`UPSTREAM.md`.

## Open questions

- **The outcome carrier.** The bare return cannot tell a response from an error when both are one class. Either the
  invoker returns a small carrier naming which member of the union the value is (recommended: the caller never inspects a
  class to learn what the call meant), or the caller tells by class and the analysis refuses a method whose response class
  is also a declared error's.
- **A single parameter.** `getOrder(String id)` takes an atom, so it is a one-field spread named `id`. A single record
  parameter meant as a one-field request holding a record needs a way to say so, since by default it is the request.
  Candidates are `@Field` on the parameter and a distinct annotation. Which?
- **Static and default methods.** Recommended: not members of the descriptor, since a caller does not dispatch to them.
  Does any consumer need one?
- **A data-name override.** Whether an annotation on the method overrides the mangled name, for a name that does not
  round-trip (`getHTTPStatus`). Without one the join renames at its end.
- **A throwable that is no `ToData`.** Recommended: it is not a declared error and propagates. Whether analysis should
  refuse a `throws` clause naming a checked exception that bridges to nothing, since the call can then fail in a way the
  descriptor does not describe.
- **The return type of a declared-error-only method.** Whether `void` with errors is a response absent and nothing more.

## What a probe would pin

The probe, against bind's public surface, before adoption:

- **The throwable bridge.** Whether `DataBindContext` gives a bridged descriptor for a `Throwable` subclass implementing
  `ToData<S>` with a constructor taking `S`. Nothing here is asserted about it.
- **The request record's constructor handle.** Its shape (`DataClassRecord.constructor()`, receiver and argument types)
  and how a spread request's field values compose with it and with `ArrayMapper`.
- **No names under this build.** That a spread method's parameters carry no names in this build, since neither this
  repo's Gradle build nor the sibling's passes `-parameters` to `javac`, so a spread method is named by `@Field` here or
  refused.
- **A hand-assembled invoker over `Orders`.** The handle composition for `placeOrder` and `cancelOrder` assembled by hand
  from `MethodHandles`, which is the part of the design no one has run: that it dispatches, returns a declared error as
  data, and lets an undeclared one propagate.

## Recommended verdict

Superseded: the sibling's draft is the design under review, and this project's join is built over its descriptors.
