# 1. Interface only — nothing HTTP in the document (the !!id is the interface's name)

!!id:"https://schemas.example.com/2026/34/app/orders-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/order-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-errors-1.tn"
{
  @doc:"Accept an order and confirm it with the quantity doubled."
  place_order => !method { request: order  response: order  errors: [sku_not_found] }

  @doc:"Look an order up by reference."
  get_order   => !method { request: order_ref  response: order  errors: [order_not_found]  safe: true }
}

# `extends`: a second interface importing this one has both methods in its namespace, and may not
# redeclare either — that is §2.2.3, not a rule of this layer.


# 2. Web service only — today's orders-api-1.tn in the new shape

!!id:"https://schemas.example.com/2026/34/app/orders-api-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/order-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-errors-1.tn"
!!import:"https://tson.io/2026/34/m/core.tn"
{
  @doc:"Accept an order and confirm it with the quantity doubled."
  create_order => !operation {
    verb: POST  path: "/orders"  status: 201
    request: order  response: order  errors: [sku_not_found]
  }

  @doc:"Every schema this service names is served at its own identity's path. The 200 is bytes, not TSON."
  get_schema => !operation {
    verb: GET  path: "/{schemaPath}"  safe: true  idempotent: true
    parameters: [ !parameter { name: "schemaPath"  in: PATH  type: text  required: true
                               description: "The path component of the schema's own !!id" } ]
    errors: [schema_not_found]
  }
}

# and in orders-errors-1.tn, each error pins the status it inherits from problem:
#   sku_not_found    => problem & { status: = 404  sku: text }
#   schema_not_found => problem & { status: = 404 }


# 3. Both — one document, one declaration per method, some bound to HTTP and some not yet

!!id:"https://schemas.example.com/2026/34/app/orders-1.tn"
!!meta:"https://tson.io/2026/34/ltr8/http/meta-service-1.tn"
!!import:"https://schemas.example.com/2026/34/app/order-1.tn"
!!import:"https://schemas.example.com/2026/34/app/orders-errors-1.tn"
{
  @interface:orders
  @doc:"Accept an order and confirm it with the quantity doubled."
  place_order => !operation {
    verb: POST  path: "/orders"  status: 201
    request: order  response: order  errors: [sku_not_found]
  }

  @interface:orders
  @doc:"Cancel an order. Reachable through the batch endpoint; no route of its own yet."
  cancel_order => !method { request: order_ref  errors: [order_not_found]  idempotent: true }

  @interface:agent
  @doc:"Run a plan of method calls in one request; each step's outcome is data, so the envelope is a 200."
  invoke => !operation { verb: POST  path: "/invoke"  request: plan  response: plan_result }
}

# The interface view of this document is {place_order, cancel_order, invoke} with the HTTP fields
# erased. The web-service view is {place_order, invoke}. Nothing was written twice.
