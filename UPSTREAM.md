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

### To file: `identifier` as a text family, and what it settles — map keys, and `enum.profile`

**Sections:** [TSON-SCHEMA] §7.4 (enum member semantics, the `identifier` primitive, text member sets), §5.2
(which fields may carry a value), §5.4 (discrimination class), §5.7 (refinement), §5.10 (templates and parameters),
§8.3 (references), §9 (the meta layer), §11.4 (name hygiene at the schema layer); [TSON-DATA] §2.6 (map keys are
values), §7.7 (the identifier grammar), §8.2 (name hygiene); the meta-kernel's `unit`, `identifier`,
`text_type`, `enum_profile` and `enum`.

**The gap it starts from.** §8.2's three mechanisms reach *declared names* — a schema's declarations, a record's
fields, an `IDENTIFIER` enum's members — and §11.4 lists the scopes the look-alike mechanism runs over. A map key
is data, so none of it reaches one. Two method names in one interface with equal UTS #39 skeletons (`admin` and
`аdmin`, the second with U+0430) are admitted, as is a mixed-script one, where the same two names as two fields of
a record or two declarations of a schema are refused under the default Highly Restrictive identifier policy.
Measured in `experiments/meta-service/java/…/NameRoleProbe.java` under a key typed `type_name`, one typed
`method_name` (a role over `identifier`) and one typed `text` alike: the role changes the grammar enforced and the
name in the refusal, and changes nothing about hygiene.

An interface's method map is a naming scope in every sense §8.2 means — names a reader must tell apart, in one
document, where confusing two of them is the attack. What moved is where such scopes live: once a design puts
members in a map keyed by an identifier role, which is what a borrowed namespace looks like today and what §4.1's
`data` kind exists to make possible, the spoofing surface moves with them and the rules stay behind.

**Why the fix is a type and not a rule about maps.** Upstream has already settled that look-alike keys in a JSON
map must be *accepted* (`design/json-unicode-policies.md`): at a map position `admin` and `аdmin` may be two
legitimately distinct keys, and nothing about the position says they are names. A key whose declared type is an identifier is the schema
saying exactly that. So the line runs through the key's type — `text` keys are data, judged by the token policy as
today; identifier keys are names — and the missing piece is that `identifier` has no constraint vocabulary for the
rules to belong to. The kernel declares it `identifier => !unit {}`, its grammar and §8.2's rules live in prose on
§7.4, and they reach the kernel's own naming positions (`type_name`, `field_name`, `param_name`) by that prose
rather than by the type.

**Proposal 1 — `identifier` becomes a text family.** Declare it the way `uri`, `regex` and `email` already are: a
`text_type` composition with its specification pinned.

```
identifier_type => text_type & atom_specification & {
  spec?: = "<[TSON-DATA] §7.7>"
}
identifier => !identifier_type {}
```

What follows:

- **`identifier` IS-A `text`.** §7.4's "`IDENTIFIER` is inside `TEXT`" becomes a subtype edge rather than a
  selector's declared order, so §5.7's narrowing follows IS-A as it does everywhere else.
- **It inherits the text facets.** `min_length`/`max_length`/`length`, `pattern` (a naming convention such as
  snake_case, still inside §7.7's grammar) and `members` — so `!identifier ^ { members: [...] }` is a closed
  vocabulary of names, with §7.4's member-coherence rule applying unchanged.
- **The per-name mechanisms ride the type.** Every value whose type is `identifier` or refines it meets §8.2's
  character and script rules, judged by the **identifier** policy — at a map key and at a field value alike. That
  reverses §7.4's "`identifier` is not used in data values", and moves §8.2's split from *position* (declared names
  against data) to *type* (identifier-typed against everything else); §8.2's "Values" paragraph needs rewording to
  match, and a document admitted today can be refused under it. Both are the point, and both should be stated.
- **`core.tn` gains a sibling**, as it has one for `void`. Today the kernel notes "Core declares no sibling of it",
  which is why `NameRoleProbe` has to use a kernel-governed meta layer; an ordinary schema should be able to write
  `{identifier => handler}`.
- **One scope is added to §11.4:** the key set of a map whose key type is an identifier. The look-alike mechanism is
  a property of a set, so the type alone cannot carry it; this is the sentence that gives it the set, in the same
  words §11.4 already uses for an `IDENTIFIER` enum's members.

The cost to weigh: an identifier becomes a kind of string in the type system. The obligations it adds over `text` —
the grammar and NFC — are what `spec` pins, which is the arrangement `uri_type` already has.

**Proposal 2 — `enum.profile` becomes `enum.type`, which needs a bounded type reference.** Once `identifier` is a
text family, `profile` states a fact the type system can state itself, and every row of §7.4's profile table is
derivable from one question — *is the member type `identifier` or a refinement of it?*

| §7.4 row | derived from `enum.type` |
|---|---|
| members | each member is a valid value of `type`, by the family's own parsing and facets |
| hygiene | all three mechanisms when `type` IS-A `identifier`; the look-alike mechanism alone otherwise |
| discrimination class (§5.4) | the members' shared class when `type` IS-A `identifier`; string otherwise |
| binding | host enum generation guaranteed when `type` IS-A `identifier`; host text otherwise |

And it is more expressive than the selector: an author can state `type: currency_code`, where `currency_code =>
!text ^ { length: 3  pattern: "[A-Z]{3}" }`, and have every member checked against it, where today a `TEXT` enum's
members are any text.

**The kernel cannot spell this today**, and that is the part to decide first. A field typed `type_ref` names any
type; there is no way to say *a type that IS-A `text`*. What is needed is an upper bound on a type reference —
checked by the resolver against the named type's supertype chain. Two things to settle with it:

- **A second use exists, and it argues for one mechanism rather than an enum-specific one.** Template parameters are
  unbounded too (§5.10): `<T: text>` is the same question asked at a parameter. And this project's
  `meta-http-1.tn` has the same gap at `parameter.type`, recorded in its own `@doc` as "names a scalar and nothing
  enforces that" — a URL segment cannot carry a record. A bound serves all three.
- **What a bound may name.** "A subtype of `text`" is a type bound. "Any scalar", which `parameter.type` wants, is a
  base kind (`atom`). Whether a bound may name a base kind as well as a type decides whether the mechanism covers
  both uses or only the enum's.

**The default runs into §5.2.** A `~` or `=` value is admitted only on a field whose declared type resolves to an
atom-family instance or an enum. `profile?: enum_profile ~ IDENTIFIER` is legal because `enum_profile` is an enum; a
type reference is a record, so `type?: <bounded ref> ~ identifier` would be refused at the declaration. Two ways
through, in order of preference:

1. **`type` optional, absence defined as `identifier` in the prose of §7.4**, and resolver output omitting it
   exactly as it omits `profile` at its default today (§8.1) — so every existing enum resolves unchanged in source
   and output. No rule changes; the cost is that the default is stated in prose rather than visible in the kernel.
2. **§5.2 admits a bare type name as the default of a type-reference field.** More honest, and the tidier end state,
   but a second change riding on the first.

**Refinement becomes subtyping.** A refinement may narrow `enum.type` only to a subtype of the source's `type` — the
relation `profile`'s `IDENTIFIER`-inside-`TEXT` order stands in for today — and the member-coherence check already
applies to whatever type is stated. What does **not** move to the type is the look-alike mechanism on the member
set: a `TEXT` enum's members are still what a value is matched against, so two that read alike are still the hazard
whatever `type` is. That is a property of `enum`, and §7.4 keeps saying so.

**The alternatives, for the author to weigh:**

- **Keep `profile`, defined by reference.** `IDENTIFIER` means each member is an `identifier` value, and the rules
  come from Proposal 1's type rather than being restated in §7.4. No change to `enum`'s shape and no bounded
  reference needed — the minimum that still removes the duplication, and the fallback if bounds are not wanted.
- **Collapse `enum` into member sets** — `!identifier ^ { members: [...] }` and `!text ^ { members: [...] }`. Argued
  against: `enum` carries what a member set does not — the binding row, unquoted spelling, and a discrimination
  class of its own — and Revision 36 chose deliberately to keep both.

**What this project does meanwhile:** nothing, and says so. The experiment's `Routes` could scan its own keys, but a
check living in one consumer is exactly the shape this repo argues against for the schema-fetch policy — one
security rule with a second implementation free to drift lenient. `NameRoleProbe` is written to fail when hygiene
starts reaching an identifier key, which is when the experiment's README and this entry come out.

**Priority:** low against a shipped feature; higher against the meta-service direction, which puts every method and
every route name at a map key. Proposal 1 stands alone and closes the map-key gap; Proposal 2 depends on it and on a
bounded type reference, and can follow.

---

**Nothing else is staged.** The four entries this section held — §8.2's policy has no artifact; naming a schema for
a document that cannot carry `!!schema`; no shorthand for a template application at a `type_ref` slot in data;
a namespace should be a value — were filed there on 2026-09-01 (#16–#19 at filing). Prose in this repo names
each by its subject, not its number. A new finding goes here in the same shape: a `### To file:` heading naming
the spec sections, the gap, what this project does meanwhile, and a priority.
