# Binding: classes, bindings and what a mismatch costs

The rules for a bound Java class in this project: visibility, bindings, strictness, optional lists, and which failures
are the server's own. Current form only.

**Invariants**

- A bound class is public. Bindings come from `TsonBindings.of(map)`, `of(map, profile)` or `bySchema(maps)` (in
  `io.ltr8.tson.service`, the `tson-service` module), never a hand-rolled `DataBindContext.builder()`.
- A reader asks the binder for a type by the canonical identity of the schema declaring it and its name there, and for
  an extern the entry it lands on, never the extern's local name; a TSON read of a root type that is a plain alias asks
  for the entry the alias reaches. A name-only map answers every schema alike;
  `bySchema` keys a map per schema, for two schemas declaring one name. A class bound by an extern's local name is
  refused at startup by `TsonServiceDescription`.
- One bind context per `Tson` binds a schema's value entries and a data document's values alike, so a vocabulary's
  records go in the application's map: `TsonBindings.of(TsonHttpSchema.bindingsWith(application))`, or
  `TsonServiceSchema.bindingsWith` for a description of protocols alone. The map binds application names only; `Tson.of`
  answers the kernel's vocabulary ahead of it.
- Binding is strict and a mismatch is a startup failure if `prepareToRead(schemaId)` runs at startup; there is no
  lenient mode. `prepareToWrite` is a warm-up, not a correctness measure.
- A type nothing binds is a 500 (`MissingBindingException`), not a 501. A compact constructor stricter than its schema
  is a 500 (`BIND_MISMATCH`), not a 400: a constraint a client is held to goes in the schema.
- A dependent field (`payload: kind!`) binds to an `Object` component, which `TsonBindings` registers as an atom; a
  context without it prepares clean and fails every read. Which types a sender may name in the sibling is held at
  startup (`ChosenTypes`, `design/http-projection.md`), so a sender's choice never reaches the 500 above.
- A bound class guards its own optional lists in its compact constructor and never guards a required one.
- A URI atom reads to `io.ltr8.net.Iri`, not `java.net.URI`.
- A value's name (a `meta.ref<T>` slot, `T` no constructor) binds to `ValueRef<T>` or `String`; a `TypeRef` there is a
  `BIND_MISMATCH` at `prepareToRead`. A type's name (`meta.type_ref`, a kind) binds to `TypeRef`.
- `@Unbound` means not on the wire in both directions.

## Traps

- **A bound class guards its own optional lists.** An optional field a document omits reaches the constructor
  as `null`, and the binder does not normalise it — the convention upstream follows is that the record does,
  in its compact constructor, as `RecordBody` and `TypeChain` do. `Operation`,
  `Binding` and `Document` guard theirs for that reason. A **required** list is deliberately not guarded: it never reaches a
  constructor, because the reader reports `FIELD_REQUIRED` and abandons the construction first, so a guard
  there would mask a real violation. A `references()` that returns such a `null` is refused by name — a
  `BindMismatchException` naming the class — rather than being an NPE out of `Tson.resolve`, so the failure reads
  as the class's and not the library's; the guard is still the class's job.
- **A bound class must be public.** tson-java declares no `opens` and binding only ever touches public
  constructors and methods, so a package-private record fails analysis with a bare `DataBindException:
  Failed to resolve` that names nothing useful.
- **Object binding needs bindings, and `TsonBindings` builds them.** The class passed to `readObject` is the
  expected *result*, not the mapping. `TsonBindings.of(Map.of("order", Order.class))` — or `of(map, profile)` — is
  the whole context: the map as a name binder, with the atom registrations applied. Do **not** hand-roll
  `DataBindContext.builder()` at a call site: the atom step is invisible, and missing it fails a long way from the
  cause. **The map authors the failure**: a name it does not bind is reported as a missing line of this
  application's configuration, listing what it does bind. It has no fallback, and needs none: `Tson.of` composes the
  kernel's vocabulary over the configured binder and asks it first, so every kernel name binds to the schema model
  whatever the map says, and `Tson.dataBindContext()` is that composed context.
- **One context, schemas and data alike.** The context a `Tson` is built with binds a schema's value entries as it
  binds a data document's values. A value entry binds by the name of the type it applies, as a data value does: an
  entry applying a record type (`!service.protocol { … }`) is a `ValueEntry(type, value)` holding the class the map
  gives that name, and a field typed by a namespace type binds to a `Namespace` whose values bind the same way, keeping
  the keys' order and annotations where a `Map` component loses both. An entry no class binds is held as
  a tree: the schema loads, and the bind registry refuses it with `BIND_MISMATCH` at the entry. So a vocabulary a
  schema imports belongs in the same map as the application's: `TsonServiceSchema.bindings()` is `service.tn`'s
  records as an explicit map and `TsonHttpSchema.bindings()` both layers', and `bindingsWith(application)` merges
  the application's, refusing a name both bind, since within one closure a name means one thing. The demos build their
  one context as `TsonBindings.of(TsonHttpSchema.bindingsWith(bindings))`.
- **The binder is asked the declaring schema and the entry's own name.** The library asks `DataNameBinder.resolve(
  namespace, name)` with the canonical identity of the schema declaring the type, and a name-only binder, which
  `TsonBindings.of` builds, answers by the name and ignores the schema. An extern ([TSON-SCHEMA] §5.13) is asked as the
  entry it lands on: a description's `order_v1 => !meta.extern { schema: "…/order-1.tn"  type: order }` is asked as
  `order` of `order-1.tn`, so a map keyed `order_v1` prepares clean and answers every read a 500 naming `order`
  (`ExternProbeTest.aNameOnlyBinderIsAskedTheTargetsNameNotTheExterns`). `TsonServiceDescription` refuses that binding at
  startup, where the description names the extern, naming the entry to bind instead
  (`TsonServiceDescriptionExternTest.aBindingByAnExternsLocalNameIsRefusedAtStartup`); the codec alone cannot, since it
  has no description to name the extern. The refusal reaches an extern the description declares itself and a route
  names as a payload type, and is skipped where a schema of the closure declares that name as its own entry, which the
  binding may be for. Two schemas each declaring `order`, two versions of one, bind to two classes
  through `TsonBindings.bySchema(Map.of(order1Id, Map.of("order", OrderV1.class), …))`, `DataNameBinder.byNamespace`
  over a map per schema; every schema whose types bind is keyed there, each layer's vocabulary under
  `TsonServiceSchema.ID` and `TsonHttpSchema.ID` among them, and a name asked within a schema no map holds is
  refused listing those it does. A protocol an HTTP description binds is held as a tree where `service.tn`'s identity is
  not keyed, and `TsonServiceDescription` refuses it naming the fix.
- **A type nothing binds is a 500, not a 501.** It is a `MissingBindingException` naming the map, deferred
  to the first read of that specific type (a schema legitimately declares types a consumer never binds). A 501
  would report a missing line of this server's configuration as "this library cannot do that". Pinned by
  `TsonHttpCodecTest.aTypeNothingBindsIsAServerFaultNotALibraryGap`.
- **A bound class stricter than its schema answers 500.** A compact constructor that throws on a value the schema
  admits is reported as `BIND_MISMATCH` — the class disagrees with its contract — so the client whose body the
  schema accepted gets a 500 with no detail, not a 400. A constraint a client is to be held to goes in the schema,
  where it is a diagnostic the client can act on. Pinned by
  `TsonHttpCodecTest.aBoundClassRefusingWhatItsSchemaAdmitsIsAServerFault`.
- **A value's name binds to a `ValueRef` or a `String`, a type's name to a `TypeRef`.** A slot typed `meta.ref<T>`
  with `T` no constructor (`exchange: meta.ref<service.exchange>`) holds a value by name. A `ValueRef<T>` component receives
  the name and, once delivered, the value it reaches through `get()`: a name in a schema's value entry is delivered
  when the schema links, one in a data document at the read, and one inside an annotation's value never
  (`isResolved()` false, `get()` throws). A `String` component receives the name alone. A `TypeRef` component there
  is a `BIND_MISMATCH` naming both hosts, which `prepareToRead` makes a startup failure
  (`UpstreamGapsTest.aValueReferenceIsDeliveredToAValueRefAndRefusedAtATypeRef`). A slot naming a type
  (`meta.type_ref`, `meta.ref<meta.record>`) binds to `TypeRef`, which is a name and never an application: an
  application is `ApplyBody`, written in place only inside a parametric record.
- **A URI atom reads to `io.ltr8.net.Iri`, not `java.net.URI`.** `uri`, `uri_reference`, `iri` and
  `iri_reference` all do, recognised to RFC 3986/3987 by `tson-net`, so `TsonValue.as(URI.class)` finds nothing;
  ask for `Iri` and read `text()`. A bound component may still declare `URI`.

- **Binding is strict, and a mismatch is a startup failure if you let it be.** A schema field with no
  component, or a component no field fills, is a `BindMismatchException` when the schema compiles in bind
  mode. `TsonHttpCodec.prepareToRead(schemaId)` forces that at startup and `TsonSchemaVersions` calls it —
  without it the same mistake is a 500 on the first request that reads one. `@Unbound` marks a component that
  is the class's own, and it means **not on the wire in both directions**: a response type carrying one leaves the
  field out of the body. A field's type is a name or a type written in place, which fixes the component's class, or
  a dependent type (`value?: kind!`), whose field is read per instance against the type its sibling `kind` names, so
  its component is an `Object` receiving whatever that type binds to; a narrower class is a mismatch at compile.
  **There is no lenient mode**, so a mismatch is a startup failure or nothing. **A mismatch reaching the diagnostics
  channel is still reported as `SCHEMA_ERROR` and so becomes a 400** — routing and `prepareToRead` are what keep it
  unreachable.
- **`prepareToWrite` is a warm-up, not a correctness measure.** Keep calling it at startup to move first-write
  descriptor resolution off the request thread; nothing about concurrency depends on it.

