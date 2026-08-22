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

## 5. The diagnostic wire schema exists, but only `tson-cli` can reach it

**Hit:** building this project's error body turned up `tson-cli/src/main/resources/diagnostics.tn`
(`!!id` `.../ltr8/cli/diagnostics-6.tn`), which already declares `diagnostic_code` and `diagnostic`
exactly as an HTTP error body needs them, alongside `CliDiagnostic` — the `Optional`-narrowed,
`@Field`-annotated DTO that binds to it — and `DiagnosticsSchema`, which compiles it in bind mode.

So the wire form is not missing. It is unreachable: `tson-cli` is an application module that exports
nothing, publishes no schema, and keeps all three types package-private.

**Workaround in place:** `tson-http` copies `diagnostic_code` and `diagnostic` into its own
`problem-1.tn`, field for field, and `TsonProblemDiagnostic` reproduces `CliDiagnostic` line for line
including the `absentIfEmpty` narrowing and the `line:column:byteOffset` position rendering. Both
copies say so in their own doc comments.

**Why it is unsatisfying:** two copies of one wire contract, kept identical by hand. The moment the CLI
adds a `Diagnostic.Code` member — as `diagnostics-6.tn`'s own `@doc` records happening twice already —
the server and the CLI describe the same failure differently, and nothing fails to warn anyone.

**Change:** lift the shared half — `diagnostic_code`, `diagnostic`, `CliDiagnostic` (renamed) and the
`DiagnosticsSchema` compile — into a module a consumer can depend on, with the CLI's own
`validation_report`/`file_report`/`validation_run` staying behind. `tson-schema` is the natural home:
`Diagnostic` itself lives in `tson-compiler`, and its wire form is a value model, which is what that
module is for. Then `problem-1.tn` imports it by `!!id` instead of copying it.

**Note:** `Diagnostic.Code` needs no change — the twelve codes map cleanly onto 4xx detail. It is only
the sharing that is missing.

---

## 6. A collecting receiver does not collect base-syntax failures

**Hit:** `treeReader().withDiagnostics(collector).read(body)` still throws for a document that does not
lex or parse — the collector is not consulted, and `TsonParseException` reaches the caller. This looked
like a bug in the codec until `Tson.validate`'s own body showed the intended handling: catch, then run
`Diagnostic.ofBaseSyntaxError(e)`, which classifies the three base-syntax exception types and rethrows
anything else.

It is discoverable only by reading `validate`'s implementation. Neither `withDiagnostics` nor `read`
mentions that a receiver does not see these, and `ofBaseSyntaxError`'s own Javadoc explains why it lives
on `Diagnostic` without saying that every non-`validate` caller needs it. Two of the three exception
types are in the unexported `lexer` package, so a caller cannot even write the `catch` without it.

`TsonHttpCodec` does the same thing `validate` does, in `TsonHttpException.from`.

**Change:** state it on `withDiagnostics` — a receiver sees value-level problems, and a base-syntax
failure throws — and point at `ofBaseSyntaxError` as the classifier for it. Documentation only.

**Alternative worth considering, but not asked for:** route base-syntax failures through the receiver
too, so a collecting read never throws for a bad document. That is a behaviour change with real
consequences (a caller relying on the throw), and the current split is defensible: nothing can continue
past a document that will not parse, so there is no "collect and carry on" to offer.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off.

**Nothing to file yet.** §7.1's media-type prose is now implemented (`TsonMediaType`, `TsonAcceptHeader`)
and it held up: `application/tson`, the optional `version=1` parameter, and the UTF-8 fix are each stated
once and unambiguously, and nothing in them needed an interpretation to be chosen.

One near-miss worth recording so it is not re-investigated. A `text`-typed field accepts `42`, `true` and
`2026-01-01` — any token — and rejects only a non-token such as an array or a record. That reads as
under-enforcement until two sections settle it: §4 says base type resolution does not apply at a
schema-typed position, and §7.1's "form is not meaning" makes a type contract operate on the token's
text rather than on how it was written. The implementation is right and the spec is clear; it is the
combination that is easy to get wrong. Pinned by
`TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
