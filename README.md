# tson-java-http

Integrating [TSON](https://tson.io) into a Java HTTP server — reading `application/tson` request bodies,
writing `application/tson` responses, mapping schema violations onto HTTP status codes, and resolving
`!!import` URLs over HTTP.

The modules:

| Module | What it is |
|---|---|
| `tson-http` | Server-agnostic core: media type and `Accept` negotiation, codec, status policy, TSON error body (`problem.tn`), schema catalog. Depends on `tson-service`. No external dependencies. |
| `tson-service` | A service's description of itself (`service.tn`), the reader that checks it against what resolves, and the bind context that maps schema types to classes. Independent of HTTP. No external dependencies. |
| `tson-http-jdk` | Adapter for the JDK's own `com.sun.net.httpserver`, plus schema serving. No external dependencies. |
| `tson-http-javalin` | Adapter for [Javalin](https://javalin.io) 6. |
| `tson-http-helidon` | Adapter for [Helidon](https://helidon.io) 4 SE, plus `TsonMediaSupport` so plain handlers read and write TSON natively. |

**Status.** The modules are built and tested, concurrency included — every adapter driven over real HTTP
rather than by calling handlers, because the point of three adapters is that each framework's own body and
content-negotiation handling differs. Each proves the same loop: a schema served at its own identity path,
fetched back over HTTP under policy, and used to validate a posted document. Serves multiple schema versions
side by side, routed by the `TSON-Schema` header ([TSON-JSON] §3.5) or the body's own `!!import`, and speaks JSON
where an endpoint opts in: a JSON body validated against a TSON schema, and a JSON reply for a client that prefers
one. A service also publishes a description of itself, as a schema whose payload types and exchange references the
compiler resolves — and the examples below are executed by a test, so they are true or the build fails.

## Try it

```
./gradlew :tson-http-jdk:runDemo        # or :tson-http-javalin: / :tson-http-helidon:
```

Each starts the same server and prints what to try. The same commands work against all three:

```
$ curl -s localhost:8080/orders -H 'Content-Type: application/tson' --data-binary '
  !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
  !order { sku: "ABC-1"  quantity: 3 }'
!!import:"https://schemas.example.com/2026/40/app/order-1.tn"
!order { sku: "ABC-1" quantity: 6 }

$ curl -s localhost:8080/orders -H 'Content-Type: application/tson' --data-binary '
  !!import:"https://schemas.example.com/2026/40/app/order-1.tn"
  !order { }'
!!import:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
!problem.details { status: 400 title: "Invalid TSON document" detail: "the request body has 2 problems" errors: [
  { path: "/sku" schema_pointer: "/order/sku" code: "FIELD_REQUIRED"
    message: "missing required field \'sku\' for \'order\'" data_position: "3:8:70" ... }
  { path: "/quantity" schema_pointer: "/order/quantity" code: "FIELD_REQUIRED" ... } ] }
```

Both problems, in one response — a client fixing one error per round trip needs one round trip per error.

The service description lists JSON's media types for `POST /orders` ([TSON-JSON]), so the same order can
arrive as JSON and be answered in it, the schema named in the `TSON-Schema` header since a JSON body has nowhere
else to name it:

```
$ curl -s localhost:8080/orders -H 'Content-Type: application/json' -H 'Accept: application/json' \
    -d '{"sku": "ABC-1", "quantity": 3}'
{"sku":"ABC-1","quantity":6}
```

### Problem types

`type` is the member to match on: it is stable where `title` is prose. Every failure this project produces
carries one of these, under `https://ltr8.io/2026/40/http/problems/` — the implementation's own host, kept apart
from the specification's `tson.io`, where schema identities live.

| `type` | Status | Raised when |
|---|---|---|
| `invalid-document` | 400 | the body was read against its schema and broke it |
| `confusable-names`, `restricted-character`, `restricted-script` | 400 | refused under this deployment's [TSON-DATA] §8.2 name policy, one type per rule; what the deployment admits is published at `/.well-known/tson-deployment` |
| `malformed-document` | 400 | the body does not lex, does not parse, or is not data |
| `invalid-schema` | 400 | the schema the body names is itself wrong |
| `unusable-schema-reference` | 400 | the body names a schema this server will not load, or nothing serves |
| `no-schema-declared` | 400 | the endpoint requires a version and the body named none |
| `unsupported-schema-version` | 400 | the body's schemas include no version this endpoint serves |
| `malformed-schema-header` | 400 | `TSON-Schema` or `TSON-Accept-Schema` is not a valid List of sf-strings |
| `conflicting-schema` | 400 | header and body name different sets of schemas, or a message names two versions served |
| `no-such-schema` | 404 | a schema was requested at a path this server does not publish |
| `method-not-allowed` | 405 | the route does not take this method (adapters) |
| `not-acceptable` | 406 | `Accept` rules out the only representation the route produces |
| `unsupported-media-type` | 415 | the body is not something the route can read |
| `limit-exceeded` | 413 | the body went past a [TSON-DATA] §9.1 bound this deployment reads within, so it was not read to the end; the bounds are published at `/.well-known/tson-deployment` |
| `internal-error` | 500 | this server's own wiring — a bind mismatch, or an unclassified fault |
| `not-implemented` | 501 | this server's TSON library has not built a construct the body uses |
| `schema-origin-failed` | 502 | the schema could not be obtained, so the body went unchecked |
| `schema-origin-timeout` | 504 | the origin holding the schema did not answer in time |

A 5xx body carries `status` and `title` and no `detail`, and no `errors` either — an internal message can name
a class or an internal host, and a client is not the audience. A 4xx is the opposite: its detail and
diagnostics are the point.

Both replies name the schema that governs them, and the server publishes both documents, so a client can
validate what it received with nothing told out of band:

```
$ curl -s localhost:8080/2026/40/io/ltr8/http/problem.tn | head -1
!!id:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
```

```java
// JDK
server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
    Order order = exchange.readObject(Order.class);   // validated, or 400 with every diagnostic
    exchange.respond(201, store(order));
}));

// Javalin
app.post("/orders", TsonHandler.asHandler(codec, tson -> tson.respond(201, store(tson.readObject(Order.class)))));

// Helidon, with TsonMediaSupport registered -- no TSON-specific code in the handler
routing.post("/orders", (req, res) -> res.status(201).send(store(req.content().as(Order.class))));
```

## The validator demo

A second demo, and the opposite one. The order server above is the shape a real service takes — every schema
resolved at startup and shared for reads. This one takes **the schema from the request**, which that shape
forbids, and the interest is in what it costs to do safely.

```
./gradlew :tson-http-jdk:runValidator
```

Then open `localhost:8080` for a page with a document on one side and a schema on the other, and every problem
in one pass on the right. The same pair on [tson.io](https://tson.io/) is checked by the TypeScript
implementation in the browser; this checks it with the Java one over HTTP, on the same scenarios — so a
difference in code, message or source position between the two is a finding.

The request is itself a TSON document, governed by a schema the service publishes:

```
!!import:"https://schemas.example.com/2026/40/app/validate-1.tn"
!validation_request {
  schema: "!!id:\"...the schema under test...\" ..."
  data:   "!!import:\"...that same identity...\" ..."
}
```

Three things about it are decisions rather than details:

- **A document that does not conform is a `200`.** The request was well formed and the service answered it;
  the diagnostics *are* the answer. A `400` here is always about the envelope and never about the document
  under test — otherwise a client cannot tell "you asked badly" from "the thing you asked about is bad".
- **`phase` says which of the two runs reported.** `SCHEMA` means the schema was at fault and the data was
  never looked at; `DATA` means the schema was sound. Without it, "no diagnostics" and "nothing was checked"
  look alike.
- **A fresh `Tson` per request**, because `validateSchema` registers a sound schema: two callers submitting
  different schemas under one `!!id` would otherwise be answered about each other's. It costs the
  standard-library bootstrap, which is why `elapsed_ms` times the validation and not the request.

It fetches nothing. The submitted schema is served at its own `!!id` and every other identity is refused — a
reference in an untrusted document is an untrusted URL.

It also runs under a **deployment descriptor** — the third artifact kind, beside a schema (what a document
must be) and an API description (what an endpoint offers). [TSON-DATA] §8.2's name-hygiene policies are a
security control with no artifact of their own, and a schema cannot hold one: an artifact that declared its own
strictness would choose its own check, and §3.5 makes a published schema immutable while a policy has to move. So the
descriptor is *data*, it is handed to the process rather than found, and no document may name one, which is the
spec's own rule; `policy.tn` is the policy's bundled vocabulary. The demo's descriptor
sets a token policy, which you can see refuse a document the default would accept:

```
$ curl -s localhost:8080/.well-known/tson-deployment
!!import:"https://tson.io/2026/40/io/ltr8/http/deployment.tn"
!deployment.acceptance_profile { name: "validator-demo" policy: {
  identifier_policy: { level: "HIGHLY_RESTRICTIVE" per_segment: false skeleton_distinctness: true permitting: [] }
  token_policy: { level: "SINGLE_SCRIPT" permitting: [] }
  unicode_data_version: "16.0" limits: { max_depth: 64 max_token_length: 1048576 max_text_length: 1048576
    max_numeric_literal_length: 4096 max_binary_size: 16777216 max_document_size: 16777216 max_elements: 1048576
    max_entries: 1048576 max_fields: 65536 max_annotations: 64 max_values: 16777216 max_import_closure: 64
    max_schema_entries: 65536 max_alias_chain: 64 max_supertype_chain: 64 max_wait_depth: 64 } } }
```

That is the policy **in force**, in `policy.tn`'s `policy.processor` shape — the shape the descriptor states its
policy in, whole — and not the descriptor: which origins a deployment will fetch schemas from is nobody else's
business. It is served at a well-known path because everything with an identity in this series is served at its
identity's path, and a descriptor is precisely the artifact that must not have one. And it is a hint — only the refusal a
request actually receives says what applied to that request.

## Building

Requires JDK 25 and a checkout of [ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java) at
`../ltr8-io-tson-java`. That library publishes to mavenLocal only, so a checkout is needed either way.

```
./gradlew build                                            # consumes the sibling as an included build
./gradlew build -Ptson.path=/elsewhere/ltr8-io-tson-java   # if it lives somewhere else

# or against its published artifacts, after `./gradlew publishToMavenLocal` in that checkout
./gradlew build -Ptson.published=true
```

## Licence

Apache 2.0, as tson-java.
