# The demo servers and the validator

The order server each adapter runs, the schemas they share, the concurrency harnesses that drive them, and the JDK-only
validator demo. Current form only. The adapter contract is `design/adapters.md`.

**Invariants**

- The three order servers are the same server: same routes, same schemas, same behaviour, one copy of the schemas in
  `demo/schemas/`. Each adapter's `OrderServerTest` drives the real demo and asserts what its printed `curl` commands
  claim.
- A `com.sun.net.httpserver` with no executor is serial; the JDK demo sets a virtual-thread executor, not a pool.
- The validator takes the schema from the request, so it builds a fresh `Tson` per request, fetches nothing, answers a
  non-conforming document 200, and applies its descriptor's policy to the probe and not to the envelope.
- The validator is JDK-only on purpose: it tests the library, not the adapters.

## Demo servers

Each adapter has an order server, and they are the same server: same routes, same schema, same behaviour, so
the same `curl` commands work against all three. Starting one prints what to try. The validator below is a
second demo, on the JDK adapter only.

```
./gradlew :tson-http-jdk:runDemo
./gradlew :tson-http-javalin:runDemo
./gradlew :tson-http-helidon:runDemo
./gradlew :tson-http-jdk:runDemo -Pport=9000
```


## The validator demo (`ValidatorServer`, JDK adapter)

A second demo, and the opposite one: `OrderServer` is the shape a real service takes — every schema resolved
at startup, shared for reads — while this takes **the schema from the request**, which that shape forbids.
`./gradlew :tson-http-jdk:runValidator`, then open `http://localhost:8080/`.

**It is a conformance tool as much as a demo.** tson.io's home page runs the same pair through the TypeScript
implementation in the browser; this runs it through the Java one over HTTP, on the same scenarios, so the two
verdicts can be put side by side. A difference in code, message or source position is a finding.

**JDK adapter only, deliberately.** The "add a case to all three" rule is about the adapter test suites, which
exist to show three adapters are indistinguishable over one codec. This demo tests the *library*, not the
adapters, so triplicating it would triple maintenance for no conformance value.

**A fresh `Tson` per request, and the two obvious optimisations are both wrong.** Sharing one instance behind a
lock fails *silently*: `validateSchema` registers a sound schema, so the second caller submitting a different
schema under the same `!!id` is told `SCHEMA_ERROR: a schema is already registered under '…'` — a complaint
about their schema that is really about someone else's — and their data is then checked against the first
caller's shape. Clearing the registry between requests puts mutation back on the request path, which is what
the startup rule exists to keep off it. A per-request instance is confined to its thread and discarded, so no
two callers can see each other at all. It costs the standard-library bootstrap, ~18 ms against ~1 ms of actual
validation — which is why `elapsed_ms` times the validation and not the request, so an implementation
comparison measures the validator rather than this decision. Pinned by
`ValidatorServerTest.twoCallersSharingASchemaIdDoNotSeeEachOther`.

**A non-conforming document is a 200.** The request was well-formed and the service answered it; the
diagnostics *are* the answer. A 400 here is always about the envelope and never about the document under test
— otherwise a client cannot tell "you asked badly" from "the thing you asked about is bad", which is the one
distinction this service exists to report. `phase` carries the other half: `SCHEMA` means the schema was at
fault and the data was never looked at. **`outcome` is the `tson` CLI's**, `ACCEPTED`/`REJECTED`/`UNDETERMINED`
by the same rule (any verdict or refusal rejects), so the service and `tson validate` can be compared on it; an
enum rather than a `conforming` boolean, since `if (!conforming)` reads "could not judge" as "rejected". The page
headlines it and keeps one hand-written copy, of the codes that neither are a verdict nor a refusal, held to
the enum by `ValidatorServerTest.thePagesUndeterminedSetMatchesTheEnum`.

**It fetches nothing.** The per-request source serves the submitted schema at its own `!!id` and refuses every
other identity, because a reference in an untrusted document is an untrusted URL and an endpoint that followed
one would be a request forger for anyone who could reach it.

**It runs under a deployment descriptor, and the policy applies to the probe and not to the envelope.** That
split is forced by what a validator is: a descriptor states one process's policies, but applying the token
policy to the envelope would refuse a request whose `data` field merely *contains* a mixed-script value — so
the one service that exists to give a verdict on such a document could never be asked about one. Text a
service acts on and text it is asked about are different surfaces, and only the second is judged. Pinned by
`ValidatorServerTest.theEnvelopeIsNotJudgedByThePolicyItCarries`, which fails the day someone applies the
descriptor to the service's own `Tson` as well.

**The page's TSON is hand-written on both sides, on purpose.** It builds the request by escaping the two
payloads as single-line quoted tokens (§7.2.2 — single-line rather than triple-quoted, since a schema pasted
into a validator is exactly where a `"""` turns up and would close the token early), and reads the reply with
a small hand-rolled reader. Shipping a TSON library to the browser would put a *second* implementation's bugs
between the reader and what the Java one actually said.

They live in a **`demo` source set**, not `main` — compiled by `build`, so they cannot rot against an API
change, but absent from the published jar. The test source set can see them, so each adapter's
`demo/OrderServerTest` drives the *real* demo rather than a copy: a demo nobody exercises is documentation
that quietly stops being true. Those tests assert exactly what the printed `curl` commands claim.


