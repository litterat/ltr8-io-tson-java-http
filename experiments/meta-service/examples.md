# Three uses of `meta-service-1.tn`

The two entries a governed schema writes are both maps. An `!interface` is a map of methods keyed by name; an
`!api` is a map of resources keyed by path, each a map of operations keyed by verb. Neither a method nor an
operation is a global entry, which is what lets two interfaces both declare `place_order` and lets an api need no
invented operation names.

(The `#` lines below are commentary for this file; TSON has no comment syntax -- a real document says these
things in `@doc`.)

# 1. Interface only -- nothing HTTP in the document

!!id:"https://schemas.example.com/2026/34/app/orders-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/order-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-errors-1.tn"
{
  order_ref   => { id: text }
  order_query => { status: text?  page: int32 ~ 1  page_size: int32 ~ 20 }
  order_page  => { items: [order]  next_page: int32? }
  new_order   => { order: order  idempotency_key: text? }

  @doc:"The orders interface. Every method takes one request record."
  orders => !interface {
    @doc:"Accept an order and confirm it with the quantity doubled."
    place_order  => { request: new_order    response: order       errors: [sku_not_found] }
    get_order    => { request: order_ref    response: order       errors: [order_not_found]  safe: true }
    list_orders  => { request: order_query  response: order_page  safe: true }
    cancel_order => { request: order_ref    errors: [order_not_found]  idempotent: true }
  }

  @doc:"Everything orders has, and a refund."
  orders_v2 => !interface { extends: [orders]  methods: { refund => { request: order_ref  idempotent: true } } }
}

# A method's name is the key it sits under, scoped to its interface; a `@doc` before the key documents it. The
# `!method` tag is optional under the typed slot. `extends` is walked by the reader, not by `!!import`.


# 2. Web service only -- an api on its own terms, every operation's signature inline

!!id:"https://schemas.example.com/2026/34/app/orders-api-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/order-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-errors-1.tn"
!!import:"https://tson.io/2026/34/m/core.tn"
{
  order_ref  => { id: text }
  schema_ref => { schemaPath: text }

  orders_api => !api {
    "/orders" => !resource {
      @doc:"Place an order. The whole body is the order."
      POST => !operation { request: order  response: order  errors: [sku_not_found]  status: 201 }
    }
    "/orders/{id}" => !resource {
      GET => !operation { request: order_ref  response: order  errors: [order_not_found]  safe: true }
    }
    "/{schemaPath}" => !resource {
      @doc:"Every schema this service names, at its own identity's path. The 200 is bytes, not TSON."
      GET => !operation { request: schema_ref  safe: true  idempotent: true }
    }
  }
}

# The verb and the path are the keys an operation sits under; a path key is data, so `/orders/{id}` needs no
# identifier minted from it. A parameter-only endpoint is a one-field request record placed from the path.


# 3. Both -- the interface of (1), and an api that implements it

!!id:"https://schemas.example.com/2026/34/app/orders-api-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-1.tn"
{
  orders_api => !api {
    implements: [orders]
    resources: {
      "/orders" => !resource {
        @doc:"Place an order."
        POST => !operation { method: place_order  status: 201
                             body: order  headers: { idempotency_key => "Idempotency-Key" } }
        GET  => !operation { method: list_orders }
      }
      "/orders/{id}" => !resource {
        GET    => !operation { method: get_order }
        DELETE => !operation { method: cancel_order  status: 204 }
      }
    }
  }
}

# Each operation names a method and borrows its signature; what it adds is the placement of that method's request
# record over the HTTP request -- where the two views are allowed to look different:

| operation | method's request | path | query | header | body |
|---|---|---|---|---|---|
| `POST /orders` | `new_order { order  idempotency_key }` | -- | -- | `idempotency_key` as `Idempotency-Key` | `order`, unwrapped |
| `GET /orders/{id}` | `order_ref { id }` | `id` | -- | -- | -- |
| `GET /orders` | `order_query { status  page  page_size }` | -- | all three (a GET's remainder) | -- | -- |
| `DELETE /orders/{id}` | `order_ref { id }` | `id` | -- | -- | -- (nothing left over) |

# The rules, in precedence order: a `{segment}` in the path binds the field of that name; `query` names query
# fields; `headers` maps a field to its header; `body` names one field carried as the whole body. The remainder
# is the body -- unless the verb is GET or HEAD, or `body` already named a field, in which case it is the query.
# Every field lands exactly once and a field in the path, query or a header must be a scalar.
#
# `implements: [orders]` is a claim the reader holds the api to: every method of `orders` (and of anything it
# extends) has an operation naming it, or an entry in `not_bound: { get_order => "served by the read replica" }`
# giving the reason. The resolver checks none of the names -- a method name, an `implements`, a `{segment}` are
# identifiers at these positions -- which is the cost of relating data entries by name; `Routes` in the
# experiment's Java does it at startup.
