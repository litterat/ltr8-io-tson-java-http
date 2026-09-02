# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`tson-java-http` demonstrates integrating **TSON** (Typed Schema Object Notation) into a Java HTTP
server: reading `application/tson` request bodies, writing `application/tson` responses, turning schema
violations into HTTP error responses, and serving/fetching schema documents over HTTP.

It is a **consumer** of the TSON library, not part of it. The library lives in the sibling repo
[ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java), checked out at `../ltr8-io-tson-java`
and consumed as a Gradle **included build** (see "Consuming tson-java" below). Destination remote:
`https://github.com/litterat/`.

**Built against 2026 Revision 34** of the spec — the sibling's `spec/` holds the snapshot, and every identity
in this repo carries `/2026/34/`. The revision series changes without compatibility guarantees, so a `git pull`
of the sibling can move the whole identity space; "Project-owned schema `!!id`" below says what that costs.

**Status.** All four modules are built and tested, each adapter with a runnable demo server and a concurrency
suite driving it under load. Responses are self-describing in both directions: an error body names
`problem-1.tn`, a demo's order reply names the schema governing it, and the server publishes both so those
URLs resolve. Every adapter proves the full loop: a schema served at its identity path, fetched back by
`TsonHttpSchemaSource`, and used to validate a document. A service also publishes a description of itself
(`meta-http-1.tn`), from which it derives what to publish and what to warm.

**Don't state a count of anything here.** Test counts and file counts go stale within a day and nothing
maintains them; `ReadmeTest` enforces that rule for the README and it applies equally to this file. Say what
is covered, not how much.

The project does what it set out to do; what remains is polish and the open questions below.

**`TSON-Schema` header — built.** `SCHEMA-HEADER.md` carries the rules and the reasoning; `TsonSchemaHeader` is
the implementation. An RFC 9651 sf-string (quoted, always — an unquoted URI parses as an sf-token right up
until someone pins a schema, which is why the strictness is deliberate); permitted on requests and responses
and on a body of any media type; may coexist with the body's `!!schema` and must then agree by canonical
identity, a mismatch being a 400 rather than a precedence question; and a schemaless body stays valid TSON,
with "must name a schema" living in endpoint policy — which is what `TsonSchemaVersions.route` enforces.

**Hard constraints:**
- Java 25 only (matches tson-java).
- `tson-http` and `tson-http-jdk` take **no external runtime dependencies** — same rule as tson-java.
  The two third-party adapters obviously depend on their server; that dependency stops at their own
  module and never leaks back into `tson-http`.
- Every module has a real `module-info.java`; module names mirror each module's root exported package.
  All three servers are genuine JPMS named modules (`io.javalin@6.7.0`, `io.helidon.webserver@4.2.2`,
  `io.helidon.http.media@4.2.2`, and `jdk.httpserver` in the JDK), so nothing here has to fall back to
  the classpath.

## Modules and dependency direction

Package group `io.ltr8`, as in tson-java (reverse-DNS names who *publishes*, not who blesses).

- **`tson-http`** (`io.ltr8.tson.http`) — server-agnostic core. Everything that is about *TSON over
  HTTP* rather than about a particular server lives here, and each adapter is then a thin translation
  layer. Owns:
  - **Media type handling** — `application/tson`, its optional `version` parameter, `Accept`
    negotiation and q-values. Per [TSON-DATA] §7.1 a TSON document is UTF-8, full stop: a `charset`
    parameter naming anything else is a client error, not a transcoding instruction.
  - **Codec** — request `InputStream` → `TsonValue` (tree mode) or a bound Java object (bind mode);
    object/`TsonValue` → response bytes.
  - **Error mapping** — `Diagnostic` and the exception hierarchy → status code + a TSON error body.
  - **~~`TsonSchemaSource` over HTTP~~ — this module no longer owns one.** `TsonHttpSchemaSource` was built
    here, offered upstream, and accepted: it now lives in tson-java as `io.ltr8.tson.TsonHttpSchemaSource`,
    alongside a `TsonFileSchemaSource` this repo never had and the `SchemaReference` rules they share. Use it
    from there — `TsonConfig.httpSchemas(hosts…)` is the one-call form. **Do not reintroduce a copy here**:
    the reference comes from an untrusted request body, so a second implementation is a second place for one
    security check to drift lenient.
  - **`TsonAcceptSchemaHeader`** — the `TSON-Accept-Schema` request field: which schema versions a client will
    accept **back**. A separate field from `TSON-Schema` on purpose — that one says what *this* body is
    (`Content-Type`), this says what the reply should be (`Accept`), and a POST routinely asks both. Absence
    means the server chooses, so it is additive; `q=0` refuses; nothing acceptable is 406; matching is by
    canonical identity. `TsonSchemaVersions.chooseResponseVersion` is the endpoint-level answer, preferring
    the last version registered unless `preferredResponseVersion` says otherwise. `SCHEMA-HEADER.md` §7.
- **`TsonSchemaHeader`** — the `TSON-Schema` field: sf-string parse/format, and `resolve` reading both
    channels and enforcing agreement. **`TsonHttpCodec.acceptingJson()`** is what admits a JSON body, opt-in.
  - **`TsonSchemaCatalog`** — the schemas a server publishes, indexed by the path each one's own `!!id`
    names, plus the cache policy. Server-agnostic because every adapter needs the same lookup and the same two
    headers; only the routing differs, so an adapter's schema handler is a dozen lines over it.
- **`tson-http-jdk`** (`io.ltr8.tson.http.jdk`) — adapter for `com.sun.net.httpserver` (JDK module
  `jdk.httpserver`). Zero external dependencies, so it is both the "no framework needed" demo and the
  reference the other two adapters are read against — when an adapter's behaviour looks odd, diff it
  against this one. Three types: `TsonExchange` (one request and its response, in TSON terms),
  `TsonHandler` (what a route is written as, plus `asHttpHandler` which wraps it in the error boundary),
  and `TsonSchemaHandler` (serves schemas at their own identity paths).
- **`tson-http-javalin`** (`io.ltr8.tson.http.javalin`) — adapter for Javalin 6.7.0 (Jetty 11, `jakarta.*`
  namespace). Same three types as the JDK adapter, deliberately: `TsonContext`, `TsonHandler` (with
  `asHandler`), `TsonSchemaHandler`. It adds `TsonHandler.install(app, codec)`, which maps a
  `TsonHttpException` escaping a *plain* Javalin route to the same problem body — a real application mixes
  route styles and should answer failures one way.
- **`tson-http-helidon`** (`io.ltr8.tson.http.helidon`) — adapter for Helidon 4.2.2 SE. The same parallel
  types (`TsonContext`, `TsonHandler` with `asHandler`/`install`, `TsonSchemaHandler`), plus the one thing no
  other adapter can offer: **`TsonMediaSupport`**, an implementation of Helidon's `MediaSupport` SPI. Register
  it once and a plain handler reads and writes TSON through `req.content().as(Order.class)` and
  `res.send(order)` with no TSON-specific code — same codec, same validation, same diagnostics.

  **`TsonHandler.install` is not optional when using `TsonMediaSupport`.** The read happens inside Helidon's
  entity machinery, before any handler code runs, so there is no handler boundary to catch a rejection.
  Without `install`, a body that breaks its schema gets Helidon's own error page and the diagnostics are lost —
  and a non-TSON body gets a 500 instead of a 415, because Helidon raises `UnsupportedTypeException` when no
  reader claims the type and never reaches this adapter's code. `install` maps that too, deciding which side
  failed by asking the codec about the *request's* content type: not readable means the client's 415, readable
  means the failure was on the write side and is this server's own 500.

Adapters depend on `tson-http`. `tson-http` names no adapter type, and the adapters never depend on
each other.

### What `tson-http` looks like today

`TsonHttpCodec` is the type an adapter drives; everything else supports it.

- `TsonMediaType` / `TsonAcceptHeader` — value types, no HTTP-status policy. Malformed input throws
  `IllegalArgumentException` and the codec decides what that is worth.
- `TsonHttpCodec` — reads bodies (tree or bound, self-describing or against a stated schema and type),
  writes bodies, and gates on `Content-Type` and `Accept`.
- `TsonHttpException` — status plus diagnostics. **`from(RuntimeException)` is the entire status policy**;
  put nothing status-shaped anywhere else. Problem `type` URIs live under `https://ltr8.io/2026/34/http/problems/`
  — `ltr8.io` is the implementation resource, kept apart from the specification's `tson.io`, where schema
  identities live. The revision rides in it too, so a spec bump moves it with everything else.
- `TsonProblem` / `TsonProblemDiagnostic` / `TsonProblemSchema` / `problem-1.tn` — the error body, its schema,
  and the reader that proves the two agree. **This project's own schema, maintained here** — it began as a copy
  of `tson-cli`'s `diagnostics.tn` and has diverged on purpose: a CLI reports on files and a server reports on
  requests, and lifting the two into a shared module was asked for upstream and rightly rejected. `problem`
  follows **RFC 9457**; `diagnostic` stays close to what a TSON read produces, because that is what it
  reports.
- `io.ltr8.tson.TsonHttpSchemaSource` / `TsonSchemaFetchException` — **upstream's, not this module's** (see
  above). What stays here is the half upstream cannot know: `TsonHttpException.from` maps a fetch `Reason`
  through `Diagnostic.Code.of` into the one fetch status table, and `TsonHttpSchemaSourceIntegrationTest` pins
  that mapping and the codec's end-to-end read of a document whose schema arrived over the wire. The policy itself is upstream's suite — do not retest it here.
  Read the class notes before relying on anything in it; every rule there is load-bearing.

### What an adapter owes the codec

`tson-http-jdk` sets the pattern the other two adapters follow. An adapter is a translation layer and holds
no TSON knowledge of its own; what it *does* own is the error boundary, and that boundary has four jobs:

1. **Check `Accept` before the handler runs.** Every route produces TSON, so a client that will not take it
   is told before the work is done — and only the boundary can make that unforgettable.
2. **Map a failure through `TsonHttpException.from`, and nothing else.** An adapter never re-decides a
   status. What `from` declines to classify is a fault in this server: catch the rethrow, make it a 500.
3. **Never overwrite a committed response.** Status and headers go out together, so after a handler has
   answered, a later failure cannot become a 500 — attempting it throws inside the boundary and loses the
   original. Log it and close instead. `TsonExchange.committed()` is how the boundary knows.
4. **Write what `TsonHttpException.problem()` returns, and make no judgement of your own.** An internal
   message can name a bound Java class, a path, an internal host or a query, and a client is not the audience —
   but **that is a rule about content, and it lives in `tson-http`**, because an adapter that decided it would
   be one security filter existing as three near-copies, free for one of them to drift lenient. There is no
   unredacted overload for the same reason. The exception goes to a `System.Logger` — which keeps that module
   dependency-free, since it may not take a logging dependency.

   **Not a rule about 5xx**, which is the shortcut it used to be and got a 501 wrong: that status says the
   request was fine and this server could not check it, so the violations the read *did* find are the client's
   to act on and are carried, with the detail saying the list is incomplete. Dropping them left a sender
   nothing to act on, so the next request was byte-for-byte the same one. A 500 (our wiring) and a 502/504
   (our dependency) do answer with status, type and title alone. The filter runs at two levels — the failure's
   own account, then each diagnostic — because a gap outranks a fetch failure, so a 501's list can carry a
   `SCHEMA_UNREACHABLE` naming a host beside violations that are the sender's. **Do not key it on
   `Diagnostic.Code.verdict()`**: that asks whether the document was judged, this asks whom the message is
   about, and they cut differently in both directions — `NOT_IMPLEMENTED` is not a verdict and is disclosed.
   A 4xx is the opposite throughout: its detail and diagnostics are the entire point.

A handler that returns without answering is a bug in the handler, so it is a 500 rather than a silent 200.

**A `com.sun.net.httpserver` with no executor is serial**, and that quietly defeated this project's own
concurrency test for the JDK adapter — `OrderServerConcurrencyTest` drove eight client threads at a server
running every handler on its dispatch thread, so two handlers never ran at once and a race in the shared codec
could not have been caught. The demo now sets a virtual-thread executor. **Virtual threads, not a pool**:
`HttpServer.stop()` does not shut down an executor you hand it, so a platform-thread pool keeps its
non-daemon threads alive and the JVM never exits — which cost a hung ten-minute run to discover.

**No lock is taken on the read or write path**, measured rather than assumed: 48,000 requests across 8 threads
produced 1416 contended monitor entries and not one of them has a tson frame. The `synchronized` methods that
exist are on the resolve and register paths, which run at startup on one thread. That is the "build during
startup, then share for reads" shape working.

**Reading the throughput numbers, because two wrong conclusions came out of them first.** The harnesses put
client and server in one JVM, so a single request stream already costs ~3.7 of 16 cores — client, server, GC
and JIT together. Scaling therefore tops out near 16/3.7 ≈ 4x, which is what both adapters show
(10.5k req/s on one thread, 43k on sixteen, at 14 of 16 cores — saturated). **That is a property of the
measurement, not of the library.** Two things to avoid repeating: comparing runs that differ in more than one
variable (a serial server at 8 client threads against a concurrent one at 8 is not a scaling curve), and
trusting a `ps` sample for CPU — use `OperatingSystemMXBean.getProcessCpuTime` over the timed region, which is
what showed the machine was saturated after a `ps` sample suggested it was at a fifth.

**A shared `HttpClient` costs 16–23%** at four threads and up, so a load generator that shares one measures
itself as much as the server. Both harnesses take a flag for a client per thread, built and warmed before the
timed region.

**Concurrency is tested, not assumed.** Every adapter shares one `TsonHttpCodec` across request threads, and
every other test in this repo is single-threaded — so a codec wrong under concurrency would pass all of them.
`TsonHttpCodecConcurrencyTest` and each adapter's
`demo/OrderServerConcurrencyTest` close that gap, and each task checks its own result: the failure worth
looking for is a crossed or torn value, not a call that threw. Writing them is what found a real race in
`DataBindContext`'s descriptor resolution, since fixed upstream. Keep them green, and add to them rather than
around them.

**The adapter test suites are near-copies on purpose.** `TsonJdkAdapterTest`, `TsonJavalinAdapterTest` and
`TsonHelidonAdapterTest` ask the same questions and assert the same answers, because three adapters over one
codec should be indistinguishable from a client's side and the only way to show that is to ask them the same
things. When adding a case to one, add it to all three. The same goes for the three schema-handler suites,
each of which ends with the same serve-then-fetch loop.

**Where they legitimately differ, and how to tell.** `com.sun.net.httpserver` chunks whatever it is given a
length of 0 for; Jetty holds a short response in its buffer, discovers the length, and sends a
`Content-Length` after all. Both are correct HTTP and a client must depend on neither — which is why the
streaming test uses a body past Jetty's buffer, so it asserts that the document was streamed rather than
asserting a framework's buffering habit. A difference that survives that treatment is worth writing down
here; one that does not is a bad test.

### Data can name a schema; only a schema can name a type

The rule behind several decisions here, and worth knowing before designing anything new.

Type-name resolution happens at type-ref positions in a **schema** document and nowhere else. A data document
can name a schema (`!!schema`) and select a type within the scope it names (a `!order` annotation), but a type
name in a value position is an inert token — no namespace is active to resolve it. §7.8's `extern` confirms it:
the sanctioned cross-schema mechanism carries a **value** with a visible scope switch, not a type reference.

Two consequences:

- **Anything whose job is to relate types must be a schema**, not a data document governed by one. That is why
  the API description is a **schema** governed by `meta-http-1.tn`, and why a description written as *data*
  cannot check anything it says.
- **The recurring `(schema identity, root type name)` pair is the data layer's workaround for this.**
  `describing(…)`, `readObjectAs(…)`, the `TSON-Schema` header plus a route-supplied type: two strings
  reassembled at every call site, because the thing they name cannot be referenced.

### Describing an API (`meta-http-1.tn`, `io.ltr8.tson.http.api`)

An OpenAPI-shaped description of an HTTP API whose payloads are TSON — minus the part OpenAPI mostly is.
OpenAPI embeds a schema language because JSON has none; TSON already has published, identity-addressed
schemas, so an operation **references** one.

**The description is a schema, not a document governed by one.** `meta-http-1.tn` is a meta layer declaring
`operation => ~data & { … }`, and a service's description is a schema governed by it whose entries are
operations. That is what makes `request: order` a *reference the compiler resolves* rather than a string —
the property no data-shaped design can have, because a data document can name a schema but cannot hold a
reference to a type.

**The spec makes both halves of that shape normative, and names this as the case they exist for.**
[TSON-SCHEMA] §4.1 declares `data` as the fourth base kind — "an HTTP operation binding request and response
types by name is the motivating case" — with `operation => ~data & { … }` as its own worked example, and makes
naming a `kind: DATA` entry where a type is expected a resolver error. §9's guidance for extension meta-schemas
then requires the other half: a constructor field holding a type reference **MUST** be typed `type_ref`, not
`type_name`, because `type_ref` is what makes the slot participate in flattening, `@alias` recording and
structural identity — and it names `request`/`response` as the example.

**The wiring is three things**: this schema reachable from the `schemaSource`, the bound classes in
`io.ltr8.tson.http.api`, and `TsonApiSchema.metaNameBinder()` on the config — **not** `bindings`, which binds
the data a schema describes. A meta layer's vocabulary is a separate namespace on purpose.

**`TsonApiDescription` has no `validate`, and that is the entire argument for the design.** Its data-shaped
predecessor carried forty lines resolving bare names against an import list, and those lines reimplemented an
upstream namespace bug twice — once by counting how many imports surface a name, and
once by comparing whole `TypeDefinition`s, which differ per route because linking credits each route's own
subtypes. Here an unsound description does not resolve, so the model cannot exist for one.

**Resolving the description is a startup check**, which is why each demo calls `tson.resolve(API)`: a payload
type nothing declares fails the server's startup rather than being published as a contract no client can act
on. And because it is a schema, the catalog serves it like any other — no bespoke route.

**The description is the source of truth for what a server publishes and warms**, not a document beside it:

- `referencedSchemas()` — itself, its meta layer, and its imports transitively, minus the bundled standard
  library. That *is* the catalog. It used to be five hand-written `add` calls that a conformance test asserted
  matched; deriving makes a referenced-but-unpublished schema impossible rather than merely tested for.
- `boundClasses(bindings)` — what to `prepareToWrite`. Hand-listing is how a response type added to a
  description never gets warmed: nothing connects the two lists, and the omission costs only latency, so
  nothing reports it.

**`TsonApiCoverage` holds a server to that contract at startup.** `serving(name)` claims a declared operation
and hands it back — so the path comes from the description wherever the framework can take it — and
`requireComplete()` fails if anything declared went unclaimed. Both directions are caught: an operation the
description does not declare is refused where the handler is written, and a declared one with no handler
fails startup rather than 404ing for a client that read the contract and believed it.

**The opinion has a way out.** "Everything declared is served" is an opinion, and a fixed one would be the
wrong kind of helper — an operation documented ahead of being built, or served by another process behind the
same proxy, are real cases, and a check that cannot express them gets switched off wholesale, taking the
operations it was right about with it. `notServedHere(name, reason)` is the way out and the reason is
required: an exemption somebody has to justify in a greppable string is a decision, where a boolean is a
hole. Readable back through `exemptions()`, and `requireComplete()`'s failure message names the method, so
the way out is discoverable from the error rather than the Javadoc.

**This is the shape to copy for any future helper here**, and it is upstream's: `bindings()` is opinionated
but `dataBindContext()` still exists; `SchemaMetaNameBinder.extendedWith` is public so `metaNameBinder` is not
a black box; and `lenientBinding()` is the model — the way out is a *named position with a rationale*, not a
flag meaning "be sloppy". An opinionated helper is fine. A fixed opinion is not.

**It checks coverage, not path equality, and that is what keeps it framework-agnostic.** The registered path
and the declared path may differ and often must — the JDK demo serves `/{schemaPath}` from a
`createContext("/")` prefix, Javalin needs `/<path>` because an identity path has slashes that only the angle
form matches across, and Helidon uses `any()`. Comparing paths would need a translation table per framework,
and a wrong entry there is a route that silently never matches: a worse failure than the one being prevented.
Claiming by name sidesteps it entirely.

**One operation per endpoint, written out — the template that would collapse them does not apply yet.**
`fetch => <T> !operation { … }` now declares and resolves, but `getOrder => fetch<order>` is refused: naming
an application means writing an alias, an alias names a type, and the entry the application materialises is
`kind: DATA`. So the CRUD-family payoff is visible and not yet reachable, and the longhand below is not a
style choice. `UPSTREAM.md` #1, pinned at both stages by `UpstreamGapsTest`.

**A choice of applications does resolve**, though, and used to be the other half of this — `(resp<order, 201>
| resp<problem, 400>)` lifts each application to its own synthetic entry, carries each value argument as a
`REQUIRED_FIXED` field, and needs no workaround. Noted because the workaround it needed (name each
application as an entry first) is still the obvious thing to reach for. The description here does not use a
choice at all — `responses` is an array of `response` records — so nothing changed; the point is that nothing
has to.

Still not built: the route table proper (registering from the description rather than claiming against it),
which is where that per-adapter path seam would finally have to exist.

**Interfaces beside operations — explored, not adopted.** `experiments/meta-service/` sketches one meta layer for
methods (transport-neutral) and operations (HTTP-bound), and finds the real obstacle: an operation binding a
method declared *elsewhere* must refer to it, and a `kind: DATA` entry cannot be referred to. The two shapes
that work today and the spec change that would settle it are measured and written up there.

**An operation's long description is its `@doc`**, read back with `TsonApiDescription.doc(name)`; `summary` is
the short form. A response and a parameter carry a `description` *field* instead, for a permanent reason —
they are values inside a payload, with nothing for an annotation to attach to.

**A schema entry has two annotation positions and they land in different places.** `@doc:"…" op => …`
annotates the **entry**, read from `entries.getAnnotations(name)`; `op => @doc:"…" { … }` annotates the
**definition**, read from `TypeDefinition.annotations()`. Both are retained, so reading only the second and
concluding `@doc` is dropped is a mistake that looks exactly like a library bug. Pinned by
`UpstreamGapsTest.anEntrysTwoAnnotationPositionsLandInDifferentPlaces`.

**Parameters are where TSON's document-orientation does not reach.** A URL segment cannot carry a record, so
`parameter.type` names a scalar and nothing enforces that. Stating the limit beats papering over it.

**Deliberately absent**, each a decision: security schemes, response headers, links, callbacks, examples,
tags, servers, and multi-media-type negotiation. `security` is the one that would disqualify this for a real
service. Adding any is `meta-http-2.tn` once published, never an edit.

**The description is checked, not just written.** `TsonApiConformanceTest` fetches it **from the running
server**, resolves it through a schema source that fetches from that same server — which proves in one step
that every referenced schema is published and every payload type exists — and then holds the server to what
no compiler can know: that its real responses carry the status and the type the description declares.

### Business errors compose `problem`

A business error — the request was schema-valid and the domain still said no — is **written by the handler, not
thrown**. It composes `problem` (§5.8) so it carries RFC 9457's members and adds its own, which means the error
boundary cannot produce it: the boundary only knows how to render a `problem`, and a `sku_not_found` has a field
`problem` does not.

These types belong in the **application's** schema, importing `problem-1.tn` — `tson-http` owns the transport
envelope, the service owns "SKU not found". One rule that costs time otherwise:

- **Imports are transitive, but name what you use anyway.** A name reaches you through what you import, so
  `orders-errors-1.tn` would get `text` through `problem-1.tn` without saying so. Name `core.tn` too: a
  collision is judged by the *declaring schema's identity*, not by how many routes reach it, so naming a
  shared dependency twice is redundant rather than an error.
- **`errors` stays data-level.** A business failure carries `errors: []` and its own fields. They never
  co-occur anyway — validation is a gate, so business logic is not reached on a document that failed it.

### Serving several schema versions

§3.5 makes a published schema immutable: a shape change is a new document under a new name (`order-1.tn`,
`order-2.tn`), so versions coexist rather than replace each other, and a server outliving one of its clients
serves both. `TsonSchemaVersions` is that, and `TsonDocumentPeek` is what lets it route.

**A `DataBindContext` per version, because binding is name-based.** `DataNameBinder.resolve(String)` is handed a
schema *type name* and nothing else — no schema, no version. Both versions declare `order`, so one binder cannot
map it to two classes. The failure is at least loud: *"the schema's root type `order` binds to OrderV1, which is
not assignable to the requested OrderV2"*. Tree mode has none of this difficulty; one `Tson` holds every version
happily, because no classes are involved. Reach for `TsonSchemaVersions` only in bind mode.

**Reading is governed, writing is negotiated.** A request body names the schema that governs it and `route`
obeys it. A response has no such anchor — a GET carries no body at all — so `chooseResponseVersion` reads the
client's `TSON-Accept-Schema` and picks from what this endpoint serves. Do not reach for `TSON-Schema` on a
request to mean "what I want back": it means what the request body *is*, and a field whose meaning depends on
the method is worse than two fields.

**Routing is a safety feature, not a convenience.** A codec built for v1 will read a v2 document and silently
drop what its class has no component for — `OrderV1[sku=A, quantity=1]`, no error, **no diagnostic even
collecting**. So `route` refuses a document naming a version this endpoint does not serve,
and refuses one naming none. Do not add a fallback that guesses; that is the failure it exists to prevent.
`defaultVersion` exists for an older unversioned client and is off by default for the same reason.

**The schema is honest, and migration across versions is the developer's call.** A v1 document read into a
class that also serves v2 fills the v2-only fields from the constructor that read it — and that constructor
*is* the migration rule, written by whoever owns the domain. It is not a value the format invented: v1's
schema declares no currency, v2's declares a required one, and a document written as v2 has one because
someone decided it does. **Do not go looking for a defaulted-versus-given distinction; TSON deliberately does
not track provenance**, and adding it would push a domain decision back into the format. Written down because
reasoning from JSON-Schema habits produces the opposite conclusion, confidently.

**Two ways to model the Java side**, both tested: a class per version, switching on `Routed.schemaId()`; or
one class holding the union of every version's fields, with a `@Profile`-annotated constructor per version and
`version(…, profile)` naming it. The second **requires** the profile — without one, strict binding refuses the
class, because the union is a shape no version's schema declares, and that refusal is what makes the profile a
decision rather than a default. Two traps: a profile naming no constructor falls back to the canonical one (so
the version whose shape *is* the class needs no annotation), and `@Profile`'s `fields` is effectively required,
since a secondary constructor's parameter names are `arg0`/`arg1` without `-parameters`.

**Multiple constructors do not select a version.** This is the tempting model and it does not work: binding
always uses the canonical constructor — the sole public one, or the `@Record`-annotated one — and passes `null`
for a field the schema does not declare. Measured, not assumed: a two-argument constructor stamping a marker was
never called. A second constructor is for your code, invisible to binding.

**`Routed.schemaId()` is the registered id, not what the document spelled.** A client may write the same
identity with a different scheme or a `?sha256=` pin (§2.2.1); a caller switching on the version must not see
that. An earlier version of this returned the reference and would have broken the moment a client pinned one.

### Identity is not location

The single most important thing to understand before touching the schema source. [TSON-DATA] §2.2.1:

> A reference's **canonical identity** is … **lowercase host plus path** … The scheme is a *transport hint*,
> not part of the name … a consumer **MAY fetch by whichever scheme its policy allows** … an identifying URI
> MUST already be in canonical form apart from scheme and hash query — lowercase host, no userinfo, **no port
> (default or otherwise)**, no percent-encoding of unreserved characters, no dot-segments, and no fragment.

So a schema reference names a document; it does not say where to get it. Two separate questions, configured
separately: `allowHost` decides which schema *names* this server will load (the security boundary), and
`mapHost` decides where the bytes come from. **The no-port rule means `mapHost` is the only way to reach a
non-default port** — an ephemeral test port, an internal endpoint, a mirror — because such a URL can never be
an identity. A mapping renames nothing: the loader still cross-checks the fetched document's `!!id`.

Getting this backwards produces a confusing failure a long way from its cause: a port-carrying `!!schema`
fails inside `TsonCanonicalIdentity.canonicalize` during resolution, with a message about identity and a
stack trace through the resolver.

## Consuming tson-java

tson-java publishes to **mavenLocal only** — deliberately; a remote release is a decision that build does not
make quietly. So a checkout of the sibling is required either way, and there are two paths:

- **Included build — the default.** Gradle substitutes `io.ltr8:tson` with the sibling's own `:tson` project,
  so an edit or a `git pull` there is picked up by a plain `./gradlew build`, no publish step, no stale jar.
  The flip side: a **broken sibling breaks this build**, and compile output names files under
  `../ltr8-io-tson-java/…`. That is expected, not a path bug. Path is `tson.path`, overridable with
  `-Ptson.path=…`.
- **Published artifacts — `-Ptson.published=true`.** Resolves `io.ltr8:tson:${tson.version}` from mavenLocal,
  after `./gradlew publishToMavenLocal` in the sibling. Easy to leave stale, which is why it is not the
  default — but it is the only path that exercises the published POM, module metadata and real jars, so CI
  runs it as well.

**Do not modify `../ltr8-io-tson-java`.** Changes wanted there are written up in `UPSTREAM.md` instead, and
only landed in that repo on the user's say-so. Pulling it is fine when asked.

## The tson API surface this project builds on

Read tson-java's own `CLAUDE.md` and `docs/` before working on the codec — but these are the facts that
shape the HTTP integration specifically:

**Front door.** `Tson.builder()` → `TsonConfig` (`.schemaSource(…)`, `.dataBindContext(…)`) → `Tson`.
Bootstrapping loads meta-kernel + `meta.tn` + `core.tn`. From a `Tson` you get `treeReader()`,
`objectReader()`, `treeWriter()`, `objectWriter()`, `resolve(schemaText)`, and `validate(…)`.

**Read mode is which registry you hold, not a parameter.** `treeReader()` yields an immutable queryable
`TsonValue`; `objectReader()` binds to Java objects. Both readers take `InputStream` as well as `String`
(`read`, `readWithoutSchema`, `readAs`), so a request body streams in without being buffered into a
`String` first.

**Writers take a sink.** `TsonObjectWriter`/`TsonTreeWriter` have `write(value, OutputStream)` and
`write(value, Appendable)` alongside `toTson`; the stream is flushed and not closed, so it stays the
adapter's. `TsonHttpCodec.writeTo`/`writeTreeTo` stream an ordinary response; `write`/`writeTree` buffer for
a caller that wants `Content-Length`. **An error body is only ever buffered** — streaming one means a failure
part-way through leaves a client holding a truncated problem on a response whose status is already sent.

**A self-describing document carries its own `!!schema` directive** and the reader resolves it through
the configured `TsonSchemaSource`. This is the crux of the HTTP story: a schema URL arriving in a request
body is an **untrusted URL**, so the HTTP-backed source must be policy-gated (allow-list of origins,
timeouts, size cap, cache) rather than fetching whatever it is handed. Never wire a naive fetcher in.

**Error classification is already a policy upstream — mirror it, don't invent one.** tson-java splits:
the author's document/schema is wrong per the spec; the library hasn't implemented that yet
(`UnsupportedOperationException`, where it is raised outside a read); `IllegalStateException` = an
internal invariant broke. The CLI rides its exit codes on that split (1 = your document is bad, 70 = a fault
in the library), and **the HTTP mapping is the same split wearing status codes**: validation → 4xx, gap or
internal fault → 5xx. A gap must never be reported to a client as "your request was invalid".

**Ask the `Diagnostic.Code`, not the exception type — the split is no longer one type per outcome.** A read
gap is now *reported* like any other problem rather than thrown, so it reaches a collecting caller as a
`NOT_IMPLEMENTED` diagnostic among the rest, and a fail-fast one as a `TsonReadException` carrying that same
code — the very type a schema violation arrives as. Classifying by type therefore answers 400 for a gap,
which is the one verdict this policy may never give. `TsonHttpException.invalidDocument` holds the rule and
`from` routes every `TsonReadException` through it, so both channels answer alike. Upstream states the rule
as *"asking by code rather than by exception type is the stated policy"*.

**Seven codes are not verdicts on the document, and they differ by who could not give one** — this library, the
reading application, whoever was to serve the schema. `Diagnostic.Code.verdict()` is that set, stated
upstream so no consumer keeps a private copy; **use it rather than listing them**.

| Code | Who | Status |
|---|---|---|
| `NOT_IMPLEMENTED` | this library has not built it | 501 |
| `BIND_MISMATCH` | this server's own wiring | 500 |
| `SCHEMA_UNREACHABLE` | the origin could not be reached | 502 |
| `SCHEMA_TIMEOUT` | the origin did not answer in time | 504 |
| `SCHEMA_NOT_PERMITTED` | policy refused the reference | 400 |
| `SCHEMA_NOT_FOUND` | nothing serves the reference | 400 |
| `SCHEMA_TOO_LARGE` | the document exceeds what a schema may be | 400 |

**Not being a verdict does not settle the status**, and the fetch codes are where that shows. `verdict()`
answers *was the document judged*; a status answers *who must act*. For a reference this deployment will not
fetch, cannot find, or finds too large, the body went unchecked **and** the sender still holds the fix — so
those are 400s. The invariant that does hold is the other direction, and `TsonHttpCodecTest`.
`everyCodeEarnsAStatusAndNoVerdictBecomesAServerFault` pins it: **a code `verdict()` calls true may never be
answered 5xx.** That is the failure the classification exists to prevent — telling a sender the server broke
when their document really was wrong sends them round a loop that cannot terminate.

Ranking, most-inward actor first: `BIND_MISMATCH` (an operator has to fix it, and until they do nothing else
is evaluated) → `NOT_IMPLEMENTED` → `SCHEMA_UNREACHABLE` → `SCHEMA_TIMEOUT` → the three 400 fetch codes → §8.2
refusals → ordinary violations. The two world's-doing fetch codes come before the three document's-doing ones
so a mixed failure is never blamed on the client; between them, an origin answering with something that is not
a document is less likely to right itself than one that was slow. `TsonHttpException.FETCH_RANKING` states it.
**This is a scan in rank order, not a first-match on the diagnostic list** — the status used to come from
whichever fetch reason was reported first, so document ordering decided whether the sender or the dependency
was blamed.

**`SCHEMA_TOO_LARGE` is a 400 and reads like a 502.** Retrying shrinks a schema no more than it conjures a
missing one, so a 502 there would advertise a retry that cannot help. It goes with `SCHEMA_NOT_PERMITTED` and
`SCHEMA_NOT_FOUND` under `unusable-schema-reference` — a size cap is exactly a reference this deployment will
not fetch. The CLI reaches the same place from the other side, ranking it permanent.

**Both channels must answer alike, and there is now one table rather than two held together.** One fetch
failure reaches a consumer two ways: thrown as `TsonSchemaFetchException` (essentially startup-only, since
**every read through the codec collects**) and collected as a diagnostic, the common path. The two speak
different enums — thrown carries a `Reason`, collected a `Code` — so `TsonHttpException.from` maps through
`Diagnostic.Code.of(reason)` into `fetchFailure`, which is the only fetch status table. Do not add a second
switch over `Reason`: that is what they were before, and they diverged, `NOT_PERMITTED` answering 400 thrown
and 502 collected. The agreement tests stay
(`TsonHttpCodecTest.aFetchDiagnosticIsAnsweredByItsCode`,
`TsonHttpSchemaSourceIntegrationTest.bothChannelsAnswerAnUnfetchableSchemaAlike`).

**The reason is the code, and must not also be a field.** `Diagnostic.fetchReason` is gone upstream and
`problem-1.tn` no longer declares a `fetch_reason` enum or field. A second carrier for one fact is free to
disagree with the first — on the wire that means a body stating a code and a reason that contradict each
other, which the schema would still call valid. Pinned by
`TsonProblemSchemaTest.theSchemaCarriesNoSecondCarrierForAFetchFailure`.

`Diagnostic.Code` is the detail vocabulary — mostly 4xx, but see the table above for the seven that are not:
`FIELD_REQUIRED`, `FIELD_FIXED`, `TYPE_MISMATCH`, `WRONG_ARITY`, `UNKNOWN_TYPE_REF`,
`ATOM_CONSTRAINT_VIOLATION`, `UNRECOGNIZED_FIELD`, `DUPLICATE_MAP_KEY`, `DUPLICATE_FIELD`, `CONFUSABLE_NAMES`,
`RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`, `SCHEMA_ERROR`, `UNKNOWN_TYPE`, `VALIDATION_ERROR`,
`NOT_IMPLEMENTED`, `BIND_MISMATCH`, `SCHEMA_NOT_PERMITTED`, `SCHEMA_NOT_FOUND`, `SCHEMA_UNREACHABLE`,
`SCHEMA_TIMEOUT`, `SCHEMA_TOO_LARGE`.

**`TsonSchemaFetchException` lives in `io.ltr8.tson.compiler`**, beside the `TsonSchemaSource` interface whose
contract it is — `fetch` names it as the one way a source says "cannot supply this", which is what lets the
classification route on it at all.

**Name hygiene is policy, not validity, and it arrives as three more codes.** [TSON-DATA] §8.2 is a layer
two conforming processors may legitimately disagree on, which is why its defaults are the library's choice
rather than the format's. **One code per rule**: `CONFUSABLE_NAMES` (two names in one scope with equal
UTS #39 skeletons), `RESTRICTED_CHARACTER` (a character outside the identifier profile) and
`RESTRICTED_SCRIPT` (a script combination the level does not admit — wider than a mix, since `ASCII_ONLY`
refuses a single-script name with nothing mixed).

**All three are 400**, and it is pinned rather than left implicit
(`TsonHttpCodecTest.nameHygieneIsAVerdictOnTheDocument`): these are the first codes a server can meet because
of its *own* configuration, and the temptation is to read "my policy refused it" as a 5xx. It is not — a body
refused under a raised policy is refused as one over a size limit is, and it is still the client's to fix.
None of them says anything went **unchecked**, which is what the three 5xx codes have in common and these
three do not.

**The rule rides the code, not a field beside it**, and that is worth knowing before designing anything
similar: a consumer routes on the code, and a second enum would restate what the code already fixes while
being free to disagree with it. The three want three different fixes — rename one of a colliding pair, change
a character, or relax the level — so the code has to carry enough to say which.

**A refusal carries its code and nothing about the policy that judged it.** The level, the unit and the
Unicode data version are properties of the processor, constant for its life, and tson-java states them once —
`Tson.processorPolicy()` returns a `TsonUnicodeProcessorPolicy` (both policies plus `unicodeDataVersion`), and a
derived reader answers for itself — rather than on each refusal; `Diagnostic` carries no per-refusal version.
Over HTTP the once-statement is `deployment-1.tn`'s acceptance profile at `/.well-known/tson-deployment`. §8.3
is why any of it matters: all three rules are unstable across Unicode releases, so two conforming processors
may legitimately disagree about one name and the version is what explains it.

**A refusal is a 400 of its own problem `type`, one per code, and the body carries no policy.** tson-cli's
envelopes carry `policy` on every run because a report file has nothing to dereference; an HTTP response does,
and RFC 9457's own pointer from a problem to deployment-scoped documentation is `type`. So
`TsonHttpException.policyRefusal` types a refusal `…/problems/restricted-script` (the code's name, kebab-cased)
and that documentation is where a client is sent to `/.well-known/tson-deployment`. Embedding the profile in
every refusal would be the constant-restated-N-times mistake upstream just removed from the diagnostic, one
level up. Not adding a `policy` member was therefore a layering decision, not an RFC 9457 constraint — extension
members are the standard's own growth mechanism and `errors` already is one. If a client ever needs to know
*which version* of the profile judged it, the answer is a `Link` header plus the profile's `name`, added then.

**The defaults are opposite on purpose**, and a server inherits both: `TsonConfig.identifierPolicy` defaults
to Highly Restrictive over *declared names*, so a schema a request body names is refused for a homograph;
`TsonConfig.tokenPolicy` defaults to unrestricted over *values*, because data may legitimately be a Cyrillic
display name and no scan runs at all. Pinned by
`UpstreamGapsTest.aDeclaredNameDefaultsToHighlyRestrictiveAndAValueToUnrestricted` — asserting one half would
leave the other free to move.

**Raising the token policy is the application's call, not this library's.** §8.2's "Values" paragraph names
this project's exact situation — a service that renders or matches untrusted values faces on values the
spoofing surface §9.4 raises for names — and says such a deployment applies the level *knowingly*.
`tson-http` never builds the `Tson`, so it has no place to decide; a service that renders what it reads should
pass `tokenPolicy(...)` where it builds one. Two traps if it does: a token policy stricter than the identifier
policy **subsumes** it, since the check runs before anything knows which tokens are names; and a per-segment
policy is refused outright at that setter, `_` and `-` being word separators in a name and ordinary characters
in a value, so segmenting one would admit UTS #39's own `Toys-Я-Us`.

**Concurrency — the load-bearing constraint for a server.** tson-java is not documented as thread-safe as
a whole, and parts of it explicitly are not (`TsonCompiledMetaRegistry`: "`register`/`get` are
`synchronized`, but `loadMeta` … are not"; `Lexer` and `TsonDataEmitter` are single-use, not thread-safe).
What *is* built for cross-thread use is the post-compile read path — `CompiledReaders` holds its binding
`volatile` precisely because the compile happens on one thread and reads happen on many. So the required
shape is:

> **Build the `Tson` instance and resolve/compile every schema during startup, on one thread. Only then
> share it across request threads, and only for reads.** Never resolve a schema lazily from a request
> handler.

A schema arriving at runtime (an unknown `!!schema` URL) must go through a single guarded resolution
path, not a concurrent one. The contract is now stated where a server reads it — `Tson`'s class Javadoc
(concurrent reads through one instance are safe; on a race work may be duplicated but state never is;
`resolve`/`validateSchema` and mutating a `DataBindContext` under live reads are outside the guarantee) and
`TsonConfig.dataBindContext` — and pinned upstream by `ReadPathConcurrencyTest` and
`SharedInstanceConcurrencyTest`. `TsonHttpCodecConcurrencyTest` is the same claim measured from this end.

## Traps — read before touching the code involved

Each cost a debugging cycle here and is pinned by a test.

- **`TsonHttpException.from`'s base-syntax branch is a net, not a path.** A document that will not parse is
  reported through the receiver, so a collecting read returns its diagnostics rather than throwing.
  `Diagnostic.ofBaseSyntaxError` stays in `from` because it classifies the three base-syntax exception
  types — two of which live in an unexported package, so no caller here could
  `catch` them — and **rethrows anything else**, which is what stops an unexpected fault becoming a false
  verdict about the request. Do not delete it for being unreachable.
- **`problem-1.tn`'s `diagnostic_code` is a hand-written copy of `Diagnostic.Code`.** Nothing but
  `TsonProblemSchemaTest` checks it is current, and an error body emitting a code its own schema rejects would
  not otherwise be caught, since no fixture produces a code that is new. The Java enum is the source of truth —
  never check this schema against tson-cli's, which would only prove they drifted together.
- **A `text` field accepts any token, including `42`, `true` and `2026-01-01`.** It rejects only what is
  not a token at all — an array, a record. Correct per spec, and it reliably reads as a bug: [TSON-DATA]
  §4 says base type resolution does not apply at a schema-typed position, and §7.1's "form is not meaning"
  makes a type contract operate on the token's *text*, not on how it was written. A handler that needs
  string-ness says so with a `pattern`, not by assuming `text` means it. Pinned by
  `TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
- **A bound class guards its own optional lists.** An optional field a document omits reaches the constructor
  as `null`, and the binder does not normalise it — the convention upstream follows is that the record does,
  in its compact constructor, as `RecordBody`, `TypeDefinition` and `TypeRef` all do. `Operation` guards
  `parameters` for that reason. A **required** list is deliberately not guarded: it never reaches a
  constructor, because the reader reports `FIELD_REQUIRED` and abandons the construction first, so a guard
  there would mask a real violation. A `references()` that returns such a `null` is refused by name — a
  `TsonBindMismatchException` naming the class — rather than being an NPE out of `Tson.resolve`; the guard is
  still the class's job, the failure just no longer reads as a library fault.
- **A bound class must be public.** tson-java declares no `opens` and binding only ever touches public
  constructors and methods, so a package-private record fails analysis with a bare `DataBindException:
  Failed to resolve` that names nothing useful.
- **Object binding needs bindings.** The class passed to `readObject` is the expected *result*, not the
  mapping. Use `Tson.builder().bindings(Map.of("order", Order.class))`, which builds the whole context — the
  map as a name binder, chained over the kernel's vocabulary rather than replacing it, with the atom
  registrations applied. Do **not** hand-roll `DataBindContext.builder()` for this: two of those three steps
  are invisible, and missing either fails a long way from the cause. `bindings`/`profile` are mutually
  exclusive with `dataBindContext` — a context is built or given, not both.
- **A type nothing binds is a 500, not a 501.** It is a `TsonMissingBindingException` naming the map, deferred
  to the first read of that specific type (a schema legitimately declares types a consumer never binds). It
  used to present as `UnsupportedOperationException: no usable compiled reader`, which this project mapped to
  501 — reporting a missing line of its own configuration as "this library cannot do that". Pinned by
  `TsonHttpCodecTest.aTypeNothingBindsIsAServerFaultNotALibraryGap`.
- **A JSON body names neither its schema nor its root type**, and cannot — directive syntax is not JSON. So the
  schema comes from the `TSON-Schema` header and the root type from the route, which means reading one is
  `readObjectAs`/`readTreeAs`, never the bare `read`. Same two-part requirement as `describing()`, same reason.
- **Peek a header with `TsonDocumentHeader`, and use `peekResumable` for a request body.** A body is one-shot
  — no mark, no rewind — and `peekResumable` records what the lexer pulled and hands back the document from
  its first byte, so looking costs the reader nothing. **A `ByteArrayInputStream` will not catch a mistake
  here**: it supports `mark`/`reset`, so a peek that consumes the stream still looks intact. Test with a
  stream whose `markSupported()` is false, as `TsonSchemaHeaderTest` does — this project shipped a peek that
  ate the whole body on a real request and had a green test suite over the wrong fixture.
- **`describing()` needs a root type name as well as a schema URI, for an object.** A bound record writes no
  type-ref of its own, so `!!schema` alone yields a document whose reader cannot select a type. The tree form
  takes one argument, because a tree node already carries a type-ref. `TsonHttpCodec.write(value, schemaUri,
  rootTypeName)` and `writeTree(value, schemaUri)` mirror that asymmetry deliberately.
- **Binding is strict, and a mismatch is a startup failure if you let it be.** A schema field with no
  component, or a component no field fills, is a `TsonBindMismatchException` when the schema compiles in bind
  mode. `TsonHttpCodec.prepareToRead(schemaId)` forces that at startup and `TsonSchemaVersions` calls it —
  without it the same mistake is a 500 on the first request that reads one. `@Unbound` marks a component that
  is the class's own; `TsonConfig.lenientBinding()` is the deliberate versioned-evolution position. **A
  mismatch reaching the diagnostics channel is still reported as `SCHEMA_ERROR` and so becomes a 400** —
  routing and `prepareToRead` are what keep it unreachable.
- **`prepareToWrite` is a warm-up, not a correctness measure** — it was one, before the descriptor race was
  fixed upstream. Keep calling it at startup to move first-write descriptor resolution off the request thread;
  do not treat it as load-bearing for concurrency any more.
- **A schema reference may not carry a port, userinfo or a fragment** (§2.2.1), so a schema origin cannot run
  on a non-default port. Use `mapHost`. See "Identity is not location" above — this is the trap that costs the
  most time, because the failure surfaces from the resolver rather than from the fetch.
- **A `pattern` is I-Regexp, and `^`/`$` are not anchors.** The kernel pins `regex` to RFC 9485
  ([TSON-SCHEMA] §4.2, "pinned to I-Regexp"), whose match is against the **whole** token — so a pattern needs
  no anchors, and writing them adds two literal characters no token has. It fails in the worst way: the
  schema compiles, and every value is refused with *"'Latn' does not match the required pattern
  `^[A-Za-z][A-Za-z_]*$`"* — a message that reads like the value is wrong when the pattern is. Reflex from
  JSON Schema, which is unanchored and needs them. `deployment-1.tn`'s `script_name` is the one pattern here.
- **Use `TsonSchemaSource.ofMap`, never `map::get`.** `TsonSchemaFetchException` is the whole contract for
  "cannot supply this", and a source returning `null` is now refused by name rather than dereferenced — but
  refused is still a failure, and `ofMap` is the form that does not fail: it throws `NOT_FOUND` for a miss and
  compares by **canonical identity**, so a reference differing only in scheme or `?sha256=` pin still resolves
  (§2.2.1). A hand-rolled map source gets the first half and misses the second. All three demos shipped
  `schemaSource(schemas::get)`, which was an NPE — and so a 500 any client could produce at will, by naming a
  schema nobody publishes. Pinned by `UpstreamGapsTest.ofMapRefusesAMissAndComparesByCanonicalIdentity` and,
  in all three adapters, by `OrderServerTest.aDocumentNamingAnUnknownSchemaIsTheSendersMistake`.
- **Never `computeIfAbsent` on a schema cache.** It holds a `ConcurrentHashMap` bin lock for the whole of a
  network fetch, blocking every other thread whose key lands in that bin — and stalling a resize — for as long
  as the timeout allows. `TsonHttpSchemaSource` uses get-then-put; two threads racing one identity fetch it
  twice and store identical content, which costs a request and breaks nothing.

  *Not* because the loader is re-entrant: it isn't. It fetches a document, returns, and only then resolves and
  fetches its imports, so `fetch` is never called from inside `fetch` (measured — max depth 1). Kept here
  because the reasoning was worked out in this repo and reads as a bug to anyone who meets get-then-put cold;
  the code and its concurrency test are upstream's now, so this is a rule to know rather than one to maintain.
  An earlier version of this note claimed the loader was re-entrant.
- **Policy is checked on every reference, cached or not.** A cache hit must skip the network, never the
  allow-list — otherwise a schema fetched for one request becomes fetchable for a request that would have been
  refused.
- **`readAs` requires a schema URI.** Selecting a root type is meaningless without a schema to select it
  from; `readTreeAs`/`readObjectAs` therefore take one, and it must already be registered. Passing an
  unregistered URI is a server configuration error and surfaces as 500, by design.

## Media type and file extension

`application/tson`, optionally `application/tson; version=1` where HTTP-context disambiguation is needed
([TSON-DATA] §7.1, intended for IANA registration). A document's own header determines whether it is a
data or a schema document, in at most two directives of lookahead and with no value parsing — so a
handler can classify from the opening bytes without a full parse.

**Use the `.tn` extension, not `.tn1`**, matching tson-java. §7.1 now says this outright rather than leaving it
to be inferred: `.tn` is the extension of the 2026 revision series and makes no stability claim, while `.tn1` is
a positive claim of TSON version 1 stability that **MUST NOT** be used before that freeze. The rule has a sting
in its tail for this repo — renaming a document to `.tn1` at the freeze changes its identifying URI and so its
canonical identity, and references pinned during the revision series do not carry over. So the eventual freeze
is a re-pinning exercise across every `!!id`, `!!import` and constant here, not a rename.

**Immutability binds a *published* schema, and nothing here is published yet.** §3.5 makes a schema immutable
once it is available for someone else to pin — that is what the rule protects: a document that named it must go
on resolving. Until this project is released, **edit the file in place and do not bump the version.**

This was learned the expensive way. `problem-1.tn` was bumped to `-2`, `-3` and `-4` because each new
`Diagnostic.Code` member is a shape change — which it is, but the rule only starts applying at release. Three
bumps, sixteen files each, for a document nothing outside this repo had ever seen. They have since been
collapsed back to a single `problem-1.tn`.

**Once this *is* published**, the rule is the real one and the machinery is already here:
`TsonProblemSchema.publishedSources()` returns the whole history (one entry today) and the demos publish all of
it; `publishedById()` is the same keyed by identity, for a caller wiring a schema source by hand. Two traps
worth keeping from the bumps:

- **A hand-wired source serving the current text at a superseded URI fails as an "identity mismatch"** from
  the loader — a long way from the map that was actually wrong. Use `publishedById()` even at one version.
- **Don't restate the current version in a document that could interpolate it.** The demos' own error schemas
  build their `!!import` from `TsonProblemSchema.ID`; most of those sixteen files were demos hardcoding a
  constant.

**`TsonProblemSchemaTest` is what catches a stale `diagnostic_code`**, and it is the only thing that does: an
upstream revision adding a code is otherwise invisible here until an error body emits one its own schema
rejects. It has caught every addition so far. Keep it.

**Project-owned schema `!!id`** follows tson-java's convention with this repo's own group:
`https://tson.io/2026/34/ltr8/http/<name>-<version>.tn` — `/2026/34` the spec revision, `ltr8` the
publishing org, `http` the subsystem. The version in the name is real, but see above for when bumping it is
required rather than reflexive.

**A spec revision moves every identity in the repo**, this project's own and the three bundled ones alike, and
it is one substitution across everything — `.tn` sources, Java constants, test fixtures, `README.md`,
`SCHEMA-HEADER.md`. Nothing derives the revision from a constant and nothing should: an identity is a literal in
a published document, which is exactly why `OrderServerTest.identitiesMatchTheConstants` holds the demo schemas'
literals to the Java constants rather than letting either side interpolate. So the check that the bump is
complete is that no `2026/<previous>` string survives anywhere, and then a green build — the three bundled
identities move at the same time, so a missed one fails to resolve rather than resolving to something stale.

## Conventions inherited from tson-java

These are not restated preferences; matching them is what keeps the two repos readable as one body of work.

- **Javadoc documents current contract only, no change history.** No dates, no "renamed from X", no "used
  to do Y". State the current invariant and its rationale. When you edit a class, clean up its Javadoc in
  the same edit, including narrative you didn't write.
- **`Tson` is a prefix, never an infix**, and it is not applied to everything. A class name containing
  `Tson` must lead with it (`TsonHttpCodec`, not `HttpTsonCodec`). Reserve the prefix for types a
  *consumer of this library* names in their own code, where it disambiguates against their own
  `Codec`/`Problem`/`MediaType`; leave internal machinery bare.
- **Wrap comments and code to 125 characters.**
- **Spec feedback is a deliverable.** This is the spec's first implementation, and an HTTP integration
  exercises §7.1's media-type prose that nothing else has. Ambiguity, inconsistency, underspecification,
  or plain error goes into tson-java's `SPEC-FEEDBACK.md` — raise it in conversation, and record it there
  rather than silently picking an interpretation. Since that repo is read-only for now, stage such
  findings in `UPSTREAM.md` under "Spec feedback to file".
- **Cite the spec, not the argument that got it there.** That register holds only what is open against the
  current revision and renumbers from #1 whenever a revision closes, so a `SPEC-FEEDBACK.md #N` here is a
  reference to a *live* entry and goes stale the moment the spec adopts it. Prose and Javadoc name the section
  that requires the behaviour; the number is for a finding with no section to point at yet. **Re-check every
  such citation on a revision bump** — Revision 34 carried fourteen of the seventeen entries that register
  held, which is why nothing here cites it by number any more.

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

### Deployment descriptors (`deployment-1.tn`, `TsonDeployment`)

**The third artifact kind**, beside a schema (what a document must be) and an API description (what an
endpoint offers): how *one instance* is configured. Revision 34 added the §8.2 policies as a security control
with no artifact, and this is a proposal for where it lives. The full argument is in `deployment-1.tn`'s own
`@doc` and, as filed, in tson-java's `SPEC-FEEDBACK.md`; the short form:

- **Not a schema.** An artifact declaring its own strictness chooses its own check, and §3.5's immutability
  means raising a level mints a new identity, so every document pinning the old one keeps the old policy.
  §8.3 settles it independently: skeleton distinctness does not compose across `!!import`, so the policy is a
  property of the merged namespace rather than of any one schema.
- **Not an API description**, which is a schema here and inherits both, and which would put policy in a
  contract — raising a token policy would mean publishing a new description.
- **It is data**, and that line is the useful one: an API description *must* be a schema because
  `request: order` is a type reference; a descriptor references no types, so nothing about it needs a
  namespace.

**Two rules, enforced by shape rather than by documentation.** `TsonDeployment.read` takes source text — there
is no search path and there must not be one, because a runtime that loads whatever it finds lets a container
image change a security policy with no code diff. And no document may name a descriptor: nothing registers one
with a schema source, and the catalog never serves one. `deployment-1.tn` itself *is* published, because a
client needs it to read the profile.

**An absent policy is not a permissive one.** The two defaults point opposite ways (Highly Restrictive over
declared names, unrestricted over values), so `identifierPolicy()`/`tokenPolicy()` return empty and `applyTo`
leaves a config alone rather than overwriting it with a guess.

**The profile is derived, and it is a hint.** `profile()` drops the fetch allow-list and the listener —
internal topology — and a server publishes *that*, at `/.well-known/tson-deployment`. A well-known path
because everything with an identity is served at its identity's path, and a descriptor is precisely what must
not have one. It can be cached and go stale; only the refusal a request receives says what applied to it,
which is where §8.2 puts the policy. `unicode_data_version` is read from
`TsonUnicodePolicy.dataVersion()` rather than copied — a constant would go stale silently on an upgrade —
and it is in the profile because §8.3 marks all three rules unstable across Unicode releases, so two
conforming processors may legitimately disagree about one name and the version is what explains it.

**`restriction_level` copies `TsonUnicodePolicy.Level` by hand**, held to it by
`TsonDeploymentTest.everyRestrictionLevelIsDeclaredInTheSchema` — the same discipline `diagnostic_code` gets,
and the same failure if it lapses: a level added upstream that a descriptor can name and nothing can read.

### The validator demo (`ValidatorServer`, JDK adapter)

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
fault and the data was never looked at.

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

## Build and test

No system Gradle — always use the wrapper (Gradle 9.4.1, Java 25 toolchain):

```
./gradlew build                       # also builds the included tson-java build
./gradlew build -Ptson.published=true # against tson-java's published artifacts instead
./gradlew test
./gradlew :tson-http:test --tests "io.ltr8.tson.http.TsonHttpCodecTest"
./gradlew :tson-http-helidon:test --tests "io.ltr8.tson.http.helidon.demo.OrderServerTest"
./gradlew :tson-http:test
./gradlew :tson-http-jdk:test
./gradlew :tson-http-javalin:test
./gradlew :tson-http-helidon:test
./gradlew javadoc                     # NOT reached by `build` -- its own task tree, as in tson-java
./gradlew -Ptson.path=/path/to/tson-java build
```

Test the adapters over **real HTTP** — start the server on an ephemeral port and drive it with
`java.net.http.HttpClient` — rather than by calling handler methods directly. The point of three adapters
is that each framework's own body/content-negotiation handling behaves differently, and only a real
request exercises that.

## Files

- `UPSTREAM.md` — changes wanted in `ltr8-io-tson-java`, plus spec feedback staged for its
  `SPEC-FEEDBACK.md`. **The only place upstream changes are recorded** while that repo is hands-off, and
  **it holds only what is open**: an item whose change landed, or whose answer was a decision, is deleted
  rather than struck through, so its numbers renumber and an `UPSTREAM.md #N` citation needs re-checking
  whenever it is pruned. Where a gap has closed, state the rule it left behind where it applies — in the
  Javadoc of the class that relies on it, or in "Traps" above — with no number at all.
- `demo/schemas/` — the three schemas the demo servers publish (`order-1.tn`, `orders-errors-1.tn`,
  `orders-api-1.tn`), as real `.tn` files on the demo source sets' shared resource path rather than Java text
  blocks. One copy for three adapters, so a change cannot land in one demo and not the others. They name
  their imports **literally**, as a published document must, and each `OrderServerTest.identitiesMatchTheConstants`
  holds those literals to the constants — which is what the old string interpolation gave for free.
- `tson-http/src/main/resources/deployment-1.tn` — the deployment-descriptor schema and the
  `acceptance_profile` projection published from it. A proposal, like `SCHEMA-HEADER.md`'s field and
  `meta-http-1.tn`, carrying its own argument in its `@doc`.
- `tson-http-jdk/src/demo/resources/` — the validator demo's own schemas (`validate-1.tn`,
  `validate-api-1.tn`) and its page (`validator.html`). **Not** in `demo/schemas/`, which is the three order
  demos' shared resource path; these belong to one demo on one adapter.
- `tson-http/src/main/resources/meta-http-1.tn` — the meta layer an API description names. Picked out of four
  designs explored side by side (three as schemas, one as data); the comparison is in git history, and why this
  one won is in "Describing an API" above.
- `experiments/` — design explorations kept compiling and passing rather than archived: each in its own directory
  with the schema, examples and a README stating the question and what was learned, and its probe tests joined to
  `tson-http`'s test source set from there (`demo/schemas/`'s pattern applied to design work). Not a commitment
  — nothing ships from one, and a probe failing after a library change is information, not a regression.
  `experiments/README.md` indexes them.
- `scratchpad/` — where a standalone reproducer for an upstream item goes, written to drop straight into the
  sibling's own test tree. Empty when nothing is open enough to need one; a reproducer for a gap that has since
  closed is deleted with its `UPSTREAM.md` entry. **Never copy one into `../ltr8-io-tson-java` and leave it
  there** — that repo is hands-off, and a stray test in it is a change nobody asked for.
- `UpstreamGapsTest` — every gap and constraint in the library that this project depends on knowing about,
  so a change upstream fails a test here rather than passing unnoticed. **It outlives `UPSTREAM.md`'s
  entries**: a fixed gap flips its assertion rather than losing it, so most of what it pins has no entry any
  more, and only a still-open gap names a number. **Pin the gap, not the way it is delivered**: one of these
  once asserted that resolution *throws*, stopped throwing when gaps became diagnostics, and read exactly like
  the feature landing.
- `SCHEMA-HEADER.md` — the proposal for naming a governing schema in an HTTP header. A design document for the
  spec author, not a description of anything built.
