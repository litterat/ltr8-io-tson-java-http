# Binding: classes, bindings and what a mismatch costs

The rules for a bound Java class in this project: visibility, bindings, strictness, optional lists, and which failures
are the server's own. Current form only.

**Invariants**

- A bound class is public. Bindings come from `TsonBindings.of(map)` or `of(map, profile)`, never a hand-rolled
  `DataBindContext.builder()`.
- Binding is strict and a mismatch is a startup failure if `prepareToRead(schemaId)` runs at startup; there is no
  lenient mode. `prepareToWrite` is a warm-up, not a correctness measure.
- A type nothing binds is a 500 (`MissingBindingException`), not a 501. A compact constructor stricter than its schema
  is a 500 (`BIND_MISMATCH`), not a 400: a constraint a client is held to goes in the schema.
- A bound class guards its own optional lists in its compact constructor and never guards a required one.
- A URI atom reads to `io.ltr8.net.Iri`, not `java.net.URI`.
- `@Unbound` means not on the wire in both directions.

## Traps

- **A bound class guards its own optional lists.** An optional field a document omits reaches the constructor
  as `null`, and the binder does not normalise it — the convention upstream follows is that the record does,
  in its compact constructor, as `RecordBody`, `TypeDefinition` and `TypeRef` all do. `Operation` guards
  `parameters` for that reason. A **required** list is deliberately not guarded: it never reaches a
  constructor, because the reader reports `FIELD_REQUIRED` and abandons the construction first, so a guard
  there would mask a real violation. A `references()` that returns such a `null` is refused by name — a
  `BindMismatchException` naming the class — rather than being an NPE out of `Tson.resolve`; the guard is
  still the class's job, the failure just no longer reads as a library fault.
- **A bound class must be public.** tson-java declares no `opens` and binding only ever touches public
  constructors and methods, so a package-private record fails analysis with a bare `DataBindException:
  Failed to resolve` that names nothing useful.
- **Object binding needs bindings, and the convenience is now this repo's.** The class passed to `readObject`
  is the expected *result*, not the mapping. Upstream's `bindings(Map)`/`profile(String)` are gone with the
  builder, so `TsonBindings.of(Map.of("order", Order.class))` — or `of(map, profile)` — is what builds the whole
  context here: the map as a name binder, **chained over the kernel's vocabulary rather than replacing it**,
  with the atom registrations applied. Do **not** hand-roll `DataBindContext.builder()` at a call site: two of
  those three steps are invisible, and missing either fails a long way from the cause. The map authors the
  failure on purpose — the kernel binder is the backstop, and letting it speak reports a missing line of your
  configuration as "not kernel vocabulary".
- **A type nothing binds is a 500, not a 501.** It is a `MissingBindingException` naming the map, deferred
  to the first read of that specific type (a schema legitimately declares types a consumer never binds). It
  used to present as `UnsupportedOperationException: no usable compiled reader`, which this project mapped to
  501 — reporting a missing line of its own configuration as "this library cannot do that". Pinned by
  `TsonHttpCodecTest.aTypeNothingBindsIsAServerFaultNotALibraryGap`.
- **A bound class stricter than its schema answers 500.** A compact constructor that throws on a value the schema
  admits is reported as `BIND_MISMATCH` — the class disagrees with its contract — so the client whose body the
  schema accepted gets a 500 with no detail, not a 400. A constraint a client is to be held to goes in the schema,
  where it is a diagnostic the client can act on. Pinned by
  `TsonHttpCodecTest.aBoundClassRefusingWhatItsSchemaAdmitsIsAServerFault`.
- **A URI atom reads to `io.ltr8.net.Iri`, not `java.net.URI`.** `uri`, `uri_reference`, `iri` and
  `iri_reference` all do, recognised to RFC 3986/3987 by `tson-net`, so `TsonValue.as(URI.class)` finds nothing;
  ask for `Iri` and read `text()`. A bound component may still declare `URI`.

- **Binding is strict, and a mismatch is a startup failure if you let it be.** A schema field with no
  component, or a component no field fills, is a `BindMismatchException` when the schema compiles in bind
  mode. `TsonHttpCodec.prepareToRead(schemaId)` forces that at startup and `TsonSchemaVersions` calls it —
  without it the same mistake is a 500 on the first request that reads one. `@Unbound` marks a component that
  is the class's own, and as of Revision 35 it means **not on the wire in both directions** — it was read-side
  only, so a marked component was silently *written*. If a response type here ever carries one, that is the
  change to know: the field leaves the body. **There is no lenient escape hatch any more** — upstream removed
  it, so strict is the only mode and a mismatch is a startup failure or nothing. **A
  mismatch reaching the diagnostics channel is still reported as `SCHEMA_ERROR` and so becomes a 400** —
  routing and `prepareToRead` are what keep it unreachable.
- **`prepareToWrite` is a warm-up, not a correctness measure** — it was one, before the descriptor race was
  fixed upstream. Keep calling it at startup to move first-write descriptor resolution off the request thread;
  do not treat it as load-bearing for concurrency any more.

