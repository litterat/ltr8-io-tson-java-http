# Bounded references: `ref<T>` and qualified names

**Status: landed, in a different shape.** The Revision 38 proposal has `ref<T>` naming a *value* (`ref_type`, tson-java
PR #749), `kind<C>` for a type's name, `namespace` as a constructor (PR #753), a walk through namespace keys and record
values' fields (PR #758), and a reference declared only in a kernel-governed schema (PR #760). `meta-service.tn` runs on
it: a binding's `method` is `ref<method>`, spelled `orders.orders.methods.place_order`. What follows is the proposal as
written, kept for the argument; its kernel shapes are not the ones that landed.

## The question

`request: place_order_request` is the right spelling for a method's request: a name, which the resolver checks
against a record it can see. An HTTP binding wants the same for the method it binds -- `method: orders.place_order`
-- and today cannot have it, because a method is a value inside an interface and nothing outside can name one. The
sketch's `binding` carries `method: method_name` and `interface?: type_name` instead, two identifiers the resolver
never checks, and `Routes` checks them at startup.

Does the request's spelling extend to the binding's, once a slot can say what it points at and a name can reach
into an interface?

## Three kernel changes

**1. A reference constructor, and a template beside `set`.**

```
-- meta-kernel.tn: a value is a type_ref; `of` bounds what it may name
ref_type => product & { of?: [type_ref; 1..] }

-- meta.tn and core.tn, beside `set => <T> !set_type { element_type: T }`
ref => <T> !ref_type { of: [T] }
```

`ref<record>` is then an ordinary §5.10 application, admitted at a field as `set<text>` is. The two templates share
a spelling because they share a mechanism: each captures its argument into a `type_ref` slot of a constructor. What
differs is the constructor -- `set_type.element_type` says the values *contain* a `T`, `ref_type.of` says they
*name* one. A bound needing more than one type is a named entry, as a bounded set is (§5.3):
`error_ref => !ref_type { of: [record choice] }`.

The kernel's own `type_ref` keeps its shape and is the unbounded case: it names any entry IS-A `type` (upstream's
`SPEC-FEEDBACK.md` #1, running on the proposal), so §4.1's refusal of a `data` entry at a type slot is unchanged.

**2. A qualified name.** `type_ref.name` is a `type_name`, which admits no `.`. It widens to `type_name` segments
joined by `.`, the separator [TSON-DATA] §7.1 reserves, and the identifier policy's per-segment unit becomes its
hygiene unit.

**3. A `namespace` kind** -- an entry whose body is members under keys, each member a value of one type:

```
namespace => top & { key_type: type_ref  member: type_ref }
```

It composes `top` and not `type`, so naming one where a type is expected is refused by the same test that refuses
`data`. Here it is used once, for `interface`.

## `meta-service.tn`

```
!!id:"https://tson.io/2026/38/io/ltr8/http/meta-service.tn"
!!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
!!import:"https://tson.io/2026/38/m/meta.tn"
{
  @doc:"A method's name: an identifier in its interface's namespace, not a type name."
  method_name => identifier

  @doc:"""
    The transport-neutral contract of one callable thing: a plain record, whose values sit under their names in
    an interface. `request` and each of `errors` name a record -- a request because an endpoint distributes its
    fields over the HTTP request, an error because it composes `problem`. `response` names any type.
    """
  method => {
    request?:  ref<record>
    response?: type_ref
    errors?:   [ref<record>]
  }

  @doc:"A named set of methods, each a member under its name: `orders.place_order` reaches one."
  interface => namespace & { key_type: = method_name  member: = method }

  http_verb     => !enum [GET POST PUT PATCH DELETE HEAD OPTIONS]
  status_code   => !integer ^ { min: 100  max: 599 }
  path_template => !text ^ { pattern: "/|(/([^/{}]+|\\{[A-Za-z_][A-Za-z0-9_-]*\\}))+" }
  header_name   => !text_type { pattern: "[!#$%&'*+.^_`|~0-9A-Za-z-]+"  normalization: NFKC_CASEFOLD }

  @doc:"Where a request record's fields go: the path's `{segment}`s, then these, and the remainder to the body."
  placement => {
    query?:   [field_name]
    headers?: {field_name => header_name}
    body?:    field_name
  }

  @doc:"What sits under a verb, under a path: tagged `!operation` or `!binding`."
  endpoint => placement & {
    status?: status_code ~ 200
  }

  summary    => @annotation text
  safe       => @annotation void
  idempotent => @annotation void

  @doc:"An endpoint carrying its method inline, for an api with no interface behind it."
  operation => endpoint & method

  @doc:"An endpoint bound to a method an interface declares."
  binding => endpoint & {
    method: ref<method>
  }

  resource => {
    endpoints: {http_verb => endpoint}
  }

  @doc:"Why a method of an implemented interface has no endpoint here."
  exemption => {
    method: ref<method>
    reason: text
  }

  @doc:"""
    A web service: resources keyed by path, endpoints keyed by verb. `implements` is a claim the reader holds:
    every method of every implemented interface has a binding, or an exemption giving the reason it has none.
    """
  api => data & {
    implements?: [ref<interface>]
    not_bound?:  [exemption]
    resources:   {path_template => resource}
  }
}
```

Against the sketch that resolves today: `signature` is gone, since `method` is the signature; `interface` is a
namespace rather than `data & { extends?  methods: {…} }`; `binding.interface` and `exemption.interface` are gone,
since the qualified name carries the interface; `not_bound` is a list, since a map key is data (§2.6) and cannot be
a reference; and `operation` composes `method`. `api` stays `data`, because nothing names an endpoint.

## `orders-1.tn`

```
!!id:"https://schemas.example.com/2026/38/experiment/meta-service/orders-1.tn"
!!meta:"https://tson.io/2026/38/io/ltr8/http/meta-service.tn"
!!import:"https://tson.io/2026/38/m/core.tn"
!!import:"https://tson.io/2026/38/io/ltr8/http/problem.tn"
{
  order      => { sku: text  quantity: int32 }
  order_page => { items: [order]  next_page?: int32 }

  place_order_request => { order: order  idempotency_key?: text }
  order_ref           => { id: text }
  list_orders_request => { status?: text  page?: int32 ~ 1  page_size?: int32 ~ 20 }

  sku_not_found   => problem & { status: = 404  sku: text }
  order_not_found => problem & { status: = 404 }

  @doc:"The orders interface."
  orders => !interface {
    @summary:"Place an order."
    place_order  => { request: place_order_request  response: order  errors: [sku_not_found] }
    @safe
    get_order    => { request: order_ref            response: order  errors: [order_not_found] }
    @safe
    list_orders  => { request: list_orders_request  response: order_page }
    @idempotent
    cancel_order => { request: order_ref            errors: [order_not_found] }
  }
}
```

Every name a method uses is a root entry of this document or of what it imports, and every one is checked at load.

## `orders-api-1.tn`

```
!!id:"https://schemas.example.com/2026/38/experiment/meta-service/orders-api-1.tn"
!!meta:"https://tson.io/2026/38/io/ltr8/http/meta-service.tn"
!!import:"https://schemas.example.com/2026/38/experiment/meta-service/orders-1.tn"
{
  orders_api => !api {
    implements: [orders]
    resources: {
      "/orders" => !resource {
        POST => !binding { method: orders.place_order  status: 201
                           body: order  headers: { idempotency_key => "Idempotency-Key" } }
        GET  => !binding { method: orders.list_orders }
      }
      "/orders/{id}" => !resource {
        GET    => !binding { method: orders.get_order }
        DELETE => !binding { method: orders.cancel_order  status: 204 }
      }
    }
  }
}
```

An exemption is written `not_bound: [ { method: orders.cancel_order  reason: "served by the admin service" } ]`.

## How a reference resolves

`orders.place_order` at a `ref<method>` slot: `orders` resolves in the type-name namespace, locally or through an
import, exactly as `place_order_request` does; `.place_order` selects that member; and the member's value must be a
`method`. Each step refuses with its own message:

| Written | Result |
|---|---|
| `method: orders.place_order` | accepted |
| `method: place_order` | refused: no root entry `place_order` -- a member is reached through its namespace |
| `method: orders.place_ordr` | refused: `orders` declares no member `place_ordr` |
| `method: place_order_request` | refused: a `record`, where a `method` is wanted |
| `request: orders.place_order` | refused: a `method`, where a `record` is wanted |
| `request: order_v2`, `order_v2 => order & { currency: text }` | accepted: the resolved value is a record |
| `request: req`, `req => order_ref` | accepted: the alias hop is followed |
| `implements: [order]` | refused: a `record`, where an `interface` is wanted |
| `response: orders` | refused: an interface is not IS-A `type` |

## The rules it rests on

**One relation, seen from two levels.** From `orders-1.tn`, `place_order_request` is a declaration describing data
and `place_order` a declaration describing calls. From `meta-service.tn` both are values -- one of `record`, one of
`method`. So "built by `record`" and "a value of `method`" are the same relation, and `ref<T>` means *a named value of
`T`*: C's `T*`. That is why `method` needs no kind of its own -- it is a record whose values sit in a namespace -- and
why one template serves the request slot and the binding slot. The relation is judged on the resolved value, so a
composition or an application resolving to a record is one, and §7.2's subsumption applies as at any field: a value
of a subtype of `T` is a value of `T`.

**A bare name is a root entry.** `request: place_order_request` written inside `orders` does not see the interface's
own members; nothing is looked up lexically. So a name means the same everywhere in a document, and resolution stays
two passes: the first registers root names and each namespace's keys (the head `!interface` resolves in the meta,
so the first pass knows which entries are namespaces), the second resolves every reference against them.

**A reference is the member it lands on.** Its identity is the declaring schema's identity, the entry and the key --
`…/orders-1.tn`, `orders`, `place_order` -- compared as a schema reference compares by canonical identity. Without
composition each member has one path, so spelling and identity coincide; the rule is stated now so that coverage
counts a method once the day namespaces compose. `TsonLinkedSchema.originOf` already answers the first half for a
root entry.

**A namespace where a reference reaches in, and nowhere else.** `interface` is one because a binding names its
members. `api` is not, because nothing names an endpoint, so its data keys need no projection.

**The level is the meta's.** `ref<record>` and `ref<method>` are written in a meta layer, where `record` (through
`meta.tn`) and `method` (declared there) are local types. In an ordinary schema `record` is in the structure
namespace, and `ref<record>` there is an unresolved name, as any type position naming a constructor is.

## Against the spec as written

- **§5.10 declines a constructor bound on a parameter**: `<T: text_type> set<T>` would judge a type by how the meta
  built it. Nothing here bounds a parameter -- `record` is a template *argument* -- and `ref<T>` itself carries no
  bound. A template's parameter is still bounded by the slot it fills: were `meta-http.tn`'s `operation` to take
  `request?: ref<record>`, `create => <T> !operation { … request: T … }` would refuse `create<text>` at the
  application, with `<T>` unbounded.
- **§5.10 also says a type slot cannot be bounded at a field** and leaves that as prose, and the Revision 37 change
  log defers "a bound that names a base kind" until a bundled schema proves the shape. `ref<record>` is that bound at
  a field, naming a constructor, so this is the case asking for it to be revisited. The evidence is the two slots
  above, neither of which can be stated otherwise: a request must be a record because an endpoint distributes its
  fields, and a binding must name a method because coverage counts methods. And the shape asks for no dependent
  typing and no new syntax -- one constructor, one template, applied as `set` is.
- **§4.1 is unchanged.** `data` keeps its rule; an unbounded `type_ref` names a type; `ref<method>` names a method.
- **Naming.** The kernel's `reference` is an alias: `!reference { target: T }` *is* `T` (§8.3). `ref<T>` names a
  `T` without being one, so its final name must not read as the kernel's.

## What moves out of the reader, and what stays

**Moves to the resolver:** that a binding's method exists; which interface it is in; that a request is a record
(`Placement`'s refusal of one that is not); that `implements` names interfaces.

**Stays in the reader**, each because it relates two values: a bound method belongs to an interface the api
implements; coverage; a placement's field names against the request record's fields, found by following
`orders.place_order` to its method and its request to the record; an error type pins its status; a verb agrees with
`@safe` and `@idempotent`.

## Left out

- **Composing interfaces** -- `extends`, or `&` merging member sets. It is a capability of its own, and the identity
  rule above is what it would lean on.
- **Projecting a record's fields**, `order.sku`. `.` reaches only namespace members here; a record taking the
  namespace's shape is the namespaces entry's step 2.
- **Referencing an endpoint.** Its keys are data, which `.` cannot spell; it would need the pointer form,
  `/orders/POST`.
- **A reference as a map key.** A key is data (§2.6), which is why `not_bound` became a list.
