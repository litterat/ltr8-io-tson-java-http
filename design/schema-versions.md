# Serving several schema versions (`TsonSchemaVersions`)

Why versions coexist, why bind mode needs a `DataBindContext` per version, how a request is routed and a response
negotiated, and the two ways to model the Java side. Current form only.

**Invariants**

- A published schema is immutable (§3.5): a shape change is a new identity beside the old, and a server serves both.
- Reading is governed by the body's own schema (`route`); writing is negotiated through `TSON-Accept-Schema`
  (`chooseResponseVersion`). `TSON-Schema` on a request never means "what I want back".
- `route` refuses a body naming a version this endpoint does not serve, and one naming none. Never add a guessing
  fallback; `defaultVersion` is off by default for the same reason.
- `Routed.schemaId()` is the registered id, not the spelling the document used.
- Binding uses the canonical constructor only. A version is selected by routing or a `@Profile`, never by overloads.

## Serving several schema versions

§3.5 makes a published schema immutable: a shape change is a new document under a new name (`order-1.tn`,
`order-2.tn`), so versions coexist rather than replace each other, and a server outliving one of its clients
serves both. `TsonSchemaVersions` is that, and `TsonDocumentPeek` is what lets it route.

**A `DataBindContext` per version, because binding is name-based.** `DataNameBinder.resolve(String)` is handed a
schema *type name* and nothing else — no schema, no version. Both versions declare `order`, so one binder cannot
map it to two classes. The failure is at least loud: *"the schema's root type `order` binds to OrderV1, which is
not assignable to the requested OrderV2"*. Tree mode has none of this difficulty; one `Tson` holds every version
happily, because no classes are involved. Reach for `TsonSchemaVersions` only in bind mode.

**Reading is governed, writing is negotiated.** A request body names the schema that governs it and `route`
obeys it — a JSON one through the `TSON-Schema` header alone, since it can carry no directive: `route(body,
header, contentType)` routes it without a peek, and `Routed.readObjectAs(rootType, class)` reads whichever
encoding arrived, so a handler writes one read per version rather than one per version per encoding.
`Builder.acceptingJson()` makes every version's codec admit JSON. A response has no such anchor — a GET carries no body
at all — so `chooseResponseVersion` reads the client's `TSON-Accept-Schema` and picks from what this endpoint serves. Do
not reach for `TSON-Schema` on a request to mean "what I want back": it means what the request body *is*, and a field
whose meaning depends on the method is worse than two fields.

**Routing is a safety feature, not a convenience.** A codec built for v1 will read a v2 document and silently
drop what its class has no component for — `OrderV1[sku=A, quantity=1]`, no error, **no diagnostic even
collecting**. So `route` refuses a document naming a version this endpoint does not serve,
and refuses one naming none. Do not add a fallback that guesses; that is the failure it exists to prevent.
`defaultVersion` exists for an older unversioned client and is off by default for the same reason.

**The schema is honest, and migration across versions is the developer's call.** A v1 document read into a
class that also serves v2 fills the v2-only fields from the constructor that read it — and that constructor
*is* the migration rule, written by whoever owns the domain. It is not a value the format invented: v1's
schema declares no currency, v2's declares a required one, and a document written as v2 has one because
someone decided it does. **Do not go looking for a defaulted-versus-given distinction; TSON deliberately does
not track provenance**, and adding it would push a domain decision back into the format. Written down because
reasoning from JSON-Schema habits produces the opposite conclusion, confidently.

**Two ways to model the Java side**, both tested: a class per version, switching on `Routed.schemaId()`; or
one class holding the union of every version's fields, with a `@Profile`-annotated constructor per version and
`version(…, profile)` naming it. The second **requires** the profile — without one, strict binding refuses the
class, because the union is a shape no version's schema declares, and that refusal is what makes the profile a
decision rather than a default. Two traps: a profile naming no constructor falls back to the canonical one (so
the version whose shape *is* the class needs no annotation), and `@Profile`'s `fields` is effectively required,
since a secondary constructor's parameter names are `arg0`/`arg1` without `-parameters`.

**Multiple constructors do not select a version.** This is the tempting model and it does not work: binding
always uses the canonical constructor — the sole public one, or the `@Record`-annotated one — and passes `null`
for a field the schema does not declare. Measured, not assumed: a two-argument constructor stamping a marker was
never called. A second constructor is for your code, invisible to binding.

**`Routed.schemaId()` is the registered id, not what the document spelled.** A client may write the same
identity with a different scheme or a `?sha256=` pin (§2.2.1); a caller switching on the version must not see
that. An earlier version of this returned the reference and would have broken the moment a client pinned one.


