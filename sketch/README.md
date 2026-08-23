# Sketch: an API description made of types, not data about types

## The files

| File | What |
|---|---|
| `orders-api-3.tn` | **Read this first.** The API, in an ordinary schema. Imports the four below. |
| `http-api-1.tn` | Reusable vocabulary: the enums, the `response`/`page` templates, the `operation` base. |
| `order-1.tn` | The domain types. Versions independently of the API that exposes them. |
| `orders-errors-1.tn` | Business errors, composing `problem` from the shipping `problem-2.tn`. |
| `orders-api-2.tn` + `meta-http-2.tn` | The annotation design: operations as `top &`, metadata on annotations. |
| `orders-api-1.tn` + `meta-http-1.tn` | The constructor design. Blocked; see below. |


**Three designs, in increasing order of how little they need.** Nothing here ships. `SketchTest` holds each to
what this file claims, so a fix upstream shows up as a failing test rather than as nothing happening.

| | Header it needs | Carries metadata with | Status |
|---|---|---|---|
| `orders-api-3.tn` | `meta.tn` + `core.tn` — **the ordinary one** | FIXED fields | **works** |
| `orders-api-2.tn` | a custom meta layer for annotation *types* | annotations | works |
| `orders-api-1.tn` | a custom meta layer with an `operation` constructor | constructor fields | blocked on one gap |

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

**5. Templated operations become possible.** `crud => <T> !operation { … }` is D9's `instance-template`
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

## The constructor design, blocked

`meta-http-1.tn` declares `operation` as a real type constructor:

```tson
operation => ~top & {
  method: http_method   path: text
  parameters: [parameter]   request: type_ref?   responses: [response]
}
```

**It resolves.** One wall now stops `orders-api-1.tn` using it:

1. **A user-defined meta-schema's constructors cannot be applied.**
   `UnsupportedOperationException: 'create': failed to bind 'operation' via the compiled meta-schema reader:
   'operation' is not a constructor '…/meta-http-1.tn' declares`. That exception type is this project's
   classification for *not implemented yet*, not for *your schema is wrong*.
~~A schema could reference types from only one other schema~~ — `UPSTREAM.md` #11, **fixed and merged**. This
schema now gets past it, and `SketchTest` drives the real file rather than a trimmed stand-in.

## Open questions

- **A parameter's type must be scalar** — a URL segment cannot carry a record — and nothing enforces it in
  either design.
- **Templated operations** are why `responses` is a list rather than a map keyed by status: the
  structure-templates CR's `template_argument` has no collection case, so a parameter inside any
  collection-typed slot has no resolved form. `crud => <T> !operation { … }` needs that case, which the CR
  defers. API patterns are a better motivating example for it than `choice` was.
- **Envelope templates already work**: `page => <T> { items: [T]  next: uri?  total: integer }`, then
  `@status:200 listed: page<order>`. This is what OpenAPI hand-rolls with `allOf`.
