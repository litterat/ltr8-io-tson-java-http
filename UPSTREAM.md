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

## 1. A `data` entry is in the type system everywhere except at the one position that would name it

**Hit:** the CRUD-family payoff of an API description. `fetch => <T> !operation { method: GET  path: "/x"
responses: [ { status: 200  body: T  description: "found" } ] }` — one declaration standing for every
fetch-by-id endpoint — declares and resolves. Nothing may apply it. `getOrder => fetch<order>` is refused:

> `'fetch<order>' names 'operation_GET_/x_200_order_found_1f8d998a', which is built with 'operation' and
> describes something other than a data value — it is declared by this schema but is not a type, so nothing
> can be typed by it`

**The mechanism is not missing, which is the thing to know before designing anything.** An earlier version of
this entry asked for "a way to name the application", implying one had to be invented. It does not: the
declaration position already produces a name-keyed entry for a template application. Measured, on the type
case:

```
page_of_order => page<order>

page_of_order          kind=REFERENCE  source=page_order_463f346d
page_order_463f346d    kind=PRODUCT    source=page
```

The author's name survives as a real entry in the map — a REFERENCE onto the internal instantiation — which is
exactly the shape an operation needs, since a generated name is no use to anything looking an operation up by
name (`TsonApiCoverage.serving`). **What fails is one kind check at the last step**, because a REFERENCE is
defined as pointing at a *type* and the target here is `kind: DATA`.

**And the spec is not of one mind about that check.** §4.1 *enumerates* the positions where naming a
`kind: DATA` entry is an error — "a field type, element type, variant, argument, composition operand, or
refinement source" — and **a reference target is not among them**. But §4.1's own definition of the REFERENCE
kind, and §8.3, both say a reference points at a type. Which reading governs decides whether this is a spec
change or an implementation one, and it is worth settling either way: once `data` entries became full citizens
of resolution — namespace membership, `type_ref` slots whose references are walked, templates, structural
identity — "reference" quietly stopped meaning "reference to a type", and the definition did not move with it.

**Change**, in preference order:

1. **Let a reference target a `data` entry**, leaving §4.1's enumerated positions exactly as they are. An
   alias declaration is a binding, not a typing position, so nothing that the DATA rule protects is weakened:
   no field, element, variant, argument, composition operand or refinement source becomes able to name one.
2. If that is wrong, say so in §4.1 — add the reference target to the enumerated list, so the refusal is
   stated rather than inferred from REFERENCE's definition, and the gap becomes a deliberate closed door
   rather than an oversight.

**The consumer cost, stated because it is real and small:** `getOrder` would then be a REFERENCE, so `method`
and `path` live one hop away on the instantiation. `TsonApiDescription` does not follow that hop today.

**Workaround in place:** write each operation out untemplated, which is what this project's description does.
That costs a full record per endpoint where the template would have cost an application, and it is the cost
the `data` base kind otherwise removes.

**Priority: low** — a description is written once and read often, so verbosity there is cheap. Recorded
because the remaining step is small enough to look already done, and because the framing above took a
measurement to arrive at. Pinned at both stages by
`UpstreamGapsTest.aTemplatedDataConstructorDeclaresButItsApplicationCannotBeNamed`: asserting only the throw
would go on passing if the declaration regressed to a parse error, which is a different gap wearing the same
red.

---

## 2. The object reader cannot continue a peek against a stated type

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
