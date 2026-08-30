# tson-java-http

Integrating [TSON](https://tson.io) into a Java HTTP server — reading `application/tson` request bodies,
writing `application/tson` responses, mapping schema violations onto HTTP status codes, and resolving
`!!schema` URLs over HTTP.

Four modules:

| Module | What it is |
|---|---|
| `tson-http` | Server-agnostic core: media type and `Accept` negotiation, codec, status policy, TSON error body (`problem-1.tn`), API description (`meta-http-1.tn`), schema catalog. No external dependencies. |
| `tson-http-jdk` | Adapter for the JDK's own `com.sun.net.httpserver`, plus schema serving. No external dependencies. |
| `tson-http-javalin` | Adapter for [Javalin](https://javalin.io) 6. |
| `tson-http-helidon` | Adapter for [Helidon](https://helidon.io) 4 SE, plus `TsonMediaSupport` so plain handlers read and write TSON natively. |

**Status.** All four modules are built and tested, concurrency included — every adapter driven over real HTTP
rather than by calling handlers, because the point of three adapters is that each framework's own body and
content-negotiation handling differs. Each proves the same loop: a schema served at its own identity path,
fetched back over HTTP under policy, and used to validate a posted document. Serves multiple schema versions
side by side, routed by the `TSON-Schema` header or the body's own `!!schema`, and validates JSON bodies
against TSON schemas. A service also publishes a description of itself, as a schema whose payload types the
compiler resolves — and the examples below are executed by a test, so they are true or the build fails.

## Try it

```
./gradlew :tson-http-jdk:runDemo        # or :tson-http-javalin: / :tson-http-helidon:
```

Each starts the same server and prints what to try. The same commands work against all three:

```
$ curl -s localhost:8080/orders -H 'Content-Type: application/tson' --data-binary '
  !!schema:"https://schemas.example.com/2026/34/app/order-1.tn"
  !order { sku: "ABC-1"  quantity: 3 }'
!!schema:"https://schemas.example.com/2026/34/app/order-1.tn"
!order { sku: "ABC-1" quantity: 6 }

$ curl -s localhost:8080/orders -H 'Content-Type: application/tson' --data-binary '
  !!schema:"https://schemas.example.com/2026/34/app/order-1.tn"
  !order { }'
!!schema:"https://tson.io/2026/34/ltr8/http/problem-1.tn"
!problem { status: 400 title: "Invalid TSON document" detail: "the request body has 2 problems" errors: [
  { path: "/sku" schema_pointer: "/order/sku" code: "FIELD_REQUIRED"
    message: "missing required field \'sku\' for \'order\'" data_position: "3:8:70" ... }
  { path: "/quantity" schema_pointer: "/order/quantity" code: "FIELD_REQUIRED" ... } ] }
```

Both problems, in one response — a client fixing one error per round trip needs one round trip per error.

### Problem types

`type` is the member to match on: it is stable where `title` is prose. Every failure this project produces
carries one of these, under `https://tson.io/2026/34/ltr8/http/problems/`.

| `type` | Status | Raised when |
|---|---|---|
| `invalid-document` | 400 | the body was read against its schema and broke it |
| `malformed-document` | 400 | the body does not lex, does not parse, or is not data |
| `invalid-schema` | 400 | the schema the body names is itself wrong |
| `unusable-schema-reference` | 400 | the body names a schema this server will not load, or nothing serves |
| `no-schema-declared` | 400 | the endpoint requires a version and the body named none |
| `unsupported-schema-version` | 400 | the body names a schema version this endpoint does not serve |
| `malformed-schema-header` | 400 | `TSON-Schema` or `TSON-Accept-Schema` is not a valid sf-string |
| `conflicting-schema` | 400 | header and body both name a schema and they disagree |
| `no-such-schema` | 404 | a schema was requested at a path this server does not publish |
| `method-not-allowed` | 405 | the route does not take this method (adapters) |
| `not-acceptable` | 406 | `Accept` rules out the only representation the route produces |
| `unsupported-media-type` | 415 | the body is not something the route can read |
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
$ curl -s localhost:8080/2026/34/ltr8/http/problem-1.tn | head -1
!!id:"https://tson.io/2026/34/ltr8/http/problem-1.tn"
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
!!schema:"https://schemas.example.com/2026/34/app/validate-1.tn"
!validation_request {
  schema: "!!id:\"...the schema under test...\" ..."
  data:   "!!schema:\"...that same identity...\" ..."
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
must be) and an API description (what an endpoint offers). Revision 34 added [TSON-DATA] §8.2's name-hygiene
policies as a security control with no artifact, and a schema cannot hold one: an artifact that declared its
own strictness would choose its own check, and §3.5 makes a published schema immutable while a policy has to
move. So the descriptor is *data*, it is handed to the process rather than found, and no document may name
one. The demo's sets a token policy, which you can see decide a verdict:

```
$ curl -s localhost:8080/.well-known/tson-deployment
!!schema:"https://tson.io/2026/34/ltr8/http/deployment-1.tn"
!acceptance_profile { name: "validator-demo" tokens: { level: "SINGLE_SCRIPT" permitting: [] } }
```

That is a **projection** of the descriptor, not the descriptor: which origins a deployment will fetch schemas
from is nobody else's business. It is served at a well-known path because everything with an identity in this
series is served at its identity's path, and a descriptor is precisely the artifact that must not have one.
And it is a hint — only the refusal a request actually receives says what applied to that request.

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
