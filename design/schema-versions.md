# Serving several schema versions (`TsonSchemaVersions`)

Why versions coexist, what binding two versions takes, how a request is routed and a response negotiated, and the two
ways to model the Java side. Current form only.

**Invariants**

- A published schema is immutable (§3.5): a shape change is a new identity beside the old, and a server serves both.
- Reading is governed by the body's own schema (`route`); writing is negotiated through `TSON-Accept-Schema`
  (`chooseResponseVersion`). `TSON-Schema` on a request never means "what I want back".
- A message's binding is a set of schemas, and its version is the one member this endpoint serves. `route` refuses a
  binding naming no version it serves, one naming no schema at all, and one naming two of its versions. Never add a
  guessing fallback; `defaultVersion` is off by default for the same reason.
- `Routed.schemaId()` is the registered id, not the spelling the document used.
- The binder is asked a type by its declaring schema's canonical identity and its name there, so one context binds two
  versions' `order` to two classes (`TsonBindings.bySchema`). A context per version is needed only for the `@Profile`
  model, one class serving both; the routing and its refusals hold either way.
- Binding uses the canonical constructor only. A version is selected by routing or a `@Profile`, never by overloads.

## Serving several schema versions

§3.5 makes a published schema immutable: a shape change is a new document under a new name (`order-1.tn`,
`order-2.tn`), so versions coexist rather than replace each other, and a server outliving one of its clients
serves both. `TsonSchemaVersions` is that, and `TsonDocumentPeek` is what lets it route.

**One context binds two versions; a context per version is for the `@Profile` model.** A reader asks the binder for a
type within the canonical identity of the schema declaring it, `DataNameBinder.resolve(namespace, name)`, so
`order-1.tn`'s `order` and `order-2.tn`'s are two questions. `TsonBindings.bySchema`, `DataNameBinder.byNamespace` over
a map per schema, binds them to two classes through one `Tson`, the same whether a body imports a version or a
description names both by extern (`TsonServiceDescriptionExternTest.twoVersionsBindToTwoClassesThroughOneContext`,
`ExternProbeTest.oneContextBindsTwoVersionsByTheDeclaringSchema`). A name-only map, `TsonBindings.of`, answers every
schema alike, so it maps `order` to one class for both, and reading the other version is loud: *"the schema's root type
`order` binds to OrderV1, which is not assignable to the requested OrderV2"*
(`TsonSchemaVersionsTest.oneBinderCannotServeTwoVersionsOfOneTypeName`). A context per version remains where one class
serves both through `@Profile`, since a profile is fixed when a context is built. `TsonSchemaVersions` holds a codec per
version in both models, for the routing and its refusals below, which no binding replaces. Tree mode has none of the
binding question; one `Tson` holds every version happily, because no classes are involved. Reach for
`TsonSchemaVersions` in bind mode, where routing is what keeps a v2 document out of a v1 class.

**Reading is governed, writing is negotiated.** A request body names the schema that governs it and `route`
obeys it — a JSON one through the `TSON-Schema` header alone, since its in-band `$import` is not read: `route(body,
header, contentType)` routes it without a peek, and `Routed.readObjectAs(rootType, class)` reads whichever
encoding arrived, so a handler writes one read per version rather than one per version per encoding.
`Builder.acceptingJson()` makes every version's codec admit JSON. A response has no such anchor — a GET carries no body
at all — so `chooseResponseVersion` reads the client's `TSON-Accept-Schema` and picks from what this endpoint serves. Do
not reach for `TSON-Schema` on a request to mean "what I want back": it means what the request body *is*, and a field
whose meaning depends on the method is worse than two fields.

**Routing is a safety feature, not a convenience.** A codec built for v1 will read a v2 document and silently
drop what its class has no component for — `OrderV1[sku=A, quantity=1]`, no error, **no diagnostic even
collecting**. So `route` refuses what it cannot place.

**The version is the served member of the binding.** `TsonSchemaHeader.resolve` gives the governing set — header and
imports agreeing as sets, or whichever is present (`design/codec.md` has the table) — and a body importing its version
and a helper schema beside it (`order-1.tn` and `money.tn`) is one binding of two members, read as one namespace. The
version is the member whose canonical identity this endpoint serves; the others are schemas the body also uses and are
not the version question. `Routed.schemas()` is the whole set, the version spelt as registered, and
`Routed.readObjectAs` reads a TSON body under all of it. A JSON body is read against the version alone, since the JSON
reader states one schema. Each refusal is a 400:

- **No version this endpoint serves** — `unsupported-schema-version`, *"this endpoint does not serve '…'; it serves
  [...]"*, the set listed where it has several members. The same type answers a body import that is not a schema
  identity at all; a header member that is none is `unusable-schema-reference`, from `TsonSchemaHeader`.
- **No schema named at all** — `no-schema-declared`, *"this endpoint serves several schema versions, so a message must
  name the one that governs it -- in a TSON-Schema header or an !!import directive; it serves [...]"*.
- **Two versions this endpoint serves** — `conflicting-schema`, *"this message names [...], several versions this
  endpoint serves; at most one of them governs it"*. Two versions of one schema both declare `order`, so they cannot be
  one namespace, and choosing either would be a guess.
- **Header and body naming different sets** — `conflicting-schema`, *"the TSON-Schema header names … and the body
  imports …; at most one of them governs it"*, from `TsonSchemaHeader.resolve` before routing.

Do not add a fallback that guesses; that is the failure it exists to prevent. `defaultVersion` exists for an older
unversioned client, answers only a message naming no schema at all — a binding that names schemas and leaves the
version out is refused, not defaulted — and is off by default for the same reason.

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
that.


