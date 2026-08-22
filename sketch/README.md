# Sketch: an API description made of types, not data about types

Two designs, four schemas. **One works today** (`meta-http-2.tn` + `orders-api-2.tn`); one is blocked on
implementation gaps (`meta-http-1.tn` + `orders-api-1.tn`). Nothing here ships. `SketchTest` holds each to what
this file claims, so a fix upstream shows up as a failing test rather than as nothing happening.

## The problem with `api-1.tn`, which does ship

```tson
body => { schema: uri  type: non_empty_text }
```

A schema URI and a type name, both carried as **data**. Nothing resolves either. That is
`$ref: "#/components/schemas/Order"` in TSON's clothing — and it is why `TsonApiConformanceTest` exists at all:
it hand-checks coherence that a resolver should establish by construction.

## The design that works

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

## The design that is blocked

`meta-http-1.tn` declares `operation` as a real type constructor:

```tson
operation => ~top & {
  method: http_method   path: text
  parameters: [parameter]   request: type_ref?   responses: [response]
}
```

**It resolves.** Two walls stop `orders-api-1.tn` using it:

1. **A user-defined meta-schema's constructors cannot be applied.**
   `UnsupportedOperationException: 'create': failed to bind 'operation' via the compiled meta-schema reader:
   'operation' is not a constructor '…/meta-http-1.tn' declares`. That exception type is this project's
   classification for *not implemented yet*, not for *your schema is wrong*.
2. **`!!import` is deep where §2.2.3 says shallow** (`UPSTREAM.md` #11). An API description references types
   from several published schemas by nature, and any two that both reach `core.tn` collide on `void`.

## Open questions

- **A parameter's type must be scalar** — a URL segment cannot carry a record — and nothing enforces it in
  either design.
- **Templated operations** are why `responses` is a list rather than a map keyed by status: the
  structure-templates CR's `template_argument` has no collection case, so a parameter inside any
  collection-typed slot has no resolved form. `crud => <T> !operation { … }` needs that case, which the CR
  defers. API patterns are a better motivating example for it than `choice` was.
- **Envelope templates already work**: `page => <T> { items: [T]  next: uri?  total: integer }`, then
  `@status:200 listed: page<order>`. This is what OpenAPI hand-rolls with `allOf`.
