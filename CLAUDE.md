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

**Status.** `tson-http` is built and tested (55 tests): media type and `Accept` negotiation, the read/write
codec, the status policy, the `problem-1.tn` error body, and the HTTP-backed `TsonSchemaSource`. Still to
build there: schema serving. The three adapters are empty — build files and nothing else. Treat their
descriptions below as the design being built to, not as code you can go read.

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
  - **Schema serving** — exposing the registry's own schemas so those URLs actually resolve. Only
    `TsonProblemSchema.source()` exists so far, which is the piece that makes the `!!id` in an error body
    resolvable. **Not built yet.**
- **`tson-http-jdk`** (`io.ltr8.tson.http.jdk`) — adapter for `com.sun.net.httpserver` (JDK module
  `jdk.httpserver`). Zero external dependencies, so it is both the "no framework needed" demo and the
  reference the other two adapters are read against — when an adapter's behaviour looks odd, diff it
  against this one.
- **`tson-http-javalin`** (`io.ltr8.tson.http.javalin`) — adapter for Javalin 6.7.0 (Jetty 11,
  `jakarta.*` namespace). Javalin's integration seam is its `JsonMapper`-style plugin/handler surface.
- **`tson-http-helidon`** (`io.ltr8.tson.http.helidon`) — adapter for Helidon 4.2.2 SE. Helidon's
  integration seam is the **`MediaSupport` SPI** (`io.helidon.http.media`), which is why that artifact
  is a direct dependency: register TSON as a media type once and every route reads/writes it, rather
  than hand-coding entity handling per route.

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
- `TsonProblem` / `TsonProblemDiagnostic` / `TsonProblemSchema` / `problem-1.tn` — the error body, its
  schema, and the reader that proves the two agree.
- `TsonHttpSchemaSource` / `TsonSchemaFetchException` — fetching a schema named by an untrusted request
  body, under policy. Read its class notes before changing anything in it; every rule there is load-bearing.

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

- **A collecting receiver does not collect base-syntax failures.** `withDiagnostics(collector)` catches
  value-level problems; a document that does not lex or parse still *throws*, and two of the three
  exception types live in `tson-compiler`'s unexported `lexer` package, so you cannot write the `catch`.
  `Diagnostic.ofBaseSyntaxError(e)` is the classifier — it handles those three and rethrows anything else,
  which is also what stops an unexpected fault becoming a false verdict about the request. This is why
  `TsonHttpException.from`'s `default` branch is not a fallthrough. `UPSTREAM.md` #6.
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
- **A schema reference may not carry a port, userinfo or a fragment** (§2.2.1), so a schema origin cannot run
  on a non-default port. Use `mapHost`. See "Identity is not location" above — this is the trap that costs the
  most time, because the failure surfaces from the resolver rather than from the fetch.
- **Never `computeIfAbsent` on the schema cache.** Fetching a schema resolves its transitive
  `!!import`/`!!meta`, each of which re-enters `fetch`, and a recursive `computeIfAbsent` on one
  `ConcurrentHashMap` deadlocks or throws. `TsonHttpSchemaSource` uses get-then-put deliberately; two threads
  racing one identity fetch it twice and store identical content, which costs a request and breaks nothing.
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

## Build and test

No system Gradle — always use the wrapper (Gradle 9.4.1, Java 25 toolchain):

```
./gradlew build                       # also builds the included tson-java build
./gradlew build -Ptson.published=true # against tson-java's published artifacts instead
./gradlew test
./gradlew :tson-http:test --tests "io.ltr8.tson.http.TsonHttpCodecTest"
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
