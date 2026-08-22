# Upstream changes wanted in `ltr8-io-tson-java`

That repo is **hands-off** for now. Anything this project would like changed there is written up here
first, and only landed on the user's say-so. Each item states what this project hits, why the workaround
is unsatisfying, and what the change would be.

Ordered by how much they block or shape work here. **Done** items stay, with what actually landed, so the
constraint they used to impose is not reintroduced by someone reading around them.

---

## 1. ~~No `maven-publish`~~ — DONE (`e76eb3b`)

Every module now applies `maven-publish` with a `mavenJava` publication, sources and javadoc jars, and a POM
carrying name, description, url and licence. `./gradlew publishToMavenLocal` puts the whole set in `~/.m2`.

**Deliberately no remote repository** — that is packaging, not release. So a checkout of the sibling is still
needed either way, and this project keeps both consumption paths:

- **Included build, the default.** A `git pull` in the sibling is picked up by a plain `./gradlew build` with
  no publish step. During co-development that matters: the sibling moves often, and a stale `~/.m2` would
  silently give this build the old behaviour with nothing to warn anyone.
- **Published artifacts, `-Ptson.published=true`.** Resolves `io.ltr8:tson:0.1.0-SNAPSHOT` from mavenLocal.
  Verified working, with the transitive set arriving correctly from the POM.

CI runs both, because the included build substitutes projects and never touches a jar — so it proves nothing
about what tson-java actually publishes. The second job does: a broken POM, a missing transitive dependency
or a bad module descriptor fails there rather than in whatever project depends on them next.

---

## 2. ~~Writers have no stream sink~~ — DONE (`60cddae`)

`TsonObjectWriter` and `TsonTreeWriter` both gained `write(value, OutputStream)` and
`write(value, Appendable)`; `toTson` is now that method over a `StringBuilder`. The stream is flushed and not
closed, which is the right ownership for a response body, and the encoder does its own buffering.

`TsonHttpCodec` uses it:

- **Ordinary responses stream** — `writeTo`/`writeTreeTo` hand the response stream straight to the writer, so
  a large or open-ended document never exists as a `String`. The cost is no `Content-Length`; the buffering
  `write`/`writeTree` remain for a caller that wants it.
- **Error bodies stay buffered.** `writeProblem` is deliberately not streamed: a failure part-way through
  leaves a client holding a truncated problem on a response whose status is already sent, which is worse than
  the failure being reported. A problem is small, so there is nothing to gain.

Pinned by `TsonHttpCodecTest.streamingAndBufferingProduceTheSameBytes` — which one an adapter happens to call
must not be observable to a client — and by a test that the writer flushes a short document and leaves the
stream open.

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

**Update: that is what happened.** Writing the test found #8, a real race in `DataBindContext`. This project
now carries `TsonHttpCodecConcurrencyTest`, `TsonHttpSchemaSourceConcurrencyTest` and an
`OrderServerConcurrencyTest` per adapter, all green — so the contract as stated in this project's `CLAUDE.md`
(resolve and compile at startup, then share for reads) is measured rather than assumed, for everything except
the first write of a class. Those tests are the obvious starting point for the upstream ones.

---

## 4. No HTTP-backed `TsonSchemaSource` — now built here

**Hit:** `TsonConfig.schemaSource(…)` takes a `TsonSchemaSource`, and every example supplies a lambda
returning a literal string. `Tson`'s Javadoc names a real disk/HTTP-backed source as future work, and
`BACKLOG.md:370` tracks it — "A real disk/HTTP-backed `TsonSchemaSource` with whitelist/blacklist policy".

**Status: implemented here** as `TsonHttpSchemaSource`, and built to be liftable — no adapter types in its
signatures, and its only tson-java dependencies are `TsonSchemaSource` itself and the spec.

What it does, and what it deliberately leaves to the loader:

- **Host allow-list, deny by default**, matched exactly — no suffix or wildcard matching, since a suffix
  test for `.example.com` also matches `evil-example.com`.
- **Host→location mapping**, which is what makes a non-default port reachable at all (see below).
- **No redirects followed** — a redirect is the allow-list's exit door.
- **Refuses a reference that is not a legal identity** — port, userinfo or fragment (§2.2.1) — early and
  with a message naming the rule, rather than letting it surface from the resolver.
- **Caps on size and time**, size enforced against bytes delivered rather than `Content-Length`.
- **Optionally requires a `?sha256=` pin**, which is the one control the loader cannot express: it verifies
  a pin that is present, but has no way to insist on one.
- **Does not verify the pin, and does not cross-check the fetched `!!id`** — the loader already does both,
  after `fetch` returns. Repeating either would be a second implementation to drift from the real one.

**What the design turned on.** §2.2.1 separates identity from storage location: identity is lowercase host
plus path, the scheme is "a transport hint, not part of the name", and a consumer "MAY fetch by whichever
scheme its policy allows". A first cut here treated the reference as a fetch URL and allow-listed
scheme+host+port; that is wrong, and the rule that exposes it is §2.2.1's "no port (default or otherwise)"
— an identity cannot carry one, so an allow-list keyed on origin can never match anything real, and a
schema host on a non-default port is unreachable without an explicit mapping.

**Change requested: none yet.** Propose lifting the policy-and-cache part upstream once it has run against
the three adapters. Flagged now so it stays liftable.

**Security note, since it is easy to get wrong:** the reference comes from an untrusted request body. A
naive fetcher is an SSRF primitive.

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

## 7. The readers honour `!!schema`; the writers cannot produce it

**Hit:** deciding whether an HTTP request should be able to name its schema in a header turned up that a
response cannot name one at all. `TsonObjectWriter` and `TsonTreeWriter` emit no header directives, and the
gap is not at the facade — `TsonDataEmitter`'s whole surface is `beginRecord`/`field`/`beginArray`/
`annotation`/`typeRef`/the value methods. There is no `!!id`, no `!!schema`, no directive method of any kind.
`TsonValue` carries no schema reference either, so a tree that was read from a self-describing document has
already lost the fact by the time anything could write it back.

**So the round trip is asymmetric.** `treeReader().read(doc)` resolves the document's own `!!schema`,
validates against it and returns the value — and nothing in the library can write that value back out in the
form it arrived in. A document this library reads, it cannot reproduce.

**Why it matters here.** A TSON response body is therefore never self-describing: a client that receives one
cannot tell what governed it without being told out of band. That is what
`TsonHttpCodecTest.whatItWritesItCanReadBack` is really showing — it has to pass the schema and root type back
in explicitly via `readObjectAs`, because the bytes do not say. It also removes the honest answer to the
request-side question: "put it in the body, that is what the directive is for" is only available in one
direction.

**Change:** a way to emit a document header — at minimum `!!schema`, ideally `!!id` too. Shapes worth
considering, cheapest first:

- A `TsonDataEmitter` directive method, and an opt-in on the writers (`objectWriter().describing(schemaUri)`),
  leaving the default output exactly as it is today.
- The writer deriving it from the compiled schema a bind-mode registry already holds, so a caller writing a
  type the schema governs gets a self-describing document without naming the URI twice.

**Blast radius:** additive at the emitter. The writer-level opt-in matters more than the mechanism: emitting a
directive by default would change every existing document this library produces, including `tson validate
--output tson`, so the current output has to stay reachable.

**Priority:** high. It is the difference between TSON over HTTP being self-describing in both directions and
being self-describing only inbound.

---

## 8. `DataBindContext.getDescriptor` races on the first write of a class

**Hit:** a concurrency test over one shared `TsonHttpCodec` — the object all three adapters hold across their
request threads — failed on 31 of 32 threads with
`TsonWriteException: cannot write class … : Class already registered: …`.

**The mechanism**, in `tson-bind/src/main/java/io/ltr8/bind/DataBindContext.java`:

```java
DataClass descriptor = descriptors.get(parameterizedType);   // a ConcurrentHashMap
if (descriptor == null) {
    descriptor = dataClassResolver.resolve(this, targetClass, parameterizedType);
    register(parameterizedType, descriptor);                 // throws if the key is already present
}
```

The map is concurrent; the compound operation is not. Two threads resolving a given type for the first time
both see `null`, both resolve, and both call `register` — which is written to reject a duplicate rather than
tolerate an identical one, so the loser throws.

**Why it survived every other test.** The window is the *first* write of each class and closes permanently
after it, so anything single-threaded, and anything that happens to read before it writes, never sees it. Reads
are unaffected entirely: a compiled schema binds its classes when it compiles. It is specifically the lazy
write-side resolution that races — which is why a server can pass a full test suite and then fail under load,
on the error path, while reporting some other failure.

**Suggested fix**, one line and behaviour-preserving:

```java
return descriptors.computeIfAbsent(parameterizedType, key -> { ... resolve ... });
```

or leave the structure alone and make `register` idempotent for an equal descriptor. `computeIfAbsent` is safe
here because resolution does not re-enter the map for the same key; if that is not certain, the
tolerant-`register` variant has no such requirement.

**Workaround in place:** `TsonHttpCodec` resolves its own wire types in its constructor — an error body must
not be the thing that meets the race — and exposes `prepareToWrite(Class...)` for an application to do the same
for its types at startup. Both become unnecessary once this is fixed, and the tests that call it say so.

**Priority: highest of the open items.** It is a real fault reachable from correct usage, not a documentation
gap, and its symptom is worst exactly when a server is busiest.

**Related:** #3 asks for the thread-safety contract to be *stated*. This is the first place it is measurably
not met, so the two want fixing together — a stated contract is worth much more once it is true.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off.

### To file: how a schema is named for a document that cannot carry `!!schema` (§6, §7.1)

**Section:** [TSON-DATA] §6 (JSON compatibility) and §7.1 (Encoding, Normalization, and Media Type).

**The gap.** §6 makes every valid JSON document a valid TSON document, and the format's stated target use is
validating generated structured output against a schema. But `!!schema` is TSON directive syntax, and a JSON
document cannot carry one — so for the entire JSON-compatible surface there is no in-band way to say which
schema governs the document. The spec does not say what the out-of-band channel is.

This is not purely an application question, because §7.1 already legislates for HTTP: it defines
`application/tson`, notes the media type is intended for IANA registration, and specifies
`application/tson; version=1` for when "disambiguation is needed in HTTP contexts". Having gone that far, it
stops exactly before the parameter that is actually needed in practice.

**What an implementation must pick something for.** A server validating a posted JSON or TSON body has to
learn the schema from somewhere. Every implementation will invent a channel, and they will not agree —
`Content-Type: …; schema=…`, `Link: <…>; rel="describedby"`, a bespoke `Schema:` header, or a
route-configured constant are all reasonable and mutually incompatible readings.

**The interpretation this project chose**, pending a spec answer: the body's `!!schema` remains primary and
authoritative; a `schema` media-type parameter on `Content-Type` is accepted where the body carries no
directive; and where both are present and their canonical identities (§2.2.1) differ, the request is rejected
rather than resolved by precedence.

**Suggested resolution.** Define a `schema` media-type parameter alongside `version` in §7.1, and state the
conflict rule. The parameter binds to the representation, which is where a statement about what the
representation *is* belongs, and it extends a parameter list the section already opened.

**The conflict rule has a precedent in this same spec, and should follow it.** §2.2.1 already answers the
identical shape of question for content hashes: "two that declare different hashes are in conflict — at most
one describes the real bytes — and a consumer that observes both MUST report an error rather than choosing
between them". A header and a body directive naming different schemas is the same situation, and silent
precedence is how a document gets validated against a schema nobody intended.

**Related:** `UPSTREAM.md` #7 — the writers cannot emit `!!schema`, so a TSON *response* has no in-band
option either, and this parameter is currently the only channel in that direction.

### Not to file

§7.1's media-type prose is implemented (`TsonMediaType`, `TsonAcceptHeader`) and held up: `application/tson`,
the optional `version=1` parameter, and the UTF-8 fix are each stated once and unambiguously, and nothing in
them needed an interpretation to be chosen.

One resolved question and one near-miss, recorded so neither is re-investigated.

**A schema origin cannot run on a non-default port**, since §2.2.1 forbids a port in an identifying URI and
the same URI is what a `!!schema` reference carries. This reads as an oversight — it makes an ephemeral test
port, an internal endpoint on `:8443`, and a local development server all unreachable by name. It is not:
§2.2.1 is explicit that identity is "independent of its storage location" and that a consumer "MAY fetch by
whichever scheme its policy allows", so mapping an identity to a location is a consumer policy the spec
anticipates rather than a hole in it. `TsonHttpSchemaSource.mapHost` is that policy. Nothing to file — but
worth stating in [TSON-GUIDE], if it says anything about deployment, that a fetching consumer is expected to
carry an identity→location policy rather than dereferencing the identity directly, because every naive
implementation will dereference it and then discover ports are impossible.

**The near-miss.** A `text`-typed field accepts `42`, `true` and
`2026-01-01` — any token — and rejects only a non-token such as an array or a record. That reads as
under-enforcement until two sections settle it: §4 says base type resolution does not apply at a
schema-typed position, and §7.1's "form is not meaning" makes a type contract operate on the token's
text rather than on how it was written. The implementation is right and the spec is clear; it is the
combination that is easy to get wrong. Pinned by
`TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
