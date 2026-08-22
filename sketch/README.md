# Sketch: an API description made of types, not data about types

**Three designs, in increasing order of how little they need.** Nothing here ships. `SketchTest` holds each to
what this file claims, so a fix upstream shows up as a failing test rather than as nothing happening.

| | Header it needs | Carries metadata with | Status |
|---|---|---|---|
| `orders-api-3.tn` | `meta.tn` + `core.tn` — **the ordinary one** | FIXED fields | **works** |
| `orders-api-2.tn` | a custom meta layer for annotation *types* | annotations | works |
| `orders-api-1.tn` | a custom meta layer with an `operation` constructor | constructor fields | blocked on one gap |

**`orders-api-3.tn` is the one to read.** It needs no meta layer, no kernel import, no `top`, and no
annotations — an ordinary schema, which means any TSON toolchain can already read it.

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
2. **An annotation's value is bound through the governing meta-schema's namespace, and is silently dropped
   otherwise.** Declare `method => @annotation http_method` beside the operation and `@method:POST` resolves
   with `value=Optional.empty` — no diagnostic. `@doc` keeps its value only because `doc` is meta-kernel's.
   **This is the whole reason `meta-http-2.tn` exists**: it holds the annotation *types* and nothing else.
   Filed as `UPSTREAM.md` #12.
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
