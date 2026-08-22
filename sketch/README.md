# Sketch: an API description made of types, not data about types

Two schemas, written to answer a question: **what would an API specification look like if it were
TSON-native rather than OpenAPI transliterated?**

Neither ships. `meta-http-1.tn` resolves as written; `orders-api-1.tn` does not yet, and the two reasons why
are the point of the exercise.

## The problem with the version in `tson-http`

`api-1.tn` (which does ship, and works) describes an operation like this:

```tson
body => { schema: uri  type: non_empty_text }
```

A schema URI and a type name, both carried as **data**. Nothing resolves either. That is
`$ref: "#/components/schemas/Order"` in TSON's clothing, and it is why `TsonApiConformanceTest` has to exist:
it hand-checks that the description is internally coherent, which is work a resolver should do by
construction.

## The sketch

`meta-http-1.tn` adds an `operation` constructor to the type system; `orders-api-1.tn` uses it:

```tson
create_order => !operation {
  method: POST   path: "/orders"
  request: order
  responses: [ !response { status: 201  body: order }
               !response { status: 404  body: sku_not_found } ]
}
```

`order` and `sku_not_found` are **type references**, resolved through this schema's `!!import`s. The
`(schema, type)` pair is gone, because a type reference already carries identity. So is the `api { title
version operations }` envelope: a schema document already has an `!!id` — identity and §10 versioning — plus
`@doc` and a namespace.

What that buys: a description naming a type nothing declares **fails to load**, rather than failing at a
client months later. Resolver output (§8) is then a fully-linked API model for free, which is what codegen
wants.

## Two design points, both measured rather than assumed

**`operation` must be declared in a sibling of `meta.tn`, not a layer above it.** Declaring a type constructor
— the `~` marker — is permitted only in a schema whose own `!!meta` *is* the meta-kernel. A layer above
`meta.tn` is refused: *"'op' declares a type constructor (the '~' marker), but … is not governed directly by
the meta-kernel"*. So `meta-http-1.tn` is governed by the kernel, exactly as `meta.tn` is.

**`operation` is `~top &`, not `~product &`.** No data value ever has an operation as its type. This is the
reasoning meta-kernel already uses for `reference` and `instance_template`, where `product` would oblige an
entry to supply `access_pattern` and `size_type` as meaningless filler.

## What blocks it

**1. `!!import` is deep, and the spec says shallow** (`UPSTREAM.md` #11). An API description references types
from several independently-published schemas by nature. `orders-api-1.tn` imports `order-1.tn` and
`orders-errors-1.tn`, and both reach `core.tn`, so:

```
'void' is declared by more than one !!import
```

§2.2.3 says imports are shallow — neither carries `void` — so this must resolve. Until it does, an API schema
can reference types from exactly one other schema, which is not an API.

**2. A user-defined meta-schema's constructors cannot be applied.** With a single import, the failure changes:

```
UnsupportedOperationException: 'create': failed to bind 'operation' via the compiled meta-schema
reader: 'operation' is not a constructor '…/meta-http-1.tn' declares
```

`UnsupportedOperationException` is this project's classification for *not implemented yet*, not for *your
schema is wrong* — and the meta layer itself resolved, so the declaration is fine. The gap is between
resolving a custom meta-schema and being able to govern a schema with it.

Neither is a defect in the design. Both are implementation gaps with a working design waiting on them.

## Open questions

- **A parameter's `type` must name a scalar** — a URL segment cannot carry a record — and nothing in this
  vocabulary enforces that. A `~atom` bound would say it, if a constructor can constrain a `type_ref` slot by
  base kind.
- **Templated operations** are the reason `responses` is a list rather than a map keyed by status. The
  structure-templates CR's `template_argument` is `param | value | type_ref` with **no collection case**, so a
  parameter inside any collection-typed slot has no resolved form. `crud => <T> !operation { … }` therefore
  needs that case, which the CR defers as "worth reviewing once the basic form is working". API patterns are a
  better motivating example for it than `choice` was.
- **Envelope templates work today**, and are what OpenAPI hand-rolls with `allOf`:
  `page => <T> { items: [T]  next: uri?  total: int32 }`, then `body: page<order>`. Verified resolving.
- **Where does `meta-http-1.tn` get the standard constructors?** It imports only the kernel, which carries
  `record`/`array`/`enum` and the rest. Importing `meta.tn` as well — for `binary`, the float types — is the
  same double-import wall as #1.
