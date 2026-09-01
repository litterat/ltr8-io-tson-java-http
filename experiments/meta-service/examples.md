# The examples

Real documents under [`examples/`](examples/), resolved by `ExamplesProbe` against the sketch on every build, so
they cannot drift from it. Two entries a governed schema writes, both maps: an `!interface` of methods keyed by
name, and an `!api` of resources keyed by path, each a map of endpoints keyed by verb -- an `!operation` carrying
its signature inline, or a `!binding` naming a method's.

| file | which use | what it shows |
|---|---|---|
| [`orders-types-1.tn`](examples/orders-types-1.tn) | shared | the request and response records; one request record per method, one-field records for parameter-only endpoints |
| [`orders-errors-1.tn`](examples/orders-errors-1.tn) | shared | business errors composing `problem` and pinning `status`, which is how an endpoint's `errors` get one |
| [`orders-1.tn`](examples/orders-1.tn) | **interface only** | `orders` as a map of methods with per-method `@doc`; `orders_v2` extending it |
| [`orders-api-inline-1.tn`](examples/orders-api-inline-1.tn) | **web service only** | an api with no interface behind it, every endpoint an `!operation` with its signature inline; a parameter-only endpoint as a one-field record placed from the path |
| [`orders-api-1.tn`](examples/orders-api-1.tn) | **both** | an api that `implements` `orders`, every endpoint a `!binding` with its placement |
| [`orders-wire-1.tn`](examples/orders-wire-1.tn) | wire | `rpc-1.tn`'s `call`/`return` templates closed once per method of `orders` -- what a generator emits from the interface |

## The placement, for `orders-api-1.tn`

| binding | method's request | path | query | header | body |
|---|---|---|---|---|---|
| `POST /orders` | `new_order { order  idempotency_key }` | -- | -- | `idempotency_key` as `Idempotency-Key` | `order`, unwrapped |
| `GET /orders/{id}` | `order_ref { id }` | `id` | -- | -- | -- |
| `GET /orders` | `order_query { status  page  page_size }` | -- | all three (a GET's remainder) | -- | -- |
| `DELETE /orders/{id}` | `order_ref { id }` | `id` | -- | -- | -- (nothing left over) |

The rules, in precedence order: a `{segment}` in the path binds the field of that name; `query` names query
fields; `headers` maps a field to its header; `body` names one field carried as the whole body. The remainder is
the body -- unless the verb is GET or HEAD, or `body` already named a field, in which case it is the query.
Every field lands exactly once, and a field in the path, query or a header must be a scalar.

`implements: [orders]` is a claim the reader holds the api to: every method of `orders` (and of anything it
extends) has a binding naming it, or an entry in `not_bound: { get_order => "served by the read replica" }`
giving the reason. The resolver checks none of the names -- a method name, an `implements`, a `{segment}` are
identifiers at these positions -- which is the cost of relating data entries by name; `Routes` does it at
startup, and `ExamplesProbe` runs both apis through it.
