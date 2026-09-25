# Upstream changes wanted in `ltr8-io-tson-java`

That repo is **hands-off** for now. Anything this project would like changed there is written up here
first, and only landed on the user's say-so. Each item states what this project hits, why the workaround
is unsatisfying, and what the change would be.

**This register holds what is open, and nothing else.** An item whose change landed upstream is deleted, and
so is one whose answer was a decision — rejected, withdrawn, or built here instead. The reasoning that got
there is in git history, which is the right place for it; leaving it inline turns a to-do list into an archive
nobody reads to the end of. This mirrors the sibling's own `SPEC-FEEDBACK.md`, which renumbers from #1 each
time a revision closes for the same reason.

Two consequences worth knowing before citing anything here:

- **Numbers are not stable.** Deleting a closed item renumbers the rest, so an `UPSTREAM.md #N` reference from
  Javadoc or prose is a reference to a *live* item and needs re-checking whenever this file is pruned. Keep
  such references few and put them only where the open question is the point.
- **Cite the behaviour, not the item.** Where a gap has closed, the rule it left behind is stated where it
  applies — in the Javadoc of the class that relies on it, or in `CLAUDE.md`'s "Traps" — with no number at
  all. That is what stops a fixed gap being reintroduced by someone who never reads this file.

**A closed gap keeps its test.** `UpstreamGapsTest` outlives every entry deleted from here: a fixed gap flips
its assertion rather than losing it, which is what makes a regression upstream fail a test here instead of
passing unnoticed. Deleting an entry is a documentation act, never a test one.

---

## 1. The object reader cannot continue a peek against a stated type

**Hit:** reading a body whose schema arrives in the `TSON-Schema` header rather than in a `!!schema` directive,
in **bind** mode. `Tson.begin` hands back a `TsonDocumentPeek`, and the readers continue on one — but only some
of them do:

| | from a stream | continuing a peek |
|---|---|---|
| `TsonTreeReader.read` | yes | yes |
| `TsonTreeReader.readAs(…, typeName)` | yes | **yes** |
| `TsonObjectReader.read` | yes | yes |
| `TsonObjectReader.readAs(…, typeName, targetClass)` | yes | **no** |

So the one combination a header-governed bind-mode read needs — schema from the field, root type from the
route, body read once — is the missing cell. `TsonHttpCodec` mirrors the asymmetry rather than papering over
it: it has `readTreeAs(peek, …)` and no `readObjectAs(peek, …)`.

**Why the workaround is unsatisfying rather than fatal.** Where the header is the *only* channel the body has —
a JSON body, which can carry no directive — there is nothing for a peek to cross-check, so reading from the
start costs nothing and that is what `TsonSchemaHeaderRoutingTest`'s JSON route now does. The gap bites where a
body could carry a directive *and* a header: enforcing rule 3's agreement requires the peek, and bind mode then
cannot use it, so such a route must read in tree mode or give up the check.

**Change:** add `readAs(TsonDocumentPeek, String typeName, Class<T> targetClass)` to `TsonObjectReader`,
matching the tree reader's own `readAs(TsonDocumentPeek, String)`. The private `readPeeked` and
`readDocumentAs` machinery both already exist on that class; this is the entry point that was not written, not
a capability that is absent.

**Workaround in place:** `TsonHttpCodec.readTreeAs(peek, …)` exists and its Javadoc names the gap; the JSON
route reads from the start and says why.

---

## 2. A JSON document cannot name its own binding

**Hit:** a JSON body that names its schema and root type in band. [TSON-JSON] §3.4 gives a JSON document two
routes to its binding — out of band, supplied by the application, and in band, a root annotation object carrying
`$schema` and `$type` — and §3.5 requires a `TSON-Schema` header to agree with an in-band binding by canonical
identity. tson-java's JSON reader builds the first route only, and refuses a `$schema` at any position that is
not scoped, the root included. So a body that names its binding twice, in agreement, is a 400 here, where the
spec calls it valid.

**Already on upstream's list** — `BACKLOG.md`, "A JSON document has no in-band way to name its schema". Recorded
here because this project hits it, and because what it needs is specific: an entry that reads the in-band
binding (or reports its absence) before the body, so `TsonHttpCodec` can check §3.5's agreement against the
header — the JSON counterpart of what `TsonDocumentPeek` gives a TSON body.

**Workaround in place:** none needed for the common case — a JSON client sends the header and a bare value,
which is §3.4's "expected production route". Pinned by `UpstreamGapsTest.aJsonDocumentsInBandBindingIsRefused`.

---

## 3. `readAs` against a stated schema silently overrides the document's own `!!schema`

**Hit:** `TsonObjectReader.withSchema(a).readAs(doc, type, cls)` and the tree reader's `readAs` read a document
whose own `!!schema` names a *different* schema `b` as if it were `a`, with no diagnostic. Upstream's Javadoc scopes
`readAs` to "data that isn't self-describing", but nothing refuses a self-describing document that reaches it.

**Why it matters here:** `TsonHttpCodec.readObjectAs`/`readTreeAs` are this project's out-of-band reads — a route
supplying the schema and root type, which is what a JSON body needs. A route using one on a TSON body can be sent
a document naming some other schema, and it is validated against the route's instead. [TSON-JSON] §3.4 and §3.5
state the posture for the equivalent JSON and header cases: where two channels supply a binding they MUST agree,
and disagreement is an error, never a precedence question — silent precedence is how a document is validated
against a schema nobody chose.

**Change:** where the document names a schema and `withSchema` names another, report the disagreement (by
canonical identity, §2.2.1, so scheme and pin do not count) rather than reading on. A resolver-category diagnostic
seems right, matching §3.4's "disagreement is a resolver error".

**Workaround in place:** the demos read a TSON body by its own binding (`readObject`) and use `readObjectAs` for
JSON alone. The codec cannot check agreement itself in bind mode, because checking needs a peek and the object
reader cannot continue one (#1) — so #1 closing is also what would let this project check it locally. Pinned by
`UpstreamGapsTest.aStatedSchemaSilentlyOverridesTheDocumentsOwn`.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump.

### To file: name hygiene does not reach a map key, where a naming scope now lives

**Sections:** [TSON-DATA] §8.2 (confusable names, the identifier profile, restricted scripts), §8.3 (skeleton
distinctness and what it composes over), §2.6 (map keys are values), §7.7 (the identifier grammar);
[TSON-SCHEMA] §2.1 (the schema body as a name-keyed map).

**The gap.** §8.2's three rules apply to *declared names* -- a schema's declarations, a record's fields -- and
skeleton distinctness is stated over a scope those inhabit. A map key is data, so none of it reaches one. Two
method names in one interface with equal UTS #39 skeletons (`admin` and `аdmin`, the second with U+0430) are
admitted, as is a mixed-script one, where the same two names as two fields of a record or two declarations of a
schema are refused under the default Highly Restrictive identifier policy. Measured both ways in
`experiments/meta-service/java/…/NameRoleProbe.java`, under a key typed `type_name`, one typed `method_name`
(a role over `identifier`) and one typed `text` alike -- the role changes the grammar enforced and the name in
the refusal, and changes nothing about hygiene.

**Why it is not merely an implementation choice.** An interface's method map is a naming scope in every sense
§8.2 means: names a reader must tell apart, in one document, where confusing two of them is the attack. What
moved is where such scopes live. Once a design puts members in a map keyed by an identifier role -- which is
what a borrowed namespace looks like in TSON today, and what §4.1's `data` kind exists to make possible -- the
spoofing surface §8.2 was written for moves with them, and the rules stay behind on the declaration map.

**What this project does meanwhile:** nothing, and says so. The experiment's `Routes` could scan its own keys,
but a check that lives in one consumer is exactly the shape this repo argues against for the schema-fetch
policy -- one security rule with a second implementation free to drift lenient.

**Two ways it could close, and the choice is the author's.** The implementation could apply the identifier
policy to a map whose key type resolves to an identifier role, which needs no spec change and is invisible to a
map keyed by `text`. Or §8.2 could name such a map a scope, which is the more honest fix and reaches the other
implementations. Either would be caught by the probe, which is written to fail when hygiene starts applying.

**Priority:** low against a shipped feature, higher against the meta-service direction, since that design puts
every method and every route name at a map key.

---

**Nothing else is staged.** The four entries this section held — §8.2's policy has no artifact; naming a schema for
a document that cannot carry `!!schema`; no shorthand for a template application at a `type_ref` slot in data;
a namespace should be a value — were filed there on 2026-09-01 (#16–#19 at filing). Prose in this repo names
each by its subject, not its number. A new finding goes here in the same shape: a `### To file:` heading naming
the spec sections, the gap, what this project does meanwhile, and a priority.
