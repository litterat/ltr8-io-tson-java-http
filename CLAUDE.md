# CLAUDE.md

Orientation for Claude Code sessions in this repo: what the project is, the rules that apply to every change, and where
the detail lives. It describes the code **as it stands**, present tense; history is in git.

**This file is an index, and it stays short.** Design detail lives in `design/` (map below), in class Javadoc, and in
the `CLAUDE.md` inside each module directory, which loads when you touch that module. **Before working in an area, read
its `design/` note**: each opens with the invariants that are easy to break silently, then the why. Read the one or two
notes the task touches, not all of them. The code is the source of truth if a note has drifted.

**When you learn something, put it in the area's note, not here.** A paragraph added to this file is read by every
session on every task; one added to a note is read by the sessions that need it. This file grows only when a rule
applies to all work in the repo.

## Design notes (`design/`)

Each under ~20 KB and opening with its invariants. Pick by what you are about to touch.

| Working on | Read (`design/…`) |
|---|---|
| `TsonHttpCodec`, `TsonSchemaHeader`, `TsonSchemaCatalog`, peeks, `describing` | `codec.md` |
| An adapter's error boundary, `negotiate`, committed responses, the concurrency suites | `adapters.md` |
| `TsonHttpException.from`, a `Diagnostic.Code`, a problem `type`, name hygiene, limits | `status-classification.md` |
| What tson-java offers and its concurrency contract (`Tson`, `ProcessorConfig`, `SchemaAccess`) | `tson-api-surface.md` |
| A bound class, `TsonBindings`, `prepareToRead`, a 500 that should be a 400 or vice versa | `binding.md` |
| `acceptingJson`, `application/json` bodies, `respondDescribed`, `application/problem+json` | `json-encoding.md` |
| `TsonSchemaVersions`, `TSON-Accept-Schema`, `@Profile`, two versions of one schema | `schema-versions.md` |
| `allowHost`/`mapHost`, `SchemaSource.ofMap`, a `!!schema` URL in a request body | `schema-sources.md` |
| `meta-service.tn`, `io.ltr8.tson.http.api`, `TsonApiDescription`, `TsonApiCoverage`, business errors | `service-description.md` |
| A `.tn` file this project owns, a `!!id`, `problem.tn`, a revision bump, a field spelling | `schema-authoring.md` |
| `deployment.tn`, `TsonDeployment`, `/.well-known/tson-deployment`, `policy.tn` | `deployment-descriptor.md` |
| `OrderServer`, `demo/schemas/`, `ValidatorServer`, the throughput harnesses | `demos.md` |

Other root documents: `UPSTREAM.md` (changes wanted in tson-java and spec feedback staged for its `SPEC-FEEDBACK.md`;
holds only what is open), `SCHEMA-HEADER.md` (the design record behind [TSON-JSON] §3.5; cite §3.5 for the rules),
`experiments/README.md` (design explorations kept compiling), and the consumer-facing `README.md`, which `ReadmeTest`
executes against the real demo.

## Project

`tson-java-http` demonstrates integrating **TSON** (Typed Schema Object Notation) into a Java HTTP server: reading
`application/tson` request bodies, writing `application/tson` responses, turning schema violations into HTTP error
responses, and serving and fetching schema documents over HTTP. It is a **consumer** of the TSON library, not part of
it. The library is the sibling repo [ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java), checked out at
`../ltr8-io-tson-java` and consumed as a Gradle included build (below). Destination remote: `https://github.com/litterat/`.

**Built against Revision 38**, published at tson.io and on the sibling's `main`, which is what CI checks out. Every
identity in this repo carries `/2026/38/` and `tson.version` names `0.38.0-SNAPSHOT`. The revision series changes
without compatibility guarantees, so a `git pull` of the sibling can move the whole identity space;
`design/schema-authoring.md` says what a bump touches.

**Status.** All four modules are built and tested, each adapter with a runnable demo server and a concurrency suite
driving it under load. Responses are self-describing in both directions, every adapter proves the serve-then-fetch loop,
and a service publishes a description of itself (`meta-service.tn`) from which it derives what to publish and warm.
What remains is polish and the open questions in `UPSTREAM.md`. Still not built: a route table registered from the
description rather than claimed against it.

**Hard constraints:**
- Java 25 only (matches tson-java).
- `tson-http` and `tson-http-jdk` take **no external runtime dependencies**. tson-java's own modules (`io.ltr8:tson`,
  `io.ltr8:tson-json`) are not external. An adapter's framework dependency stops at that adapter and never leaks back
  into `tson-http`.
- Every module has a real `module-info.java`; module names mirror each module's root exported package. All three
  servers are genuine JPMS named modules, so nothing falls back to the classpath.

## Modules and dependency direction

Package group `io.ltr8`, as in tson-java. Adapters depend on `tson-http`; `tson-http` names no adapter type, and the
adapters never depend on each other. Each module's own `CLAUDE.md` says what it holds and what bites there.

| Module | Holds |
|---|---|
| `tson-http` (`io.ltr8.tson.http`) | Server-agnostic core: media types, `TsonHttpCodec`, `TsonHttpException` (the whole status policy), `problem.tn`, the two schema headers, `TsonSchemaCatalog`, `TsonSchemaVersions`, `TsonDeployment`, and the service description (`meta-service.tn`, `io.ltr8.tson.http.api`) |
| `tson-http-jdk` (`io.ltr8.tson.http.jdk`) | Adapter for `com.sun.net.httpserver`; zero dependencies; the reference the other two are read against |
| `tson-http-javalin` (`io.ltr8.tson.http.javalin`) | Adapter for Javalin 6.7.0 (Jetty 11, `jakarta.*`) |
| `tson-http-helidon` (`io.ltr8.tson.http.helidon`) | Adapter for Helidon 4.2.2 SE, plus `TsonMediaSupport` over Helidon's `MediaSupport` SPI |

An adapter is a translation layer with no TSON knowledge of its own. It owns the error boundary and nothing else:
`design/adapters.md`.

## The startup rule

tson-java is not thread-safe as a whole; its post-compile read path is. So: **build the `Tson` and resolve and compile
every schema during startup, on one thread. Only then share it across request threads, and only for reads.** Never
resolve a schema from a request handler. `TsonHttpCodec`'s Javadoc and `TsonHttpCodecConcurrencyTest` state and
measure the same rule; `design/tson-api-surface.md` has the contract upstream gives.

## Consuming tson-java

tson-java publishes to **mavenLocal only**, so a checkout of the sibling is required either way.

- **Included build, the default.** Gradle substitutes `io.ltr8:tson` with the sibling's own project, so an edit or a
  `git pull` there is picked up by a plain `./gradlew build`. A broken sibling breaks this build, and compile output
  naming files under `../ltr8-io-tson-java/…` is expected. Path is `tson.path`, overridable with `-Ptson.path=…`.
- **Published artifacts, `-Ptson.published=true`.** Resolves `io.ltr8:tson:${tson.version}` from mavenLocal after
  `./gradlew publishToMavenLocal` in the sibling. The only path that exercises the published POM and jars; CI runs it too.

**Do not modify `../ltr8-io-tson-java`.** Changes wanted there go in `UPSTREAM.md` and land in that repo only on the
user's say-so. Pulling it is fine when asked. Read its `CLAUDE.md` and `design/` before working on the codec.

## Conventions

Matching tson-java's conventions is what keeps the two repos readable as one body of work.

- **Javadoc and notes document the current contract only.** No dates, no "renamed from", no "used to". State the
  invariant and its rationale once. When you edit a class, clean its Javadoc in the same edit, including narrative you
  did not write. Keep the area's `design/` note current in the same session as the change.
- **Don't state a count of anything** in this file, the notes or the README: counts go stale within a day and nothing
  maintains them (`ReadmeTest` enforces it for the README). Say what is covered, not how much.
- **`Tson` is a prefix, never an infix** (`TsonHttpCodec`, not `HttpTsonCodec`), and only on types a consumer of this
  library names in their own code; internal machinery is bare.
- **Wrap comments and code to 125 characters.** Characters, not bytes: `scripts/check-line-length.sh`, which the
  pre-commit hook runs over the lines a change adds.
- **Status policy lives in `TsonHttpException.from`** and nowhere else. An adapter never re-decides a status, and never
  filters a problem body: that rule is content policy and it lives in `tson-http`, once.
- **Test the adapters over real HTTP**: start the server on an ephemeral port and drive it with `java.net.http.HttpClient`.
  Only a real request exercises each framework's own body and negotiation handling.
- **The three adapter suites are near-copies on purpose.** A case added to `TsonJdkAdapterTest`, `TsonJavalinAdapterTest`
  or `TsonHelidonAdapterTest` is added to all three, and the same for the three schema-handler suites.
- **Concurrency is tested, not assumed.** `TsonHttpCodecConcurrencyTest` and each adapter's `OrderServerConcurrencyTest`
  are the only tests that would catch a codec wrong under concurrency. Add to them rather than around them.
- **Spec feedback is a deliverable.** Ambiguity, inconsistency or error in the spec is raised in conversation and staged
  in `UPSTREAM.md` under "Spec feedback to file", never silently interpreted. **Cite the spec section, not the register
  number**: a `SPEC-FEEDBACK.md #N` is live only while the entry is open.
- **`UpstreamGapsTest` pins every library gap and constraint this project depends on.** A fixed gap flips its assertion
  rather than losing it. Pin the gap, not the way it is delivered.
- **Project-owned schema `!!id`:** `https://tson.io/2026/38/io/ltr8/http/<name>.tn`, no version in the name; `.tn`, never
  `.tn1`. Nothing is published yet, so a schema is edited in place.

## Traps

One line each; the note carries the why. Read the note before touching the code involved.

- Ask the `Diagnostic.Code`, never the exception type: a gap arrives as a `ReadException` too. (`status-classification.md`)
- A code `verdict()` calls true is never a 5xx; a refusal (`isNameRefusal()`, `LIMIT_EXCEEDED`) is a 400 or 413, not a
  5xx; `NOT_IMPLEMENTED` is 501 and `BIND_MISMATCH` 500. The status is a scan in rank order. (`status-classification.md`)
- One fetch status table, reached by `Diagnostic.Code.of(reason)`; never a second switch over `Reason`. (same)
- `TsonHttpException.from`'s base-syntax branch looks unreachable and is a net. Do not delete it. (`codec.md`)
- Peek a body with `TsonHttpCodec.begin` and pass the peek on; test with a stream whose `markSupported()` is false, since
  a `ByteArrayInputStream` hides a peek that ate the body. (`codec.md`)
- Upstream's `readAs` silently lets a stated schema override a body's own; the codec checks agreement. Never bypass it
  on a body a client wrote. (`codec.md`)
- `describing()` needs a root type name for an object; the tree form takes the URI alone. (`codec.md`)
- A `com.sun.net.httpserver` with no executor is serial; use a virtual-thread executor, never a pool. (`demos.md`)
- Never overwrite a committed response; a handler that returns without answering is a 500. (`adapters.md`)
- A bound class is public, bound through `TsonBindings.of`, strict with no lenient mode, and `prepareToRead` at startup
  is what makes a mismatch a startup failure rather than a 500. (`binding.md`)
- A type nothing binds is a 500, not a 501; a compact constructor stricter than its schema is a 500, not a 400. (same)
- A URI atom reads to `io.ltr8.net.Iri`, not `java.net.URI`. (`binding.md`)
- A JSON body is never peeked and never read into a `TsonValue`; its schema comes from `TSON-Schema` and its root type
  from the route. JSON `null` is `_` and is refused at `a?: T`. (`json-encoding.md`)
- `route` refuses a body naming a version this endpoint does not serve, and one naming none. No guessing fallback.
  Multiple constructors do not select a version. (`schema-versions.md`)
- A schema reference carries no port; `mapHost` is the only way to a non-default port, and the failure surfaces from the
  resolver, far from the fetch. (`schema-sources.md`)
- `SchemaSource.ofMap`, never `map::get`; never `computeIfAbsent` on a schema cache; policy on every reference, cached or
  not. (`schema-sources.md`)
- `name?: T` is optional; `name: T?` is required and admits `_`. A `text` field accepts any token. A `pattern` is
  I-Regexp, whole-token, and `^`/`$` are literals. Core no longer declares `identifier` or `non_empty_text`.
  (`schema-authoring.md`)
- `problem.tn`'s `diagnostic_code` is a hand-written copy of `Diagnostic.Code`; `TsonProblemSchemaTest` is the only check.
  (`schema-authoring.md`)
- An entry applying a namespace type is a value, never a type; `interface`, `api` and `resource` each have exactly one
  bare required field, and a second one breaks every document written positionally. (`service-description.md`)
- An entry has two annotation positions that land in different places; reading only one looks like a library bug. (same)
- `TsonHandler.install` is not optional with Helidon's `TsonMediaSupport`. (`tson-http-helidon/CLAUDE.md`)
- A descriptor states its policy whole; an absent policy is not a permissive one. (`deployment-descriptor.md`)

## Build and test

No system Gradle; always the wrapper (Gradle 9.4.1, Java 25 toolchain). `build` also builds the included tson-java.

```
./gradlew build                        # also builds the included tson-java build
./gradlew build -Ptson.published=true  # against tson-java's published artifacts instead
./gradlew :tson-http:test --tests "io.ltr8.tson.http.TsonHttpCodecTest"
./gradlew :tson-http-helidon:test --tests "io.ltr8.tson.http.helidon.demo.OrderServerTest"
./gradlew javadoc                      # NOT reached by `build`; its own task tree, as in tson-java
./gradlew :tson-http-jdk:runDemo       # also :tson-http-javalin:runDemo, :tson-http-helidon:runDemo, -Pport=9000
./gradlew :tson-http-jdk:runValidator  # then open http://localhost:8080/
scripts/check-line-length.sh           # lines over 125 characters this working tree adds
```

Demos live in a `demo` source set: compiled by `build`, absent from the jar, driven by each adapter's `OrderServerTest`.
`experiments/` probes join `tson-http`'s test source set; one failing after a library change is information, not a
regression. `scratchpad/` holds reproducers written to drop into the sibling's test tree; never copy one there and leave
it.
