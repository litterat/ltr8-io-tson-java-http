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

## 7. ~~The writers cannot produce `!!schema`~~ — DONE (`2335e00`)

`TsonObjectWriter.describing(schemaUri, rootTypeName)` and `TsonTreeWriter.describing(schemaUri)`, plus
`identifiedBy(documentId)` on both, over a shared `DocumentHeader` that knows §2.2's ordering. Off by default,
so existing output is unchanged.

The object form requires **both** arguments, which is the detail worth keeping in mind: a bound record writes
no type-ref of its own, so a `!!schema` alone yields a document whose reader answers "declares a `!!schema` but
has no root type-ref to select a type". Half self-describing is not self-describing, and there is deliberately
no one-argument form to get it wrong with. The tree form needs one, because a tree node already carries a
type-ref.

**Adopted here.** `TsonHttpCodec.writeProblem` writes through a `describing` writer, so an error body names
`problem-1.tn` and reads back with `readObject` and nothing told out of band — and this project's schema
handler publishes that document, so the URL in it resolves. `write(value, schemaUri, rootTypeName)` and
`writeTree(value, schemaUri)` are the opt-in for application types; the JDK and Javalin demos use them, so a
posted order comes back naming the schema that governs it.

One place it does not reach: the Helidon demo's order route goes through `TsonMediaSupport`, whose
`EntityWriter` is handed a value and a stream and has nowhere to learn a schema URI from. That is left as it
is, and commented, because it is an honest cost of the native seam rather than something to paper over.

---

## 8. ~~`DataBindContext.getDescriptor` races on the first write of a class~~ — DONE (`7c8dbe0`)

A lost cache-fill race now takes the winner's descriptor instead of throwing `Class already registered`.
Verified from this end: deleting the `prepareToWrite` workaround from `TsonHttpCodecConcurrencyTest` and
running four times gives four clean runs, where before it failed on 5 of 32 threads every run.

The fix's own note is worth reading — it explains why `computeIfAbsent` was not the answer *there*: descriptor
resolution recurses back into `getDescriptor` for every component type, and `computeIfAbsent` on the map being
computed is a documented deadlock. (Not the same reason it is wrong for this project's schema cache, where the
loader is not re-entrant and the objection is holding a bin lock across a network call.)

`TsonHttpCodec.prepareToWrite` stays, re-documented as what it now is: a warm-up that keeps first-write
descriptor resolution off the request thread, not a correctness measure.

---

## 9. No public way to read a data document's header without reading the document

**Hit:** routing a request to the right schema version means knowing which schema the document names *before*
choosing how to read it. There is no API for that. `TsonDataParser.peekDirectiveName()` exists and is exactly
right, but is package-private in a package `tson-compiler` does not export; `TsonSchemaParser` handles schema
documents, not data ones; and everything public reads the whole document, which is the thing that needs the
answer first.

**This is a designed-for capability with no door.** [TSON-DATA] §7.1: "classification requires at most two
directives of lookahead and no value parsing, so streams, previews, and content sniffers can classify a
document from its opening bytes." That sentence describes a use case the format deliberately supports and this
library cannot serve.

**Workaround in place:** `TsonDocumentPeek`, a small strict scanner over the leading bytes, with adversarial
tests whose governing rule is that it may answer "I could not tell" but must never answer with a schema the
document does not name. It is a second implementation of a fragment of the lexer, which is exactly what should
not exist.

**Change:** expose it — a `TsonDocumentHeader` for *data* documents (`!!id`, `!!schema`) read from a stream
without consuming it, or simply make the existing peek reachable. Symmetric with the `DocumentHeader` the
writers just gained (#7), which names the same two directives from the other end.

**Blast radius:** additive.

---

## 10. Binding drops a schema field the target class has no component for, silently

**Hit:** the multi-version case, which is where it matters. Given `order-2.tn` adding a `currency` field, a
codec whose binder maps `order` to a v1 class reads a v2 document and returns `OrderV1[sku=A, quantity=1]`. The
currency is gone. No exception, and **no diagnostic even under a collecting receiver** — `diagnostics=0`. Tree
mode over the same document keeps `currency=AUD`, so the document was read correctly against its own schema; it
is the bind that discards the field.

The reverse is silent too: a class with a component the schema does not declare gets `null` for it.

**Why it is worth reporting rather than shrugging at.** Ignoring unknown fields is a reasonable evolution
policy — it is what most wire formats do. Doing it *invisibly* is the problem, because the caller cannot tell a
deliberate leniency from a misconfiguration. In a server the misconfiguration is realistic and the consequence
is not cosmetic: a v1 endpoint reachable by a v2 client processes an order and drops its currency.

Note the contrast with §7.2's record closure, which this library enforces properly: a field in the *data* that
the *schema* does not declare is an `UNRECOGNIZED_FIELD` error. It is only the schema-to-class direction that
says nothing.

**Change:** make it reportable — a diagnostic through the receiver (`UNBOUND_FIELD`, say) when a schema field
finds no component on the bound class, and its converse when a component finds no field. A caller wanting
leniency ignores it; a caller wanting strictness fails on it; neither has to guess. Not an exception by default,
which would break the evolution case this leniency is presumably for.

**Workaround in place:** `TsonSchemaVersions` refuses to route a document to a codec built for a different
version, so the mismatch cannot arise. That is a guard around the gap, not a fix for it — anything binding
outside that router is still exposed.

**Pinned by** `TsonSchemaVersionsTest.aCodecFromTheWrongVersionSilentlyDropsFieldsItsClassLacks`, which asserts
the current behaviour so that a fix upstream makes it fail and say so.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off.

### To file: how a schema is named for a document that cannot carry `!!schema` (§6, §7.1)

**Section:** [TSON-DATA] §6 (JSON compatibility) and §7.1 (Encoding, Normalization, and Media Type).

**The gap.** §6 makes every valid JSON document a valid TSON document, and the format's stated target use is
validating generated structured output against a schema. But `!!schema` is TSON directive syntax, and a JSON
document cannot carry one — so for the entire JSON-compatible surface there is no in-band way to say which
schema governs the document. §7.1 already legislates for HTTP (`application/tson; version=1`, "if
disambiguation is needed in HTTP contexts") and stops exactly before the parameter that would answer this.

**Now the smaller half of a larger proposal.** Building version routing turned up a stronger reason for an
out-of-band channel than JSON compatibility: an intermediary routing between two servers by schema cannot parse
the body to find out which one, and a compressed body makes it impossible rather than merely rude. The full
proposal — rules, naming procedure, structured-field syntax, and the two decisions it needs — is in
**`SCHEMA-HEADER.md`**.

**The interpretation this project uses today**, pending a decision: the body's `!!schema` is the only channel;
`TsonSchemaVersions` refuses a document that names no version rather than guessing one.

**The conflict rule, whatever is decided,** has a precedent in this same spec and should follow it. §2.2.1 on
content hashes: "two that declare different hashes are in conflict — at most one describes the real bytes — and
a consumer that observes both MUST report an error rather than choosing between them". A header and a directive
naming different schemas is the same situation, and silent precedence is how a document gets validated against
a schema nobody intended.

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
