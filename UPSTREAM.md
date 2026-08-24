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

**Versioned rather than edited** — which was §10 applied too eagerly, and has since been undone. Immutability
protects a document someone may already have pinned; nothing here is published, so the versions were collapsed
back to one `problem-1.tn` that is edited in place. `TsonProblemSchema.publishedSources()` still exists and the
demos still serve what it returns, so the shape is ready for a real release.

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
to be checked against the enum."* `TsonProblemSchemaTest` now does that for `problem-1.tn`, reading the members
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

## 10. ~~Binding drops a schema field the target class has no component for, silently~~ — DONE (`4d02eab`, `83b8524`, `1acf7c2`)

**Strict binding landed, and it is better than what was asked for.** Both directions now refuse rather than
drop: a schema field with no component, and a component no field fills. The message names both sides and the
remedy — *"'order' and OrderV1 do not agree: no component for field 'currency'. Bind the class the schema
describes, or read leniently (TsonConfig.lenientBinding) if dropping this is deliberate."* Three things I had
not thought of and that make it right: the FIXED exemption, `@Unbound` for a component that is genuinely the
class's own, and `lenientBinding()` as the place a deliberate versioned-evolution intention gets written
down rather than defaulted into.

The meta-layer reverse case recorded above is fixed with it: a `Data` body whose Java record has a component
the meta does not declare is now a `TsonBindMismatchException` naming both sides, where it was an NPE thrown
out of `Tson.resolve`.

**And the multi-version case this item was reported from is closed too** (`1acf7c2`), by binding profiles
rather than the field-set matching the backlog deferred. `@Profile("api-1", fields = {…})` marks a
constructor, `DataBindContext.Builder.profile` names the one a context wants, and the two are matched by
label — the binder never learns that schemas exist, which is the right place to have drawn that line.

**Two details worth having read before using it.** A profile that names no constructor of its own falls back
to the canonical one, so the version whose shape *is* the class needs no annotation. And `fields` is not
optional in practice: a secondary constructor's parameter names are `arg0`/`arg1` in the class file unless
the build passes `-parameters`.

**Selection and checking hold together, which is the part that makes it safe.** The profile picks a
constructor and strict binding then verifies *that* constructor against *that* version's schema, so a profile
pointed at the wrong version fails rather than binding the other version's shape. Both halves are pinned
here.

**Adopted here:** `TsonSchemaVersions.Builder.version(…, profile)` sets the profile on that version's
context, so one class serves every version — `TsonSchemaVersionsTest.oneClassServesEveryVersionThroughABindingProfile`,
with the no-profile refusal and the wrong-profile refusal beside it.
`TsonHttpCodec.prepareToRead(String…)` compiles a schema in bind mode at startup, and
`TsonSchemaVersions` calls it for every version it serves — so a class registered against a schema it
disagrees with is a startup failure rather than a 500 on the first request that reads one. That is the whole
value of the check being static, and it needed a door on this side to reach it.


**Hit:** the multi-version case, which is where it matters. Given `order-2.tn` adding a `currency` field, a
codec whose binder maps `order` to a v1 class reads a v2 document and returns `OrderV1[sku=A, quantity=1]`. The
currency is gone. No exception, and **no diagnostic even under a collecting receiver** — `diagnostics=0`. Tree
mode over the same document keeps `currency=AUD`, so the document was read correctly against its own schema; it
is the bind that discards the field.

The reverse is silent too: a class with a component the schema does not declare gets `null` for it.

**The reverse case is worse at the meta layer, and that is new.** A `Data` body's `references()` runs *inside
schema resolution*, so a component the meta declaration does not declare arrives `null` and the consumer's own
method dereferences it there:

```
NullPointerException: Cannot invoke "java.util.List.forEach(java.util.function.Consumer)"
                     because "this.parameters" is null
```

thrown out of `Tson.resolve`. Measured: a Java record of `(method, path, parameters, request, responses)`
against a meta declaring `operation => ~data & { method: text  path: text  responses: [type_ref] }`. Three
things make this sting more than the data-side version:

- **It is an NPE from inside the resolver**, not a diagnostic, so it reads as a library fault. The status
  policy here would classify it 500 — *"a fault in this server"* — when the truth is a consumer's meta
  declaration and Java class disagreeing.
- **The author has both files open.** The declaration and the record are the two halves of one registration
  (`UPSTREAM.md` #15), written together, and nothing checks them against each other. A missing field is the
  likeliest mistake in the whole mechanism.
- **`Data.references()` is library-invoked**, so the null is dereferenced by consumer code the consumer did
  not choose to run at that moment.

A diagnostic naming the component and the constructor — *"`operation` declares no `parameters`, which
`io.…Operation` requires"* — would be caught at registration rather than at first use. Consistent with the
main item: the field-count mismatch is worth a diagnostic in **both** directions.

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
`problem-1.tn`'s own import either way — transitivity is retained — but a schema that uses a name should say
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

## 13. A template application cannot appear inside a choice — still open, now reported as a diagnostic

**Still not implemented; only the channel changed** (`f381010`). It used to abort the pass as an
`UnsupportedOperationException` and now arrives as `Diagnostic.Code.NOT_IMPLEMENTED` beside the ordinary
problems, so one unimplemented construct no longer costs every other declaration its verdict.

**Worth recording because it nearly read as a fix here.** The pinning test asserted that resolution *throws*;
it stopped throwing, which is indistinguishable from the feature landing if the test is not looking at the
code. It had not landed. A test that pins a gap must pin the gap, not the delivery mechanism — corrected in
`SketchTest.anApplicationInsideAChoiceIsStillNotImplemented`.


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

## 14. ~~A value parameter filling a FIXED field loses its fixedness~~ — DONE (`f583901`)

**Fixed where it was wrong** — the declaration, not the substitution. A materialised `status: status_code = S`
is now `REQUIRED_FIXED` carrying the substituted value, so `!created { status: 999 … }` is `FIELD_FIXED`
rather than accepted. Both this project's reproductions flipped: `SketchTest.aValueParameterFixedFieldConstrains`
and `theTemplatedResponseFormResolvesAndItsStatusIsFixed`.

**Consequence here beyond the fix:** this was the disqualifier the `response<T, S>` shape was rejected on in
`sketch/README.md`, and it is gone. The rejection now rests on the two remaining legs, which is a weaker case —
recorded honestly there rather than left standing on a reason that has expired.


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

**Reproduced again in a second design, which is what settles it.** Weighing a `responses: [type_ref]` shape
for meta-http — an operation naming applications of `response<T, S>` rather than carrying data records — the
materialised field is `state=REQUIRED, value=Optional[201]` there too. So the templated form of a response is
*less* checked than writing `status: 201` as data against a refined `status_code`, which is checked today
with a position. That inversion is the reason the shape was not adopted; see `sketch/README.md`. Pinned by
`SketchTest.theTemplatedResponseFormResolvesButItsFixedStatusIsNot`.


**Workaround:** none that keeps the template. Fixing the status literally means a record declaration per
response, which is what the template exists to remove. `sketch/orders-api-3.tn` keeps the template and says so
in its own `@doc`, because the shape is right and the gap is upstream.

**Pinned by** `SketchTest.aValueParameterFixedFieldDoesNotYetConstrain`, which asserts the current behaviour so
a fix makes it fail.

---

## 15. ~~A constructor whose instances are never data cannot be registered~~ — DONE (`892d210`)

**Resolved by option 2, and better than proposed.** `type_kind` gained `DATA`, `data` was declared in the
kernel as a base kind alongside `atom`/`product`/`sum`, and a Java body implements the new
`io.ltr8.tson.schema.meta.Data`. So the test is on a real kind rather than on the supertype chain, which was
the ugly part of what I suggested. `MetaLayerDataConstructorTest` and `consumer/Operation.java` are the
worked example. Registration is three things and nothing else: the meta schema declaring
`operation => ~data & { … }`, a Java class with `@Typename` implementing `Data`, and a `DataNameBinder` that
finds it. **No reader family and no factory registration** — the ordinary record reader binds the payload.

**Two things it does that I had not asked for, and both matter more than the registration:**

- **`Data.references()` reaches the linker.** A body declares which of its slots are references, and a name
  that resolves to nothing is an author error at schema load. This is the entire difference between
  `sketch/orders-api-1.tn` and `sketch/orders-api-4.tn`: the data-shaped description spells a payload as the
  string `"sku_not_found"` and nothing checks it, so `TsonApi.validate` had to do it by hand — and
  reimplemented #11's bug in the process. Declaring references on the body, rather than inferring them from
  slots spelled `type_ref`, also means a consumer keeps the choice.
- **An operation cannot be used where a type belongs.** Field, variant, element and map-value positions are
  all refused at link time, with *"describes something other than a data value"*. Against a kernel without the
  kind, the misuse resolves, links, compiles, and fails only when some document is read against the schema.

**One correction to my own framing.** I read `~` as "this is a type constructor". It is not: `~` is the
permission for a **schema** to write `!C …` (§3.3.1/§5.6), and says nothing about type-ness. So it is still
required on `operation` even though an operation is not a type — which is why `~data &` reads oddly at first
and is right.

Adopted here: `sketch/meta-http-1.tn` now says `~data &`, `sketch/orders-api-1.tn` resolves, and
`tson-http/src/test/java/io/ltr8/tson/http/apimeta/` holds the Java side. See #17 for what still stands in
the way of using it through the public API.


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
operations, no need for FIXED fields (so #14 cannot arise), and — the one that matters most to a consumer —
**the type names land directly in the Java record** instead of being recovered by walking resolved type
structure. `ApiModelExtractionTest` measures that walk on the ordinary-schema design: a synthetic `choice_…`
entry to look up, an instantiation `Reference` to follow, and a branch because a single-response operation and
a multi-response one have structurally different resolved forms. All of it becomes
`body() instanceof Operation op` and `op.responses()`.

**The question of whether references in a constructor payload get resolved is already answered — for
`type_ref`.** `DefinitionResolver.takesATypeRef` decides it **by the slot's declared type**, not by hardcoded
knowledge of which meta records have reference slots, and follows aliases with this reason given:

> *Aliases are followed, since a meta layer may name the kernel's own `type_ref` something of its own.*

So the mechanism is generic and was written anticipating exactly this case. A user-declared
`operation.request: type_ref` should ride the same reference channel `array.element_type` does. Nothing about
it is special-cased to the bundled meta.

**But `type_name` has no such path.** It is resolved where the resolver has specific handling — `supertypes` —
and there is no generic "resolve `type_name` slots" counterpart. So the two sketches differ where their
simplicity suggests the opposite: `meta-http-1.tn`'s `request: type_ref` should be checked, and
`meta-http-3.tn`'s leaner `request: type_name` most likely is not. **Use `type_ref`.**

This makes #15 the only thing between the design and checked references — the resolution half is built.

---

## 16. ~~An annotation before a root type-ref hides it~~ — DONE (`1d49b27`)

**Fixed** — the root type-ref lookup now looks past the annotations preceding it, so
`@doc:"…" !api { … }` reads. The reader stack always handled it; what could not was the lookup that finds the
type-ref to *select a reader with*, which saw one event and concluded a type-ref that was there was missing.

Nothing here needed unblocking by the time it landed: the document that wanted it was the data-shaped API
description, retired when the schema-shaped one was picked. Verified working all the same.


**Hit:** writing the API description in `sketch/orders-api-4.tn` with a `@doc` explaining itself, as every
schema in that directory does.

```
!!schema:"…/api-2.tn"
@doc:"why this document exists"
!api { … }
```

```
data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type
```

The type-ref is right there. The root-type-ref lookup does not look past annotations, single-line or
multi-line, and the error blames the wrong thing — it reports the type-ref as absent rather than the annotation
as unexpected, so the author's first instinct is to add a type-ref that is already present.

§3.3 reads the other way: *"Directives precede annotations in the grammar; augmentation attaches to the value
that follows it."* So `@doc:"…" !api { … }` is an annotation and a type annotation both attaching to the same
value, and the root type-ref is `!api`.

Putting it after the type-ref is a parse error, so there is no spelling that works:

```
!api @doc:"why" { … }    →    expected a value …, found '@'
```

**The consequence is larger than it looks.** TSON has no comment syntax (§2.4, deliberately), so an annotation
is the *only* way to put prose in a document. A schema-governed data document therefore cannot be
self-documenting at all — which is a real gap for exactly the documents most likely to want it: configuration,
API descriptions, fixtures.

**Workaround:** none in the document. `sketch/orders-api-4.tn` carries no prose and is explained in
`sketch/README.md` instead, which is the thing this finding is about.

**Change:** skip annotations when looking for the root type-ref, matching §3.3's own ordering. Failing that, at
minimum say what is actually wrong — an annotation here is not supported — rather than reporting a type-ref
that is present as missing.

---

## 17. ~~`Tson.builder()` cannot reach a consumer's own meta-layer constructor~~ — DONE (`8c3245a`)

**Landed as `TsonConfig.metaNameBinder(DataNameBinder)`**, in the shape suggested and with the reasoning
sharpened: what `build()` fixes is the object-binding **mode**, and adding names does not touch it. The
composition is shared rather than hand-rolled — `SchemaMetaNameBinder.extendedWith` /
`contextExtendedWith` — so the kernel's vocabulary always answers first and a consumer's binder only for a
name it does not declare. Nothing can shadow `record`/`enum` or drop `TsonAtomContext`'s registrations.

Kept separate from `dataBindContext`, for the reason given here: one binds the *data* a schema describes,
the other a governing meta's own *vocabulary*, and one namespace holding both would collide the first time a
schema type and a meta-layer constructor shared a name.

Adopted here: `SketchTest` builds an ordinary `Tson` again and keeps the reader, writer and registries.
**With #15 and #17 both in, the in-schema design has no remaining blocker** — the open question is now a
design one, recorded in `sketch/README.md`.


**Blocks #15's feature from the public API.** `TsonConfig.build()` is:

```java
TsonCompiledMetaRegistry core =
        TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), schemaSource);
return new Tson(core, dataBindContext);
```

The configured `dataBindContext` goes to the reader and writer; the compiler always gets
`SchemaMetaNameBinder.defaultContext()`. So a schema that applies a consumer's own constructor fails with
*"'operation' has no usable compiled reader … no bound Java class for 'operation'"* however the caller
configures their `Tson`. Everything #15 landed works — it just is not reachable through `Tson`, and
`SketchTest` has to build a `TsonCompiledMetaRegistry` directly to exercise it.

**The stated reason does not cover this case.** `TsonConfig`'s own Javadoc says the internal context is
*"fixed, not configurable"*, and `Tson`'s explains why: the standard library must be compiled in
object-binding mode, because a DOM reader cannot resolve the `!enum`/`!integer` instances a meta-schema
declares. That argument is about the **mode**, not about **which names the binder knows**. The composition
the upstream test itself recommends —

```java
DataNameBinder binder = name -> {
    try { return SchemaMetaNameBinder.INSTANCE.resolve(name); }
    catch (DataBindException notKernelVocabulary) { return consumerNames.resolve(name); }
};
```

— keeps the mode entirely and only adds names, so nothing the fixed context protects is given up.

**Suggested shape:** a `TsonConfig.metaNameBinder(DataNameBinder)` (or `schemaBindContext`) that defaults to
today's behaviour, is composed over `SchemaMetaNameBinder.INSTANCE` rather than replacing it, and is
documented as *"names your meta layer adds"* rather than as a general escape hatch. Reusing
`dataBindContext` for both would be the smaller change but the wrong one — a consumer's data bindings
(`order` → `Order`) have no business in the compiler's namespace, and the two would collide the first time a
schema type and a meta-layer constructor shared a name.

**Cost of not fixing it:** every consumer of the `data` kind drops to `TsonCompiledMetaRegistry`, and so
gives up `Tson`'s reader, writer and registries, or wires both and keeps them consistent by hand.

## 18. ~~A meta-layer name in a governed schema is refused with the wrong message~~ — DONE (`e4e71c5`)

**Confirmed by design, then fixed where it actually was.** A meta layer is the schema *for* the schema: its
declarations are the vocabulary a schema is written **in**, not types that schema can reference. A governed
schema applies its constructors as `!C { … }` and nothing else, so every refusal was correct — the report was
only ever about the words.

**And the words were a symptom, not a string to edit.** The desugar pass collapsed an application to its bare
head, so the `source` lookup found a template through the meta-structure fallback and faulted it for supplying
no arguments the author had in fact written. Keeping the application whole is what lets the linker judge what
was written; the fallback's own half no longer applies to an argument-bearing `source` at all, a §5.10 head
being resolved in the type-name namespace only (§3.3.1). Both halves are in
`docs/schema-resolution.md` and `docs/linking-and-compilation.md`.

All five forms now refuse in the same words — pinned by
`UpstreamGapsTest.everyMetaLayerNameInAGovernedSchemaIsUnresolved`:

| the governed schema writes | result |
|---|---|
| `x => { s: scalar }` | `'x' field 's' has an unresolved reference 'scalar'` |
| `x => { s: plain }` | `'x' field 's' has an unresolved reference 'plain'` |
| `x => { s: ctor }` | `'x' field 's' has an unresolved reference 'ctor'` |
| `x => { s: tmpl }` | `'x' field 's' has an unresolved reference 'tmpl'` |
| `x => tmpl<text>` | `'x' source has an unresolved reference 'tmpl'` |

**The expanded message was explored and rejected**, rightly. I had suggested the error carry the layering
itself — *"declared by the governing meta-schema, which is the schema for this schema…"*. Every such message
pays its cost on every occurrence, including the ones where the reader already knows; the explanation belongs
in documentation and in whatever guidance a model is given, neither of which is written yet. What the message
owes is the one thing that is true and actionable, in the same words as its four neighbours, and that is what
it now says.


## 19. ~~A bind mismatch has no code of its own, so a server cannot tell it from an invalid document~~ — DONE (`e2daff3`)

**Landed as `Diagnostic.Code.BIND_MISMATCH`, and wider than reported.** `SchemaFailure` classifies every way a
read can fail to get a compiled schema, so the three-way split is made once rather than at each call site:
`TsonBindMismatchException` (including the missing-binding subclass) → `BIND_MISMATCH`, the reading
application's wiring; `UnsupportedOperationException` → `NOT_IMPLEMENTED`, the library's; anything else →
`SCHEMA_ERROR`, the schema author's. `Diagnostic.Code`'s own doc now names the two members that are not a
verdict on the document, which is the distinction this project's status policy is built on.

**One branch of the plan was dropped, correctly.** Rethrowing library faults so they propagate as themselves
is not possible at that boundary: `TsonSchemaSource.fetch` mandates no exception type, so a source may signal
an unfetchable schema with any `RuntimeException`. Reserving `IllegalStateException` would turn a missing
schema into a crash for any source that spells it that way — and this project's own
`TsonHttpSchemaSource` would have been in scope. The residual is a fetch-contract gap rather than a
classification one and is now upstream backlog.

**Adopted here:**

- `invalidDocument` is a three-way. `BIND_MISMATCH` outranks `NOT_IMPLEMENTED` and is a **500**, because it is
  the one an operator must fix and the only one whose message names a server type; `NOT_IMPLEMENTED` stays
  501; everything else is the 400 it always was.
- **The leak is closed by the status, not by filtering.** The adapter boundary already drops detail *and*
  diagnostics from any 5xx body, so routing the status was all that was needed — the class name reaching the
  wire was a consequence of a bind mismatch landing in the 400 branch, where a body carries every diagnostic
  by design. The detail is still populated, because that is what gets logged.
- `problem-1.tn` declares `BIND_MISMATCH`, and `TsonProblemSchemaTest` caught the missing member before I did.
  It went out as `problem-4.tn` at the time — §10 applied reflexively to an unpublished schema, since collapsed
  back to one version. Immutability binds at release, not during development.

**Also fixed while here:** the three demo servers no longer hardcode the problem schema's version in their own
schema text — they interpolate `TsonProblemSchema.ID`. Three bumps in a week each touched sixteen files, and
most of that was demos restating a constant.


**Hit:** mapping the new strictness to a status. `TsonBindMismatchException`'s own Javadoc draws exactly the
right line — *"the schema is not wrong and no library invariant broke; the schema is fine, the class is fine,
and they have been pointed at each other by mistake"* — and this project's status policy needs that line,
because it decides between 4xx and 5xx. A misconfiguration is 500: the client's document may be perfectly
valid, and the message names a server class, which is not a client's business.

**Where it is thrown, that works.** `TsonHttpException.from` classifies `TsonBindMismatchException` to 500,
and `prepareToRead` at startup means a genuine misconfiguration is caught before a request exists.

**Where it arrives as a diagnostic, it does not.** Reading a v2 document through a codec whose binder maps
`order` to a v1 class yields:

```
Diagnostic[..., code=SCHEMA_ERROR, message='order' and …OrderV1 do not agree: no component for
field 'currency'. Bind the class the schema describes, or read leniently …]
```

`SCHEMA_ERROR` is the code for *"your schema is wrong"*, and a collected list of those is a 400. So the one
distinction the exception type exists to draw is erased in the diagnostics channel, and a client gets *"the
request body has 1 problem"* about a document that has nothing wrong with it — plus a server class name.

**Change:** a `Diagnostic.Code.BIND_MISMATCH`, exactly as `NOT_IMPLEMENTED` was added for the same reason one
commit earlier (`f381010`). That precedent is the argument: a gap is not a verdict on the author's document
either, and the fix was to let the code carry the distinction the channel could not. This is the same shape —
*"could not be checked"* versus *"is wrong"* — one step further out, and the CLI would want it too, since a
bind mismatch is no more an exit-1 than a gap is.

**Workaround here, which is not a fix:** routing prevents the crossing, and `prepareToRead` catches the static
case at startup, so the diagnostic path is reachable only by a server that bypasses its own guard. Matching on
message text would close it and is not worth the fragility.

## 20. ~~`@doc` on a schema entry is dropped from resolved output~~ — WITHDRAWN, the report was wrong

**There is no gap. A schema entry has two annotation positions and they land in different places**, and I
checked only one of them:

| written | read back from |
|---|---|
| `@doc:"…" thing => { … }` — annotates the **entry** | `entries.getAnnotations("thing")` |
| `thing => @doc:"…" { … }` — annotates the **definition** | `entries.get("thing").annotations()` |

Both are retained. Every "empty" in the original report was a first-position annotation read through the
second position's accessor, which looks exactly like the annotation being dropped — including the row about
this project's own `problem-1.tn`, which documents its entries in the first position and reads them back fine.
`SchemaAnnotationScopeTest` upstream asserts both, and would have told me had I looked before filing.

**What it cost**, since that is the useful part: a wrongly-filed item, and a redundant `description` field
added to `operation` in `meta-http-1.tn` to work around a problem that did not exist. Both undone —
`TsonApiDescription.doc(name)` reads the entry annotation, which was the original design.

**What would have caught it:** checking the accessor upstream's own tests use before concluding a library is
wrong. The tell was there in the report itself — I wrote that *locally declared annotations do survive*, and
did not ask why `@method` would be kept where `@doc` was not. Two accessors, not two policies.

Pinned by `UpstreamGapsTest.anEntrysTwoAnnotationPositionsLandInDifferentPlaces`, which stays even though
nothing is broken: the shape of the mistake is worth having written down.


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

### To file: no shorthand for a template application at a `type_ref` slot in data ([TSON-SCHEMA] §5.6, §8.1)

The meta-kernel's `type_ref` is explicit and the implementation matches it: at a `type_ref`-typed slot, a bare
token fills `name`, and *"a braced record is the explicit form, canonical only when `arguments` is present."*
So a schema can write `page<order>`, but a **data** payload at a `type_ref` slot — an `!operation { … }`
governed by a consumer's meta layer — must write

```tson
body: { name: page  arguments: [ { name: order } ] }
```

because `page<order>` in that position is a *parse* error (`adjacent values must be separated by whitespace,
a comma, or both`), the `<` never being data syntax. Measured, and both spellings resolve and are checked;
`SketchTest` pins them.

**This is by design and the spec is not wrong.** What is worth raising is whether the design is intended to
cost this much at the one place it now shows up. §5.6's positional form was written for the argument-free
case, and the `data` base kind has since created a class of documents — data-in-a-schema, describing types —
where the *with-arguments* case is routine rather than exotic. An API description applying `page<order>` at
four endpoints writes the braced form four times, or names four aliases.

Three ways it could go, in preference order:

1. **Leave it, and say so.** Add a sentence to §8.1 noting that the sugar is schema-syntax only, so a
   data-position reference with arguments uses the explicit record. Costs nothing and stops the next
   implementer discovering it by parse error, which is how it was found here.
2. **Recommend the alias.** `order_page => page<order>` is one line, reads better than either alternative,
   and gives the application an identity. This is what the sketch does. If it is the intended answer, §8.2
   is the place to say so.
3. **Extend the sugar to data position.** Real ergonomics, and a real cost: `<` becomes meaningful in data,
   at exactly one slot type, decided by the governing schema. Probably not worth it — noted for completeness
   rather than recommended.

One concrete diagnostic point regardless of which: a bad argument in the **alias** form is reported against
the entry the template materialised (`'array_no_such_eb84587b' element_type has an unresolved reference
'no_such'`), where the inline form names the operation. If (2) is the recommended spelling, that message is
the one to improve — the author wrote `order_page => page<no_such>` and is shown a synthetic name they have
never seen.

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
