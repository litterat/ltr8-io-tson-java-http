# Upstream changes wanted in `ltr8-io-tson-java`

That repo is **hands-off** for now. Anything this project would like changed there is written up here
first, and only landed on the user's say-so. Each item states what this project hits, why the workaround
is unsatisfying, and what the change would be.

Ordered by how much they block or shape work here.

---

## 1. No `maven-publish` — nothing to depend on

**Hit:** tson-java declares no publishing of any kind (no `maven-publish` plugin, no publications,
anywhere in its `*.gradle.kts`). There is no artifact for this repo to depend on.

**Workaround in place:** a Gradle **included build** (`settings.gradle.kts`), substituting `io.ltr8:tson`
with the sibling checkout's `:tson` project. This works today and has a real upside during co-development —
an edit upstream is picked up with no publish step.

**Why it is still unsatisfying:** it hard-requires the sibling checkout at a known path, so CI has to
check out two repos, and a broken sibling breaks this build. More to the point, it does not survive this
repo being published: an external reader who clones `ltr8-io-tson-java-http` alone cannot build it.

**Change:** add `maven-publish` to tson-java's `subprojects` block with a `mavenJava` publication
(`from(components["java"])`, plus sources and javadoc jars), so `./gradlew publishToMavenLocal` produces
`io.ltr8:tson:0.1.0-SNAPSHOT`. This repo would then take a normal `mavenLocal()` dependency and keep the
included build as an opt-in (`-Ptson.composite=true`) for co-development.

**Blast radius:** additive; no existing behaviour changes.

---

## 2. Writers have no stream sink — every response body is materialised in memory

**Hit:** `TsonObjectWriter.toTson(Object)` and `TsonTreeWriter.toTson(TsonValue)` return a `String`, and
that is the entire write surface. An HTTP response must therefore build the whole document as a `String`,
then encode it to UTF-8, then write it — two full copies of the body in memory before the first byte
goes out, and no way to stream a large or open-ended response.

The asymmetry is the tell: the **read** side already does this right. `TsonTreeReader` and
`TsonObjectReader` both take `InputStream` on `read`/`readWithoutSchema`/`readAs`, so a request body
streams in. Only the write side forces materialisation.

**Change:** add `void write(Object value, OutputStream out)` / `write(TsonValue, OutputStream)` — or an
`Appendable` overload, whichever fits `TsonDataEmitter`'s internals — alongside the existing `toTson`.
`toTson` stays as the convenience wrapper over it.

**Blast radius:** additive. Worth checking against `TsonDataEmitter` ("not thread-safe; single-use") and
against the atom-refinement round-trip in `DefinitionResolver`, which is the one existing internal caller
of `TsonObjectWriter` and must keep behaving identically.

**Priority:** highest of the API items. Every adapter in this repo pays for it on every response.

---

## 3. Thread-safety contract is inferred, not stated

**Hit:** a server shares one `Tson` across request threads, so "what may be used concurrently" is a
correctness question here, not a nicety. Today it has to be reverse-engineered from scattered class
Javadoc:

- `TsonCompiledMetaRegistry` — "Not thread-safe: `register`/`get` are `synchronized`, but `loadMeta` …
  are not."
- `Lexer` — "Not thread-safe; a `Lexer` instance is single-use over one source stream."
- `TsonDataEmitter` — "Not thread-safe; single-use, like `Lexer`."
- `CompiledReaders` — its binding is `volatile` because "the write in `bind` happens on the compiling
  thread and the reads happen on whatever threads later perform reads."

That last one shows the intended shape — compile once, read from many threads — but `Tson`,
`TsonTreeReader`, `TsonObjectReader` and `TsonCompiledSchemaRegistry` say nothing about it either way,
and those are exactly the types a server holds in a field.

**Change:** state the contract on the front-door types. Something as blunt as: *a `Tson` and the readers
obtained from it are safe for concurrent reads once every schema has been resolved and compiled;
`resolve` and registry mutation are not concurrent-safe and belong in single-threaded startup.* Then pin
it with a test that hammers one compiled schema from many threads.

**Blast radius:** documentation plus a test, unless the test finds the contract isn't actually met — in
which case that is the more valuable outcome.

---

## 4. No HTTP-backed `TsonSchemaSource` (already upstream's own backlog item)

**Hit:** `TsonConfig.schemaSource(…)` takes a `TsonSchemaSource`, and every example supplies a lambda
returning a literal string. `Tson`'s Javadoc is explicit that only a schema governed by
meta-kernel/`meta.tn`/`core.tn` is supported, and names a real disk/HTTP-backed source as future work;
tson-java's `BACKLOG.md:370` already tracks it — "A real disk/HTTP-backed `TsonSchemaSource` with
whitelist/blacklist policy".

**Position:** this repo is going to build one regardless, because a `!!schema:"https://…"` directive
arriving in a request body is the whole point of the HTTP integration. Building it here first is the
right order — an HTTP server is the environment that actually exercises origin policy, timeouts, size
caps, redirect handling, and caching.

**Change:** none requested yet. When the implementation here settles, propose lifting the
policy-and-cache part upstream as the backlog item's implementation, leaving the servlet-ish parts
behind. Flagged now so the design here is built to be liftable — no adapter types in its signatures.

**Security note, since it is easy to get wrong:** the URL comes from an untrusted request body. The
source must be allow-list gated (origins), size- and time-capped, and must not follow redirects off the
allow-list. A naive fetcher is an SSRF primitive.

---

## 5. `Diagnostic` is shaped for a CLI, not for a response body

**Hit:** `Diagnostic` carries `path`, `schemaPointer`, `schemaId`, `code`, and its factories are
CLI-flavoured (`ofBaseSyntaxError`, `ofSchemaSyntaxError`, `ofSchemaError`). The content is right for an
HTTP 4xx body; what is missing is a stable, documented serialisation. This repo needs one to define its
error schema (`https://tson.io/2026/32/ltr8/http/problem-1.tn`), and inventing a private mapping means
the CLI and the server describe the same failure two different ways.

**Change:** a schema for `Diagnostic` in tson-java, owned there, with the CLI's own rendering and this
repo's error body both derived from it. Lower priority than 1–3: this repo can define its problem schema
first and propose promoting it once it has proven out.

**Note:** `Diagnostic.Code` itself needs no change — the twelve codes map cleanly onto 4xx detail. It is
only the wire form that is missing.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. Nothing yet — the
media-type work in §7.1 has not started. Expected to produce candidates, since §7.1's HTTP prose (the
`version` parameter, `charset` handling, content sniffing from the header) has had no implementation
exercise it, and this project is the first.
