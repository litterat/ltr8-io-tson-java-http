# Sketch: an API description made of types, not data about types

## The files

**Three of the four are schemas; one is a data document.** That is the whole story in one line — a description
that *references* types must be a schema, because only a schema can name a type. The data one names them as
strings and checks them itself.

| File | Kind | What |
|---|---|---|
| `orders-api-3.tn` | schema | **Read this first.** The API in an ordinary schema. Imports the four below. |
| `orders-api-4.tn` | **data** | The same API as a data document, governed by `api-2.tn`. **The only one that ships.** |
| `http-api-1.tn` | schema | Reusable vocabulary: the enums, the `response`/`page` templates, the `operation` base. |
| `order-1.tn` | schema | The domain types. Versions independently of the API that exposes them. |
| `orders-errors-1.tn` | schema | Business errors, composing `problem` from the shipping `problem-2.tn`. |
| `orders-api-2.tn` + `meta-http-2.tn` | schema | The annotation design: operations as `top &`, metadata on annotations. |
| `orders-api-1.tn` + `meta-http-1.tn` | schema | The constructor design. **Works, unblocked.** The recommended one, on a condition. |
| `meta-http-3.tn` | schema | A leaner `operation` — four fields, `type_name`, one response. Superseded: `type_name` has no path to the linker, `type_ref` does. |

`orders-api-4.tn`'s governing schema is `tson-http/src/main/resources/api-2.tn`, not copied here — it ships,
and a copy would drift.


**Three designs, in increasing order of how little they need.** Nothing here ships. `SketchTest` holds each to
what this file claims, so a fix upstream shows up as a failing test rather than as nothing happening.

| | Header it needs | Carries metadata with | Status |
|---|---|---|---|
| `orders-api-3.tn` | `meta.tn` + `core.tn` — **the ordinary one** | FIXED fields | **works** |
| `orders-api-2.tn` | a custom meta layer for annotation *types* | annotations | works |
| `orders-api-1.tn` | a custom meta layer with an `operation` constructor | constructor fields | **works** |

**`orders-api-3.tn` is the one to read** — but the reason has changed, and the two designs are closer than the
table suggests.

*Not* because it is the most compact: `orders-api-2.tn` is, with **zero** extra declarations per response
(each is a field carrying a `@status` annotation), where `orders-api-3.tn` needs one named entry per response
because #13 forbids an application inside a choice.

The durable reason is that **only `orders-api-3.tn`'s metadata is in the type system, and therefore checkable**.
A FIXED status is *meant* to constrain the value — it does for a literal, and will for a template-supplied one
once `UPSTREAM.md` #14 lands. An annotation never will: it is metadata by design, and `@status:201` documents
the status without any prospect of validating it. That is the trade, and it is worth stating plainly because
today, with #14 outstanding, *neither* design actually constrains a template-supplied status.

Its other advantage is real but smaller: an ordinary header, so any TSON toolchain reads it without fetching a
custom meta-schema.

**Since `UPSTREAM.md` #11 was fixed it is also a library rather than a single file.** A schema can now reference
types from several others, so the vocabulary (`http-api-1.tn`), the domain types (`order-1.tn`), the errors
(`orders-errors-1.tn`) and the API each live where they belong, and a second version of the API imports the same
domain schema. Before the fix all of it had to be one file — which is what "a chain, not a library" cost in
practice.

## Why an API description has to be a schema

Not a preference. A consequence of how TSON layers data and types.

**Data can name a schema, and select a type within the scope it names. It cannot hold a reference to a type.**
`!!schema` names a schema; a `!order` annotation selects a type within the active scope; but a type *name*
sitting in a value position is an inert token, because no type-name namespace is active there to resolve it
against. Type-name resolution happens at type-ref positions in a **schema** document, and nowhere else.

§7.8's `extern` is the proof rather than the exception. It is the sanctioned way for one schema's data to carry
another schema's values, and it does not work by naming a type either: *"values matched by an `extern` field
MUST carry their own `!!schema` directive identifying the external schema and a `!type` annotation identifying
the type within it — schema scope changes are always visible in the data, never implicit."* Even here, the data
carries a **value** with a visible scope switch. Nothing anywhere lets data point at a type.

**So anything whose job is to relate types must live in the schema layer** — an API description, a mapping
spec, a data catalogue, a codegen config. The compensation is that such an artifact then gets resolution,
linking, imports, identity, §10 versioning and `?sha256=` pinning for free, because it is a schema and all of
that is what schemas already have.

**This also explains a pattern that runs through the whole of `tson-http`.** Every place the codec points at a
type, it needs a *(schema identity, root type name)* pair: `describing(schemaUri, rootTypeName)`,
`readObjectAs(schemaUri, typeName, class)`, the `TSON-Schema` header plus a route-supplied type. That pair is
not a design choice — it is the data layer's only way to point at a type, given it cannot reference one. Two
strings, reassembled by hand at every call site, because the thing they name is out of reach.

## The fourth design: the API as data, checking its own names

`orders-api-4.tn`. A **data** document — `!!schema`, not `!!meta` — so it is written, published and versioned
like any other document and needs nothing of the type system the other three need. It is the only one of the
four that ships.

```tson
!api {
  imports: [ ".../order-1.tn"  ".../orders-errors-1.tn"  ".../problem-2.tn" ]
  operations: [
    !operation { method: POST  path: "/orders"  request: "order"
                 responses: [ !response { status: 201  body: "order" }
                              !response { status: 404  body: "sku_not_found" } ] } ]
}
```

**The names are strings, and no care taken in that file changes it.** A data document cannot hold a reference
to a type. So the document carries its own `imports` list and its own namespace rule — the same shape as a
schema's `!!import`, one layer down — and `TsonApi.validate` resolves the names against it, reporting a name no
import declares, or one two imports declare *differently*, along with where it was written.

**What it costs, plainly.** A typo is caught when someone calls `validate`. In `orders-api-3.tn` — the same
service as a schema — a typo means the schema *fails to load*. Weaker, bought much more cheaply, and available
today, which the other three are not.

**Two traps in writing that namespace rule**, both of which this project fell into and both now in `CLAUDE.md`.
Judging ambiguity by how many imports surface a name is `UPSTREAM.md` #11's occurrence-counting bug rewritten
from scratch — imports are transitive, so one declaration arrives by several routes. And comparing the
`TypeDefinition`s instead does not work either, because linking credits each route's own `subtypes`: `problem`
seen through `orders-errors-1.tn` carries `sku_not_found` where the direct route carries none. Compare the
authored declaration — kind and body — and leave out what linking derived.

**Why the file carries no prose.** It cannot. TSON has no comment syntax, and an annotation before the root
type-ref stops the reader seeing the type-ref (`UPSTREAM.md` #16) — so a schema-governed data document cannot
document itself. Every other file here explains itself in a `@doc`; this one has to be explained from outside.
That asymmetry is not a stylistic choice, and it is worth noticing when weighing the designs.

### A schema is a data document with a defined structure — so what happens if you put other data in it?

The framing that makes the rest of this directory make sense. `schema => {type_name => type_definition}` and
`type_definition.body: top`: a schema document *is* data, and its body slot accepts anything. So putting an
`operation` in a schema needs no change to the meta-schema at all. The only question is how much the compiler
does with it.

**The answer splits cleanly, and both halves are already built.**

- **The operation's own shape** is checked by the constructor's reader — enums, refinements, record closure —
  exactly as a `!record { … }` body is. Nothing new.
- **The references inside it** are resolved by `DefinitionResolver.takesATypeRef`, which decides **by the
  slot's declared type**, not by hardcoded knowledge of the bundled meta records. It follows aliases, and the
  reason given is *"a meta layer may name the kernel's own `type_ref` something of its own"* — written
  anticipating precisely this.
- **Application semantics** — is this path a valid template, does this operation make sense — stay with the
  reader, which is where they belong.

So the reader does not have to load the schema and traverse entries hunting for its own data: the compiler
resolves the names, because it is the only thing that owns the namespace, and hands back a body already bound
to the consumer's own Java record.

**One caveat that decides which sketch to prefer.** `takesATypeRef` tests for `type_ref`. There is no generic
counterpart for `type_name`, which is resolved only where the resolver has specific handling (`supertypes`).
So `meta-http-1.tn`'s `request: type_ref` should be checked and `meta-http-3.tn`'s leaner `request: type_name`
most likely is not — the opposite of what their relative simplicity suggests. **Use `type_ref`.**

### Who checks what — the question all four designs answer differently

The one that matters when choosing, and the answer inverts the obvious reading.

| Design | The description's own shape | Its type references |
|---|---|---|
| `api-1.tn` — data, `$ref`-style | compiler | **nobody** |
| `orders-api-4.tn` — data, imports | **compiler** | this project, ~40 lines |
| `orders-api-3.tn` — ordinary schema | **almost nobody** | compiler |
| `orders-api-1.tn` — `~data & operation` | compiler | compiler |

Measured, not assumed — `SketchTest.theDataDesignIsStructurallyCheckedAndTheSchemaDesignIsNot`:

- **Data design.** `methd` instead of `method` → `UNRECOGNIZED_FIELD`, listing the fields that exist.
  `status: 42` → `ATOM_CONSTRAINT_VIOLATION`, below the minimum of 100. `method: POZT` →
  `ATOM_CONSTRAINT_VIOLATION`, listing the seven that are methods. All from the compiler, with positions.
- **Schema design.** The same misspelling is **accepted**. So is an operation with no response at all.

**Why the inversion.** A data description is validated against `api-2.tn`, which describes it completely. A
schema description *is* a schema, and **nothing describes what an operation must look like** — the meta-schema
says what a *schema* is in general. Composition with an `operation` base requires `method` and `path` to exist
and stops there; it cannot say that `response` is a choice of *this* operation's variants, so it does not
require `response` at all.

So neither *data-or-schema* design gets both, and each gets the half the other lacks. That was the sharpest
argument for `UPSTREAM.md` #15, and **#15 has since landed**: a `~data &` `operation` supplies the missing
description of an operation's shape, and `Data.references()` supplies the reference checking, which is why it
is the only row with the compiler in both columns — and now the only row that is also true. The last column
is no longer a prediction; `SketchTest` asserts every cell of it.

Worth weighing honestly: **the re-implementation the data design asks for is small and bounded** — resolve a
name against an import list, about forty lines. The schema design's gap is not something a consumer can fill at
all, because there is nowhere to state what an operation must be.

### Prior art: this is XSD's shape, and `api-1.tn` was JSON Schema's

The two shipping designs landed on opposite sides of a well-trodden divide.

**`api-1.tn` was JSON Schema's model.** JSON Schema has `$id`, `$ref`, `$defs` and **no import statement**:
every reference carries its own URI at the point of use — `{"$ref": "https://example.com/order.json"}` — and
resolution is per-reference URI resolution against a base. `api-1.tn`'s `{ schema: uri  type: text }` at every
payload is a `$ref` with the fragment spelled as a separate field.

**`orders-api-4.tn` is XSD's model.** XSD declares dependencies up front —
`<xs:import namespace="…" schemaLocation="…"/>` — then references types by *name*. That is `imports: [ … ]`
plus bare `"order"`.

**Two things XSD has that this does not.** Its processor is *specified*: an unresolved QName is a schema error
by the standard, where `TsonApi.validate` runs only if a consumer calls it. And it has **namespace prefixes**,
so a reference binds explicitly at the use site (`tns:Order`) — bare names cannot, which is why ambiguity had
to become a rule enforced here. An alias-prefixed form would delete that rule, at the cost of noise at every
use. (XSD's `schemaLocation` being a *hint* rather than a binding is also the identity-is-not-location split
§2.2.1 draws, arrived at independently.)

**OpenAPI mostly avoids the problem.** `components/schemas` holds the payload schemas *inside* the API
document, and references are JSON Pointers into the same file — `$ref: "#/components/schemas/Order"`. External
references are the historically patchy part of the toolchain; bundling into one self-contained document is the
norm, and resolution is each tool's business.

**That trick is not available here, and the reason is the interesting part.** OpenAPI can inline its schemas
because JSON Schema *is* JSON — schema language and data language are the same, so a schema is just more of the
document. TSON's schema language is TSON too, but a schema document and a data document are different *kinds*
of document, so a data-document description cannot carry its schemas inside it. The import list is not a
stylistic choice.

## The problem with `api-1.tn`, which does ship

```tson
body => { schema: uri  type: non_empty_text }
```

A schema URI and a type name, both carried as **data**. Nothing resolves either — and by the rule above,
nothing *could*. That is `$ref: "#/components/schemas/Order"` in TSON's clothing, and it is why
`TsonApiConformanceTest` exists at all: it hand-checks coherence a resolver should establish by construction,
and the reason it had to be hand-checked is structural, not an oversight.

## Templates: what they do and do not carry

I cited `page<order>` as "envelope templates work today" for several rounds before putting one in a design.
Doing it changed two conclusions.

**A value parameter can fill a FIXED field**, which is the thing that makes templates useful here:

```tson
response => <T, S> { status: status_code = S  body: T }
```

So a response is `response<order, 201>` — one line — rather than a record declaration. That is most of the
wrapper-type cost I had called the meta layer's biggest win, removed **at the schema level**. The shape now
lives once, in the template, where it cannot drift between responses. It nests, too:
`response<page<order>, 200>` resolves and is deduped by structural identity (§8.2).

**Two gaps shape how it is written.**

- An application cannot appear directly inside a `choice` (`UPSTREAM.md` #13), so each response is named as an
  entry and the choice is over the names. That costs a name per response, not a shape.
- **A value parameter filling a FIXED field does not currently constrain it** (`UPSTREAM.md` #14).
  `response<order, 201>` carries 201 and a document may still send 999; the literal
  `{ status: status_code = 201 … }` rejects it. So today the template **documents** the status where the
  record **enforced** it. That is silent under-validation, and it is the one thing that would stop me
  recommending the template form for a service that relies on the status being checked.

**What this does to the meta-layer argument below.** Point 1 — wrapper types becoming values — is now mostly
answered without a meta layer, and point 4 with it, since both were about declarations existing only to pair a
status with a body. What survives is **shape checking**, **recognisability by construction**, and **templated
operations** — and the template work has just added a fourth, which may now be the strongest of them:
**the meta form does not need FIXED fields at all**, so `UPSTREAM.md` #14 cannot arise in it. There, `status`
is an ordinary field of a `response` record and `!response { status: 201  body: order }` supplies 201 as a
value. Only the schema-level design has to *fix a field* to say what is really just a value, and that is
exactly where #14 bites.

## What belongs in the meta layer, and what it would unlock

The sharpest way to put it: **`type_ref` is the meta layer's ability to talk *about* types. Without it a schema
can only *use* types.** Everything else follows from that one line.

`orders-api-3.tn` describes by *using*: a payload appears in a field-type position, because that is the only
position where a type name resolves. So an operation ends up **containing** its payloads rather than **naming**
them. The much-admired property that an operation value is an exchange is not a design choice — it is a
consequence of describing-by-using, and it comes with a bill.

**What would move up:** the `operation` constructor with `type_ref` slots, the `response` and `parameter`
records, and the enums. `meta-http-1.tn` is that, and it resolves; only applying it is unimplemented.

### It has to be a constructor. A plain record in a meta layer buys nothing.

Worth stating because it is the obvious thing to try first. **Governance does not put names into the type-name
namespace.** A plain record declared in a meta layer cannot be composed by a schema that layer governs — the
name is not in scope:

```
'op': supertype 'plain_operation' names no type this schema declares or imports
```

It becomes usable only by *importing* the meta layer as well — and at that point governance has contributed
nothing, and the same record in an ordinary imported schema would have served. Which is exactly what
`http-api-1.tn` already is.

Governance supplies two things and only two: **the structure namespace** (`!C` application, gated on
`constructor: true`) and **annotation types whose values bind** (`UPSTREAM.md` #12). A plain record uses
neither, so a meta layer holding one is `http-api-1.tn` with extra steps.

That also explains why all three surviving points below flow from *applicability*: shape checking comes from
binding through the constructor's own reader, recognisability needs `!operation` on the wire of the schema, and
a templated operation is an `instance-template` over a **constructor**. No application, none of the three.

### What that unlocks, concretely

**1. Wrapper types become values.** *Mostly answered by templates now — see above.* Still worth stating,
because the meta form needs no name per response and no `choice` workaround. `orders-api-3.tn` declares five
types — `order_created`, `order_invalid`, `order_sku_gone`, `schema_served`, `schema_missing` — whose entire
job is to pair a status with a body. They exist because a status must be a FIXED *field*, which needs a
*record*, which needs a *declaration*. In the meta layer the same thing is a value:

```tson
responses: [ !response { status: 201  body: order }
             !response { status: 400  body: problem } ]
```

Five bookkeeping entries in the schema's namespace become zero. An API of thirty operations is the difference
between thirty declarations and a hundred and thirty.

**2. The resolver checks the operation's shape.** `!operation { … }` binds through the constructor's own
reader, so §7.2's closure rule catches a missing `method`, a misspelled `responses`, a `status` that is not a
status. Today nothing does: any record composing `operation` is an operation, and "a field annotated with a
status is a response" is prose in a `@doc`. This is the one gap the ordinary-schema design cannot close, and
composition gets only part-way — a base can require `method` and `path`, but not that `response` is a choice of
responses, because each operation's variants differ.

**3. An operation is recognisable by construction**, not by a supertype convention a stray record could also
satisfy.

**4. The schema's namespace stays domain-only** — order, problem, sku_not_found, and the operations. No
bookkeeping.

**5. The model lands in the Java record.** The largest win for a *consumer*, and the one this project measured
last. `ApiModelExtractionTest` reads the API out of `orders-api-3.tn`'s resolved form, which takes: find
entries by supertype, read FIXED field values, follow the `response` field to a synthetic `choice_…` entry,
walk its variants, follow each to an instantiation `Reference`, look up the materialised record, read `status`
and `body` — **and branch**, because an operation with one response has a direct reference where one with
several has a choice. Two structurally different resolved forms for the same idea. With an `operation`
constructor all of it is `body() instanceof Operation op` and `op.responses()`, because the type names sit in
the record as written rather than being recovered from the shapes they produced. Every consumer of such a
description would otherwise reimplement that traversal.

**6. Templated operations become possible.** `crud => <T> !operation { … }` is D9's `instance-template`
production — an API *pattern* as a checked, reusable type, which is the thing OpenAPI reaches for `allOf` and
codegen to fake. It needs `template_argument` to grow the collection case the structure-templates CR defers.

### What it costs

**The exchange property.** A `~top &` entry has no values, so the meta version describes and nothing more.
Today's version doubles as the shape of a trace or an access log for free. That is a real loss, and the honest
answer is that it was never free — it was the visible face of describing-by-using, and it is paid for in the
five wrapper types.

If both are wanted they are two declarations, which is the correct number: a contract and a log format are
different things that happen to mention the same types.

## The plainest design, and the best of the three

`orders-api-3.tn` — ordinary header, nothing else:

```tson
operation => { method: http_method  path: text }

order_created  => { status: status_code = 201  body: order }
order_invalid  => { status: status_code = 400  body: problem }
order_sku_gone => { status: status_code = 404  body: sku_not_found }

create_order => operation & {
  method:   http_method = POST
  path:     text = "/orders"
  request:  order
  response: (order_created | order_invalid | order_sku_gone)
}
```

Resolved, that is:

```
method:   REQUIRED_FIXED  value=Token[text=POST]
path:     REQUIRED_FIXED  value=Token[text=/orders]
request:  TypeRef[order]
response: TypeRef[choice_order_created_order_invalid_order_sku_gone_…]
supertypes = [operation]
```

**A FIXED field carries metadata inside the type.** `method: http_method = POST` is checked by the resolver
and survives into resolver output with its value — where a locally declared annotation's value is silently
dropped (#12). Metadata that would have sat *beside* the type sits *in* it.

**A response is a `choice`**, which is what one-of means. Each variant fixes its own status, so a reader can
see which status carries which body. The variants are **not** proved disjoint and need not be: §5.4 requires
distinct variant *types*, not disjoint value *sets*, and record-set disjointness is explicitly a case a
resolver may leave unproven. So a response value carries its tag — `!order_created { … }` — which is the
honest outcome rather than a wrinkle. The tag names the response; nothing is inferred from structure.

**Composition enforces the shape.** Every operation composes `operation`, so the resolver checks each has a
method and a path, and operations are found in resolved output **by supertype** rather than by a naming
convention. Composition narrows: an operation inherits `method` and re-declares it FIXED to its own verb.

**An operation value is an exchange.** Because an operation is an ordinary record,
`{ method: POST  path: "/orders"  request: <an order>  response: !order_created { … } }` is a real value — one
request and its reply. The same declaration is the contract *and* the shape of a trace or an access log, which
no data-shaped description can be.

What it still does not state is that `response` must be a choice of responses — each operation's variants
differ, so the base cannot say it. That last gap is what a type constructor would close.

### Two things that constrain the shape

- **A record is not permitted at a type position** (§5.2), so a parameter set is a named type
  (`get_schema_parameters`), not an inline `{ … }`.
- **`!` names reach only the governing meta's structure namespace**, so an ordinary schema cannot write
  `!text ^ { … }` unless `text` is a constructor there — refinements of core's atoms come from core.

## The annotation design

`orders-api-2.tn`, one schema, one import:

```tson
create_order => @method:POST @path:"/orders" top & {
  request: order

  @status:201 created:     order
  @status:400 invalid:     problem
  @status:404 no_such_sku: sku_not_found
}
```

Read back out of the resolved schema, that is:

```
POST /orders
    request -> request: TypeRef[name=order]
    201 ->     created: TypeRef[name=order]
    400 ->     invalid: TypeRef[name=problem]
    404 -> no_such_sku: TypeRef[name=sku_not_found]
```

**Payload types sit in field-type positions, so the resolver resolves and checks them.** Renaming
`sku_not_found` to `sku_not_fund` fails to load. That is the property the data-shaped version cannot have.

**An operation is `top & { … }`.** `top` is the root, so an entry that is neither product nor sum describes no
data value — nobody can write `!create_order { … }` and mean anything. This is meta-kernel's own device for
`reference` and `instance_template`, and it needs no `~` and no type constructor.

**Metadata rides on annotations**, which is what annotations are for: `@method`/`@path` on the operation,
`@status`/`@parameter` on its fields.

### Four rules this cost a probe each to learn

1. **Annotations before a declaration's name annotate the map *key*, and are not hoisted to the value.** They
   must come *after* the `=>`. `DefinitionResolver` says so explicitly, citing §6. So it is
   `op => @method:POST top & { … }`, never `@method:POST` on the line above.
2. **An annotation's type must be in the governing meta-schema's namespace.** Declaring
   `method => @annotation http_method` beside the operation and writing `@method:POST` is now an error saying
   so — it used to resolve clean and discard the value (`UPSTREAM.md` #12, fixed). `@doc` works only because
   `doc` is meta-kernel's. **This is the whole reason `meta-http-2.tn` exists**: it holds the annotation
   *types* and nothing else.
3. **A schema is a meta-schema only if its own `!!meta` is the meta-kernel.** There is no three-level chain: a
   layer above `meta.tn` is refused with *"is named as the `!!meta` of another schema but is not a
   meta-schema"*. So `meta-http-2.tn` is a **sibling** of `meta.tn`, not a layer on it.
4. **A type reference resolves against the schema's own namespace, not its meta's.** The meta supplies the
   structure namespace (`!C`) and annotation types; naming `status_code` — declared in the meta — from a field
   type fails. Hence the `!!import` of meta-kernel for `top`, `text`, `integer`, `uri` and `void`.

### What it does not get

**The operation's shape is convention, not enforcement.** Any `top &` entry with any fields is accepted; the
"a `@status` field is a response" rule is prose in a `@doc`. A `~operation` constructor would have the resolver
check that an operation has a method and a path — which is exactly what the other design buys, and cannot spend.

## The constructor design, which now works

`meta-http-1.tn` declares `operation` as a constructor whose instances are not types:

```tson
operation => ~data & {
  method: http_method   path: text
  parameters: [parameter]   request: type_ref?   responses: [response]
}
```

It said `~top &` and was blocked for two upstream revisions. The `data` base kind (`UPSTREAM.md` #15) is what
opened it, and **the wiring is three things and nothing else**: this schema, a Java record carrying
`@Typename(name = "operation")` and implementing `Data`, and a `DataNameBinder` that can find it. No reader
family, no factory registration — the ordinary record reader binds the payload. The Java side is
`tson-http/src/test/java/io/ltr8/tson/http/apimeta/`, five small files, and `SketchTest` drives the real
`orders-api-1.tn`.

**`~` does not mean "this is a type."** It is the permission for a *schema* to write `!operation { … }`
(§3.3.1/§5.6). So it is still required on something that is expressly not a type, which is why `~data &`
reads oddly and is right. Drop it and the schema applying it is told the name does not resolve to a
constructor.

**What it buys, all of it checked by the compiler:**

| | |
|---|---|
| `methd:` instead of `method:` | *unknown field* — the description's own shape is checked, which `orders-api-3.tn` cannot do |
| `body: sku_not_fnud` | *unresolved reference* — `Data.references()` reaches the linker, which `orders-api-4.tn` cannot do |
| `holder => { op: create_order }` | *describes something other than a data value* — an operation cannot be used where a type belongs |

The middle row is the one to notice. A body declares which of its slots are references; the linker follows
them. That is the forty lines of `TsonApi.validate` — the ones that reimplemented `UPSTREAM.md` #11's bug —
deleted and answered by the compiler instead.

**Nothing now blocks it.** `UPSTREAM.md` #17 added `TsonConfig.metaNameBinder`, so this is reached through
an ordinary `Tson.builder()` — a separate seam from `dataBindContext`, because that binds the *data* a schema
describes and this binds a governing meta's own *vocabulary*. `orders-api-4.tn` still ships, but the reason
is now a design choice rather than a missing feature; see below.

~~A schema could reference types from only one other schema~~ — `UPSTREAM.md` #11, **fixed and merged**. This
schema now gets past it, and `SketchTest` drives the real file rather than a trimmed stand-in.

## Do templates work in the schema model?

**Yes, and the finding is sharper than expected.** `SketchTest` pins it.

`orders-api-1.tn` now declares `page => <T> { items: [T]  next: uri?  total: int32 }` and an operation
returns a page of orders. Resolved output shows the whole mechanism working: `order_page` is a REFERENCE to
an entry the application materialised, and *that* entry carries `page<order>` in its `source` — which is what
§8.2 makes structural identity out of, so two endpoints returning a page of orders share one entry. This is
the envelope OpenAPI hand-rolls per endpoint or bolts on with `allOf`, written once.

**The one wrinkle: an operation cannot apply a template with the `<...>` sugar.** `body: page<order>` inside
an `!operation { … }` payload is a **parse** error — *"adjacent values must be separated by whitespace, a
comma, or both"* — not a resolution error, and that distinction is the whole explanation. `page<order>` is
*schema* syntax; an `!operation` payload is *data*, where a `type_ref` slot takes §5.6's positional form. The
kernel says so in as many words: *"a bare name token fills `name` directly, and a braced record is the
explicit form, canonical only when `arguments` is present."*

So there are two spellings, both checked, and the sketch shows the first:

| | Spelling | Diagnostic for a bad argument |
|---|---|---|
| **Named application** | `order_page => page<order>` then `body: order_page` | names the synthetic entry the template materialised — `'array_no_such_…' element_type has an unresolved reference 'no_such'` |
| **Inline, explicit form** | `body: { name: page  arguments: [ { name: order } ] }` | names the operation — `'list_orders' (!operation) has an unresolved reference 'no_such'` |

Neither is wrong. The named form reads better, gives the application an identity, and is what
`orders-api-1.tn` uses; the inline form gives the better message. Worth knowing that the ergonomic loss
against a schema-native design is exactly one line per distinct application, and that it is by design rather
than a gap — see the spec-feedback note in `UPSTREAM.md` on whether the sugar should reach data position.

## Does meta-http capture OpenAPI's capabilities?

Its **core**, yes — and the part OpenAPI mostly *is*, it replaces rather than reproduces. Around the edges,
no, and the omissions divide into three kinds that should not be confused.

**Covered, or done better:**

| OpenAPI | here |
|---|---|
| `paths` → `pathItem` → method | `path` and `method` as fields on one flat `operation`. Flat suits templating; nesting bought OpenAPI nothing |
| `operationId` | **the entry name** — and unlike OpenAPI's free-form string it lives in a namespace with a collision rule, so two operations cannot quietly share one |
| `components.schemas` (embedded JSON Schema) | **referenced by `!!id`, not embedded.** The whole thesis: TSON already has published, identity-addressed, immutable schemas |
| `allOf`-style envelopes, repeated per endpoint | `page<T>`, applied — with structural identity and schema-wide dedup |
| `requestBody.content.schema` | `request: type_ref?` |
| `responses` | `[response]` of `status` + `body` |
| `parameters` (name, in, required, schema) | `parameter`, minus `cookie` |
| version and identity of the description itself | `!!id` plus §10 immutability — OpenAPI has `info.version` and no notion of a document identity |

**Absent, but only because nobody added the field.** Probes confirm the meta layer accepts the shapes:
optional scalars (`summary: text?`, `deprecated: boolean?`), map-typed slots (`content: {text => type_ref}`),
and a `~data &` constructor nested inside another all declare cleanly. So `summary`, `description`,
`deprecated`, `tags`, `externalDocs`, `servers`, response `headers`, and a request body's `required` are
additions, not obstacles. Two are live regressions against the data design, which *does* carry them:
`api-2.tn` has `summary` on an operation and `description` on a response, and `meta-http-1.tn` has neither.

**Genuinely harder, and worth being honest about:**

- **Media-type negotiation.** OpenAPI's `content: {mediaType: {schema}}` is its answer to one operation
  serving JSON and XML. Everything here assumes one media type, which is TSON's premise, not an oversight —
  but a description that cannot say "this endpoint also serves `image/png`" is not a general API description.
  The slot declares fine; what to put in it when the payload is not TSON is the open question.
- **Security schemes.** A whole sub-vocabulary — `apiKey`/`http`/`oauth2`/`openIdConnect` with flows and
  scopes. Nothing blocks it; it is simply large, and it is the omission most likely to disqualify this for a
  real service.
- **Parameter serialisation** (`style`, `explode`, `allowReserved`) and **path/parameter agreement**. Nothing
  checks that `/{schemaPath}` has a matching declared parameter — `api-2.tn` has the same gap, and it is not
  a check the type system can express, since it compares a `text` field's *contents* against a list.
- **`examples`** are an opportunity rather than a gap. An OpenAPI example is an untyped blob; here it would
  be a real value of the referenced type, checkable against it. Nothing has tried this.

**The honest summary:** meta-http covers what an API description is *for* — which endpoint, which method,
which payload types — and replaces OpenAPI's largest component with a reference. What it does not yet cover
is most of what makes OpenAPI a specification rather than a sketch, and `security` is the gap that matters.


## Which to adopt

Both blockers are gone (`UPSTREAM.md` #15 and #17), so this is now a design choice rather than a wait.
`orders-api-4.tn` ships today; **`orders-api-1.tn` is the better design**, on one condition.

**The condition: `meta-http.tn` is specified and ships with the library**, the way `core.tn` does. Everything
below assumes that. Without it the in-schema design is a private extension, and a private extension to the
type system is a poor thing to publish an API description in.

**The argument that does *not* decide it**, though it looks like it should: that a consumer needs a Java
`Operation` class to read an in-schema description, while a data description reads in tree mode with no
classes at all. True — measured, and upstream's `UnregisteredMetaConstructorTest` fixes it as correct
behaviour rather than a gap. But TSON has one implementation today, and if `meta-http.tn` is standard then
TypeScript and Python ship their own `Operation` the way they will ship `RecordBody`. Writing that class is
minutes. It is a one-time cost per implementation, and so is `TsonApi.validate` — the two are symmetric and
neither settles anything.

**What actually differs, once both are standardised:**

1. **The check is unskippable in one and opt-in in the other.** `TsonApi.read()` does not call `validate()`,
   and every call site of `validate` in this repo is a test. A consumer who reads a description and forgets
   the second call gets one whose references were never checked, and nothing says so — the same shape as the
   `TsonHandler.install` trap in the Helidon adapter. In-schema there is no unchecked state to be in: the
   references resolve during resolution or the document does not load.

2. **The namespace rule is implemented once, or re-derived per implementation.** This is the one that
   matters for a multi-language future, and it inverts the portability argument rather than being symmetric
   with it. A data description carries its own import list and *prose* says how names resolve against it, so
   every implementation re-derives transitive imports plus collision-by-declaring-identity. The evidence
   that this is hard is unusually direct: upstream got it wrong (#11); it was reimplemented here
   independently, the same occurrence-counting bug, minutes after that fix was summarised; and the obvious
   repair — comparing `TypeDefinition`s — was *also* wrong, because linking credits each route's own
   `subtypes`. Three wrong answers to one rule. In-schema that rule is the resolver's own, which every
   implementation must get right anyway for `!!import`. Duplicated semantics drift silently between
   implementations; a description that validates in Java and fails in Python is what a spec exists to
   prevent.

**What the data design still wins**, honestly:

- **Manipulability.** It is data, so it can be generated, diffed, templated, POSTed to a registry.
- **Tooling weight.** Reading it needs a document reader and one schema; reading the schema form needs the
  full resolver. Every implementation ships a resolver anyway, so this is load-time work rather than a new
  dependency, but it is not nothing for a gateway or a docs site.

The first is recoverable and the reverse is not: the schema form carries strictly more information, so
emitting a data description *from* it is mechanical, while going data → schema needs exactly the checking
that was skipped. Two gaps in that emit path today, both fixable and neither structural:
`orders-api-1.tn` drops `title`/`version` (arguing `!!id` and `@doc` cover them), and `meta-http-1.tn`'s
`response` has no `description` field where `api-2.tn`'s does.

**The reframe that makes this make sense.** It is not schema-versus-data. A `data`-kinded entry *is* data —
that is what the kind is for. The only question is whether the data sits where the resolver can see its
references. `orders-api-4.tn` is the same data placed where the resolver cannot help it.

**If it is standardised, three things want spec answers:**

- **Where `meta-http.tn` sits.** `core.tn` is universal; `GET`/`POST` is emphatically not. Standard but
  optional looks right — an implementation conforms without it, and if it implements it, does so identically.
- **Operations share `entries()` with types**, so a description cannot have an operation named `order` if it
  imports `order`, and enumerating them means filtering by `instanceof Data`. Worth deciding rather than
  inheriting by accident.
- **A parameter's type** still names a scalar with no way to require scalar-ness — see below.


## Open questions

- **A parameter's type must be scalar** — a URL segment cannot carry a record — and nothing enforces it in
  either design.
- **Templated operations** are why `responses` is a list rather than a map keyed by status: the
  structure-templates CR's `template_argument` has no collection case, so a parameter inside any
  collection-typed slot has no resolved form. `crud => <T> !operation { … }` needs that case, which the CR
  defers. API patterns are a better motivating example for it than `choice` was.
- **Envelope templates already work**: `page => <T> { items: [T]  next: uri?  total: integer }`, then
  `@status:200 listed: page<order>`. This is what OpenAPI hand-rolls with `allOf`.
