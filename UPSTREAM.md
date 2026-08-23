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

## 5. ~~Lift the diagnostic wire schema out of `tson-cli`~~ — REJECTED, and rightly

**Asked for:** moving `diagnostic_code`/`diagnostic` and the `CliDiagnostic` DTO into a module both `tson-cli`
and this project could depend on, so the CLI and the server would describe a failure identically instead of
maintaining two hand-synchronised copies.

**Rejected upstream**, on the grounds that keeping them aligned was going to fail anyway: a CLI reports on
**files** and a server reports on **requests**, and a shape stretched across both fits neither. This project
should own its error structure and its schema.

**That is the better answer, and the divergence arrived immediately.** Given ownership, the error body became
what an HTTP error body should be:

- `problem` now follows **RFC 9457** (Problem Details for HTTP APIs, obsoleting 7807) — `type`, `title`,
  `status`, `detail`, `instance`, plus `errors`. Ordinary HTTP tooling recognises that; nothing in it makes
  sense for a CLI, whose envelope is built around per-file reports.
- `type` is the member a client matches on: stable where `title` is prose, and dereferenceable. `errors` is not
  an invention either — RFC 9457 §3.1 uses an `errors` extension for exactly this, a list of validation
  failures inside one problem.
- `diagnostic` stays close to what a TSON read produces, because that is what it reports. Deliberately *not*
  RFC 9457's suggested per-error shape (`detail` + `pointer`), which would throw away the code vocabulary, the
  two-ended location model and the source positions §8.1 requires.

**Versioned rather than edited**, which is §10 demonstrated rather than described: `problem-1.tn` is superseded
by `problem-2.tn` and is **still published**, because a document that named it must go on resolving. The demos
serve the whole history via `TsonProblemSchema.publishedSources()`.

**What stays true from the original finding:** `tson-cli` exports nothing and publishes no schema, so a
consumer cannot reach its shapes. That is now simply not this project's problem.

---

## 6. ~~A collecting receiver does not collect base-syntax failures~~ — DONE (`45cfd32`)

A document that will not lex or parse is now reported through the receiver rather than thrown past it, so a
collecting read returns everything wrong with it. Measured from this end: `!order { sku:` now yields **two**
diagnostics where it previously threw one `TsonParseException`, and `{ a: 1`, `!!schema:` and an empty body
each report rather than throw.

`Tson.validate`'s own catch-and-classify shrank accordingly, and `TsonReadException` gained a `toString`.

**Adopted here without a code change**, which is the pleasant part: `TsonHttpCodec` already read with a
collector and already turned a non-empty diagnostic list into a 400, so a malformed body simply started
carrying its diagnostics. `TsonHttpCodecTest.rejectsMalformedTsonAsABadRequestCarryingItsDiagnostics` now
asserts that rather than only the status.

**`TsonHttpException.from`'s base-syntax branch stays**, as a net rather than a path. `Diagnostic.ofBaseSyntaxError`
still classifies the three exception types and rethrows anything else, which is what keeps an unexpected fault
from being laundered into a false verdict — worth keeping even now that nothing routine reaches it.

---

## 6b. Also landed: `Diagnostic` says absence once (`bc015bd`)

`schemaIdIfKnown()`, `expectedIfStated()` and `actualIfStated()` narrow the `""`-means-nothing convention at the
source. `TsonProblemDiagnostic` now goes through them instead of repeating it in a private helper.

That commit also added `DiagnosticsSchemaTest` upstream, checking `diagnostics.tn`'s `diagnostic_code` against
`Diagnostic.Code` — and its note anticipates this project exactly: *"Any consumer rendering diagnostics — this
CLI, an HTTP error body, anything else — declares the vocabulary again in its own wire schema, and each copy has
to be checked against the enum."* `TsonProblemSchemaTest` now does that for `problem-2.tn`, reading the members
through the compiled schema rather than by matching text, and verified to fail when a code is removed.

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

## 11. ~~A schema could reference types from only one other schema~~ — DONE (`0f8a451`)

**Was:** a schema could not import two schemas that both import `core.tn` — and every practical schema imports
`core.tn`. `'void' is declared by more than one !!import`. So a schema could reference types from exactly one
other published schema: a chain, not a library, and an API description was unwritable.

**Fixed by `0f8a451`, merged as `ff0e630`.** All three symptoms resolve, this project's 204 tests pass
unchanged, and `sketch/orders-api-1.tn` advances past this to its one remaining blocker — the custom
meta-schema constructor still not being applicable — which `SketchTest` now pins by driving the real sketch
rather than a trimmed single-import stand-in.

**The resolution is the opposite of the one argued here, and the argument for it is better.** This entry
proposed making the implementation shallow, per §2.2.3. The fix instead **keeps transitivity** and corrects the
collision rule to compare the declaring schema's canonical identity rather than counting name occurrences — one
schema reached by several routes unifies; two different schemas declaring one name is an error naming both. It
diverges from §2.2.3 deliberately, argued in the sibling's `SPEC-FEEDBACK.md` #55: §3.3.1 already gives
`!!meta` the transitive rule ("the target's local declarations plus its imports"), and `core.tn`'s own
`void => !unit {}` depends on it. The bundled chain is itself a diamond.

A bonus the shallow reading would not have given: because identities carry the spec revision, a closure
reaching two revisions of `core.tn` is now rejected at namespace construction rather than surfacing later as a
field conflict between two identically-spelled types.

**Adopted.** `orders-errors-1.tn` in all three demos now names both imports. `text` would arrive through
`problem-2.tn`'s own import either way — transitivity is retained — but a schema that uses a name should say
where it comes from, and that spelling was rejected until this landed. `CLAUDE.md`'s "import the derived schema
only" trap is gone rather than corrected: it described a workaround, and there is nothing left to work around.

---

## 12. ~~A locally declared annotation's value is silently dropped~~ — DONE (`10a0552`)

An annotation whose type is not in the governing meta-schema's namespace is now an error, where it used to
resolve clean and discard the value. The message is better than the one asked for here — it states the rule
rather than the symptom:

```
'@verb' does not name a type in the governing meta-schema's namespace, which is the whole annotation
namespace of a schema document (one hop through !!meta, §…)
```

**Verified from this end:** a locally declared annotation is refused, and `sketch/meta-http-2.tn` +
`orders-api-2.tn` bind end to end — `method=POST`, `path=/orders`, and each response field's status and type.

**What it changes here is the comparison, not the code.** `orders-api-2.tn` already put its annotation types in
a meta layer, so it always worked; what is fixed is that the *obvious wrong thing* now fails loudly. That makes
the annotation design safe to recommend, and re-opens a point this project had scored the other way — see
`sketch/README.md`.

---

## 13. A template application cannot appear inside a choice

**Hit:** describing an operation's responses. `response => <T, S> { status: status_code = S  body: T }` is the
natural shape, and `(response<order, 201> | response<problem, 400>)` is the natural use of it:

```
UnsupportedOperationException: a container sugar form must be lifted to an entry before resolution (§5.3);
this one was not, which means either the desugar phase was skipped or a position inside it is an application,
which has no entry to name until it is materialised: Choice
```

The message diagnoses itself. An application works as a plain field type and inside an array; only `choice`
lacks the lift.

**Workaround in place:** name each application as an entry, then choose over the names —
`order_created => response<order, 201>`, then `(order_created | order_invalid | …)`. Each response is still one
line rather than a record declaration, so this costs a name, not a shape.

**Priority: low.** It is an ergonomic edge with a one-line workaround, and `UnsupportedOperationException`
already says it is a gap rather than an author error. Pinned by
`SketchTest.anApplicationInsideAChoiceIsNotImplemented`.

---

## 14. A value parameter filling a FIXED field loses its fixedness

**Hit:** the same template. `status: status_code = S` applied as `<order, 201>` produces a field that carries
201 and does not enforce it:

| declaration | data with a wrong status |
|---|---|
| `status: int32 = 201` — a literal | **rejected**, `FIELD_FIXED` |
| `status: int32 = S`, applied `<order, 201>` | **accepted** |

So `!created { status: 999  body: … }` validates against a type whose schema says the status is 201.

**Where it goes wrong is the declaration, not the substitution.** The template's own field resolves as
`state=REQUIRED, value=empty, valueParam=Optional[S]` — never `REQUIRED_FIXED`, where the literal form is.
Materialisation then substitutes correctly, producing `state=REQUIRED, value=Optional[201]`: the right value on
a field whose state no longer says it is fixed. So `= S` is being read as "a value routed by a parameter"
without the "and it is fixed" half that `=` means for a literal.

**Why it matters more than #13.** It is silent. A schema author writes a constraint, the schema loads clean,
and the constraint is not there — the same shape of hazard as #10's dropped field, and in the same place a
server would rely on it. A status is exactly the sort of thing an API description fixes and a validator is
then trusted to enforce.

**Change:** carry the FIXED state through a value-parameter binding, so a materialised
`status: status_code = S` is `REQUIRED_FIXED` with the substituted value, as the literal form already is.

**Workaround:** none that keeps the template. Fixing the status literally means a record declaration per
response, which is what the template exists to remove. `sketch/orders-api-3.tn` keeps the template and says so
in its own `@doc`, because the shape is right and the gap is upstream.

**Pinned by** `SketchTest.aValueParameterFixedFieldDoesNotYetConstrain`, which asserts the current behaviour so
a fix makes it fail.

---

## 15. A constructor whose instances are never data cannot be registered

**The shape wanted:** `operation` declared in a meta layer and written by a schema author as
`create_order => !operation { method: POST  path: "/orders"  request: order  … }`. The resolver binds the
payload to a Java `Operation` record and stores it as the entry's body. **No data value ever has an operation
as its type**, so nothing needs to read data against one.

**Two different readers, and only one of them is wanted.** `TsonCompiledMetaSchema.buildConstructors` pairs
them:

```java
if (!entry.getValue().constructor()) continue;            // ~ required to be considered at all
compiledSchema.find(name).ifPresent(instanceReader -> {
    try {
        constructors.put(name, new ReaderResolver(instanceReader, resolver.resolve(name)));
    } catch (RuntimeException noFactory) { /* deliberately swallowed */ }
});
```

- `instanceReader` reads `!operation { … }` **at schema-resolution time**, through the meta schema's own
  compiled machinery. This is ordinary record binding, and it is all an operation needs.
- `resolver.resolve(name)` is a `ValueReaderFactory` — what builds a reader so **data** can be validated
  against a type this constructor produces. An operation has no data instances, so this is asking for something
  that can never be used.

Requiring both is what blocks it, and the failure is silent at the point it happens: the factory's absence is
caught and swallowed, the constructor is never registered, and the error surfaces later as
`'operation' is not a constructor '…/meta-http-1.tn' declares` — which reads as *undeclared* when the truth is
*declared, and dropped for want of a factory it does not need*.

**No meta-schema change is needed to hold one.** `type_definition.body` is typed `top`
(`meta-kernel.tn:412`), which is how `RecordBody`, `ArrayBody`, `Reference` and `instance_template` already
share the slot. An `operation` body is legal by that declaration today.

**The missing category, stated plainly.** `~` means *author-writable, and demands a data reader*. No `~` means
*no data reader, but resolver-produced only* — which is how `reference` and `instance_template` avoid the
requirement, and why an author cannot write one. There is no **author-writable with no data instances**, which
is exactly what an operation is.

**Two ways to open it; the second looks cleaner.**

1. Make `ValueReaderFactoryResolver` reachable so a consumer can register one. It is in the unexported
   `io.ltr8.tson.compiler.reader` package today. This works, but has the author supply a factory for something
   that will never read data.
2. **Let the base kind decide.** An entry whose supertype is `top` rather than `atom`/`product`/`sum` cannot
   have data instances — that is precisely how `reference` and `instance_template` are spelled, and the CR's own
   argument for `top &` over `~product` is that `product` would oblige `access_pattern` and `size_type` as
   meaningless filler. So `buildConstructors` could require a factory only for the value kinds, and register a
   `~top &` constructor with its instance reader alone. Note `type_kind` is
   `!enum [ATOM PRODUCT SUM REFERENCE]` with no member for this, so the test would be on the supertype chain
   rather than on `kind` — or `type_kind` gains a member.

**Not verified from this end:** whether the binding half is already sufficient. `DataNameBinder` is the
established way to map a schema type name to a Java class — `TsonProblemSchema` and `tson-cli`'s
`DiagnosticsSchema` both do it — so registering `Operation` looks like existing machinery, but the factory
requirement stops the run before that is exercised.

**What it unlocks** is in `sketch/README.md`: shape checking, recognisability by construction, templated
operations, and no need for FIXED fields (so #14 cannot arise). `sketch/meta-http-1.tn` is the declaration
waiting on it.

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
