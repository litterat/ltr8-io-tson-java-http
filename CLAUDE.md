# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`tson-java-http` demonstrates integrating **TSON** (Typed Schema Object Notation) into a Java HTTP
server: reading `application/tson` request bodies, writing `application/tson` responses, turning schema
violations into HTTP error responses, and serving/fetching schema documents over HTTP.

It is a **consumer** of the TSON library, not part of it. The library lives in the sibling repo
[ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java), checked out at `../ltr8-io-tson-java`
and consumed as a Gradle **included build** (see "Consuming tson-java" below). Destination remote:
`https://github.com/litterat/` — not yet pushed.

**Status.** All four modules are built and tested (168 tests), each adapter with a runnable demo server and a
concurrency suite driving it under load. Responses are self-describing in both directions: an error body names
`problem-1.tn`, a demo's order reply names the schema governing it, and this server publishes both documents so
those URLs resolve. Every adapter proves the full loop: a schema
served at its identity path, fetched back by `TsonHttpSchemaSource`, and used to validate a document. The
project does what it set out to do; what remains is polish and the open questions below.

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
  - **`TsonSchemaSource` over HTTP** — `TsonHttpSchemaSource`: host allow-list, host→location mapping,
    caps on size and time, identity-keyed cache.
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
  put nothing status-shaped anywhere else.
- `TsonProblem` / `TsonProblemDiagnostic` / `TsonProblemSchema` / `problem-2.tn` — the error body, its schema,
  and the reader that proves the two agree. **This project's own schema, maintained here** — it began as a copy
  of `tson-cli`'s `diagnostics.tn` and has diverged on purpose (`UPSTREAM.md` #5): a CLI reports on files and a
  server reports on requests. `problem` follows **RFC 9457**; `diagnostic` stays close to what a TSON read
  produces, because that is what it reports.
- `TsonHttpSchemaSource` / `TsonSchemaFetchException` — fetching a schema named by an untrusted request
  body, under policy. Read its class notes before changing anything in it; every rule there is load-bearing.

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
4. **A 5xx body carries status and title and no detail.** An internal message can name a class, a path, an
   internal host or a query, and a client is not the audience. The exception goes to a `System.Logger` — which
   keeps this module dependency-free, since it may not take a logging dependency. A 4xx is the opposite: its
   detail and diagnostics are the entire point.

A handler that returns without answering is a bug in the handler, so it is a 500 rather than a silent 200.

**Concurrency is tested, not assumed.** Every adapter shares one `TsonHttpCodec` across request threads, and
every other test in this repo is single-threaded — so a codec wrong under concurrency would pass all of them.
`TsonHttpCodecConcurrencyTest`, `TsonHttpSchemaSourceConcurrencyTest` and each adapter's
`demo/OrderServerConcurrencyTest` close that gap, and each task checks its own result: the failure worth
looking for is a crossed or torn value, not a call that threw. Writing them is what found `UPSTREAM.md` #8.
Keep them green, and add to them rather than around them.

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
  `sketch/orders-api-3.tn` is a schema and why `api-1.tn` — which describes an API as *data* — cannot check
  anything it says.
- **The recurring `(schema identity, root type name)` pair is the data layer's workaround for this.**
  `describing(…)`, `readObjectAs(…)`, the `TSON-Schema` header plus a route-supplied type: two strings
  reassembled at every call site, because the thing they name cannot be referenced.

### Describing an API (`api-1.tn`, `TsonApi`)

An OpenAPI-shaped description of an HTTP API whose payloads are TSON — minus the part OpenAPI mostly is.
OpenAPI embeds a schema language because JSON has none; TSON already has published, identity-addressed schemas,
so an operation **references** one by `!!id` and names a root type in it.

**That pair is the contract unit, and it was not invented here.** `describing(schemaUri, rootTypeName)`,
`readObjectAs(schemaUri, typeName, class)`, the `TSON-Schema` header plus a route-supplied type — everything
handling a TSON payload independently needs both, for the same reason: a schema alone does not say which of its
types a document is, and a bound record writes no type-ref of its own. An API description is just *per
operation, which pair goes in and which pairs come out*.

**The description is checked, not just written.** `TsonApiConformanceTest` fetches it **from the running
server**, reads it as a TSON document governed by `api-1.tn`, and holds the server to it: every referenced
schema must be published, and every response's status and its body's own `!!schema`/type-ref must be what the
description declares. Verified to fail when the description lies. A description nothing executes is
documentation that quietly stops being true — the same lesson as the demo servers.

**Parameters are where TSON's document-orientation does not reach.** A URL segment cannot carry a record, so
`parameter.type` names a scalar type as `text` rather than being a full `(schema, type)` reference. Stating
that limit beats papering over it with expressiveness nothing can honour.

**Deliberately absent** from `api-1.tn`: security schemes, headers beyond parameters, callbacks, links,
examples, multi-media-type negotiation. Adding one is a new version under a new name (§10), never an edit.

### Business errors compose `problem`

A business error — the request was schema-valid and the domain still said no — is **written by the handler, not
thrown**. It composes `problem` (§5.8) so it carries RFC 9457's members and adds its own, which means the error
boundary cannot produce it: the boundary only knows how to render a `problem`, and a `sku_not_found` has a field
`problem` does not.

These types belong in the **application's** schema, importing `problem-2.tn` — `tson-http` owns the transport
envelope, the service owns "SKU not found". One rule that costs time otherwise:

- **Imports are transitive, but name what you use anyway.** A name reaches you through what you import, so
  `orders-errors-1.tn` would get `text` through `problem-2.tn` without saying so. Name `core.tn` too: a
  collision is judged by the *declaring schema's identity* now, not by how many routes reach it
  (`UPSTREAM.md` #11), so naming a shared dependency twice is redundant rather than an error.
- **`errors` stays data-level.** A business failure carries `errors: []` and its own fields. They never
  co-occur anyway — validation is a gate, so business logic is not reached on a document that failed it.

### Serving several schema versions

§10 makes a published schema immutable: a shape change is a new document under a new name (`order-1.tn`,
`order-2.tn`), so versions coexist rather than replace each other, and a server outliving one of its clients
serves both. `TsonSchemaVersions` is that, and `TsonDocumentPeek` is what lets it route.

**A `DataBindContext` per version, because binding is name-based.** `DataNameBinder.resolve(String)` is handed a
schema *type name* and nothing else — no schema, no version. Both versions declare `order`, so one binder cannot
map it to two classes. The failure is at least loud: *"the schema's root type `order` binds to OrderV1, which is
not assignable to the requested OrderV2"*. Tree mode has none of this difficulty; one `Tson` holds every version
happily, because no classes are involved. Reach for `TsonSchemaVersions` only in bind mode.

**Routing is a safety feature, not a convenience.** A codec built for v1 will read a v2 document and silently
drop what its class has no component for — `OrderV1[sku=A, quantity=1]`, no error, **no diagnostic even
collecting** (`UPSTREAM.md` #10). So `route` refuses a document naming a version this endpoint does not serve,
and refuses one naming none. Do not add a fallback that guesses; that is the failure it exists to prevent.
`defaultVersion` exists for an older unversioned client and is off by default for the same reason.

**Two ways to model the Java side**, both tested: a class per version, switching on `Routed.schemaId()`; or one
class with a field for everything any version has, nullable for what is not in all of them.

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
`TsonSchemaValidationException` = the author's document/schema is wrong per the spec;
`UnsupportedOperationException` = the library hasn't implemented that yet; `IllegalStateException` = an
internal invariant broke. Only the first is ever collected into a `Diagnostic`. The CLI rides its exit
codes on that split (1 = your document is bad, 70 = a fault in the library), and **the HTTP mapping is
the same split wearing status codes**: validation → 4xx, gap or internal fault → 5xx. A gap must never be
reported to a client as "your request was invalid".

`Diagnostic.Code` is the 4xx detail vocabulary: `FIELD_REQUIRED`, `FIELD_FIXED`, `TYPE_MISMATCH`,
`WRONG_ARITY`, `UNKNOWN_TYPE_REF`, `ATOM_CONSTRAINT_VIOLATION`, `UNRECOGNIZED_FIELD`, `DUPLICATE_MAP_KEY`,
`DUPLICATE_FIELD`, `SCHEMA_ERROR`, `UNKNOWN_TYPE`, `VALIDATION_ERROR`.

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
path, not a concurrent one. Treat this as an invariant to be pinned by a test, and see `UPSTREAM.md` #3 —
the upstream contract needs to be stated explicitly rather than inferred, as it is here.

## Traps — read before touching the code involved

Each cost a debugging cycle here and is pinned by a test.

- **`TsonHttpException.from`'s base-syntax branch is a net, not a path.** A document that will not parse is now
  reported through the receiver (`UPSTREAM.md` #6, fixed upstream), so a collecting read returns its
  diagnostics rather than throwing. `Diagnostic.ofBaseSyntaxError` stays in `from` because it classifies the
  three base-syntax exception types — two of which live in an unexported package, so no caller here could
  `catch` them — and **rethrows anything else**, which is what stops an unexpected fault becoming a false
  verdict about the request. Do not delete it for being unreachable.
- **`problem-2.tn`'s `diagnostic_code` is a hand-written copy of `Diagnostic.Code`.** Nothing but
  `TsonProblemSchemaTest` checks it is current, and an error body emitting a code its own schema rejects would
  not otherwise be caught, since no fixture produces a code that is new. The Java enum is the source of truth —
  never check this schema against tson-cli's, which would only prove they drifted together.
- **A `text` field accepts any token, including `42`, `true` and `2026-01-01`.** It rejects only what is
  not a token at all — an array, a record. Correct per spec, and it reliably reads as a bug: [TSON-DATA]
  §4 says base type resolution does not apply at a schema-typed position, and §7.1's "form is not meaning"
  makes a type contract operate on the token's *text*, not on how it was written. A handler that needs
  string-ness says so with a `pattern`, not by assuming `text` means it. Pinned by
  `TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
- **A bound class must be public.** tson-java declares no `opens` and binding only ever touches public
  constructors and methods, so a package-private record fails analysis with a bare `DataBindException:
  Failed to resolve` that names nothing useful.
- **Object binding needs a `DataNameBinder` on the `DataBindContext`.** The class passed to `readObject` is
  the expected *result*, not the mapping; without a binder the schema compiles and then throws
  `UnsupportedOperationException: no bound Java class for '<type>'` — which the status policy correctly
  reports as 501, so it looks like a library gap rather than missing configuration. Chain to
  `SchemaMetaNameBinder.INSTANCE` for everything you do not map yourself.
- **A JSON body names neither its schema nor its root type**, and cannot — directive syntax is not JSON. So the
  schema comes from the `TSON-Schema` header and the root type from the route, which means reading one is
  `readObjectAs`/`readTreeAs`, never the bare `read`. Same two-part requirement as `describing()`, same reason.
- **`TsonDocumentPeek` is a hand-rolled scanner, and must stay conservative.** There is no public header peek
  upstream (`UPSTREAM.md` #9) despite §7.1 designing for exactly this. Its governing rule: it may answer "I
  could not tell", but it must never answer with a schema the document does not name — a wrong answer routes a
  request to the wrong version. When changing it, add to `neverInventsASchemaForRubbish` first.
- **`describing()` needs a root type name as well as a schema URI, for an object.** A bound record writes no
  type-ref of its own, so `!!schema` alone yields a document whose reader cannot select a type. The tree form
  takes one argument, because a tree node already carries a type-ref. `TsonHttpCodec.write(value, schemaUri,
  rootTypeName)` and `writeTree(value, schemaUri)` mirror that asymmetry deliberately.
- **`prepareToWrite` is a warm-up, not a correctness measure** — it was one, before `UPSTREAM.md` #8 was fixed
  upstream. Keep calling it at startup to move first-write descriptor resolution off the request thread; do not
  treat it as load-bearing for concurrency any more.
- **A schema reference may not carry a port, userinfo or a fragment** (§2.2.1), so a schema origin cannot run
  on a non-default port. Use `mapHost`. See "Identity is not location" above — this is the trap that costs the
  most time, because the failure surfaces from the resolver rather than from the fetch.
- **Never `computeIfAbsent` on the schema cache.** It holds a `ConcurrentHashMap` bin lock for the whole of a
  network fetch, blocking every other thread whose key lands in that bin — and stalling a resize — for as long
  as the timeout allows. `TsonHttpSchemaSource` uses get-then-put; two threads racing one identity fetch it
  twice and store identical content, which costs a request and breaks nothing.

  *Not* because the loader is re-entrant: it isn't. It fetches a document, returns, and only then resolves and
  fetches its imports, so `fetch` is never called from inside `fetch` (measured — max depth 1, pinned by
  `TsonHttpSchemaSourceConcurrencyTest`). An earlier version of this note claimed otherwise.
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

**Use the `.tn` extension, not `.tn1`**, matching tson-java: `.tn1` is a stability claim §7.1 reserves for
a frozen "TSON version 1" that hasn't happened (tson-java's `SPEC-FEEDBACK.md` #20).

**Superseded schemas stay published.** §10 makes a published schema immutable, so `problem-1.tn` is still
served alongside `problem-2.tn` — a document that named the old one must go on resolving, even though nothing
new is written against it. `TsonProblemSchema.publishedSources()` returns the whole history, and the demos
publish all of it. When bumping a schema here, add the new version and keep serving the old, never edit.

**Project-owned schema `!!id`** follows tson-java's convention with this repo's own group:
`https://tson.io/2026/32/ltr8/http/<name>-<version>.tn` — `/2026/32` the spec revision, `ltr8` the
publishing org, `http` the subsystem. Per §10's immutability rule, a shape change bumps the version
under a **new name** (`problem-2.tn`), never an in-place edit.

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

## Demo servers

Each adapter has one, and they are the same server: same routes, same schema, same behaviour, so the same
`curl` commands work against all three. Starting one prints what to try.

```
./gradlew :tson-http-jdk:runDemo
./gradlew :tson-http-javalin:runDemo
./gradlew :tson-http-helidon:runDemo
./gradlew :tson-http-jdk:runDemo -Pport=9000
```

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
  `SPEC-FEEDBACK.md`. **The only place upstream changes are recorded** while that repo is hands-off.
- `sketch/` — an API description made of **types** rather than data about types. Three designs; the best needs
  only the **ordinary header** (`orders-api-3.tn`: FIXED fields carry method and path, a `choice` carries the
  responses, composition enforces the shape, every payload is a resolved `TypeRef`). The other two need a custom
  meta layer, and the `~operation` one is blocked on `UPSTREAM.md` #11 and on user meta-schema constructors not
  being applicable. Nothing here ships. `SketchTest` holds each to what `sketch/README.md` claims, so a fix
  upstream shows up as a failing test. **Read `sketch/README.md` before `api-1.tn`**, which is the shipping
  data-shaped version and knows what is wrong with it.
- `SCHEMA-HEADER.md` — the proposal for naming a governing schema in an HTTP header. A design document for the
  spec author, not a description of anything built.
