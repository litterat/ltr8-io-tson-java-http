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

## 2. §6's JSON reader does not exist, and `acceptingJson()` is what notices

**Hit:** `TsonHttpCodec.acceptingJson()`, the opt-in that admits an `application/json` request body. It was a
media-type gate over a guarantee: [TSON-DATA] §6 made every valid JSON document a valid TSON document, so the
TSON reader needed no help. Revision 35 withdrew that. TSON is JSON-*like* and is not a superset, and §6 now
puts JSON compatibility in a separate **JSON reader** — "a second encoding of the same model rather than a mode
of this notation" — which tson-java has not built (its own `CLAUDE.md`: *"a whole separate stack … Not started,
not backlogged"*).

**What the gate admits meanwhile** is JSON as the *TSON* reader reads it, which is neither all of JSON nor
JSON's meaning. Measured, not inferred, and pinned by `TsonHttpCodecJsonTest.theTsonReaderIsNotAJsonReader`:

- **JSON `null` reads as the four-character string `"null"`, with no diagnostic.** §4.4 removed the null
  keyword, so the token is text like any other, and §6's reader is what maps JSON's `null` to absence (§2.9).
  At a `text?` field a JSON `null` therefore binds a string. **This is the one that corrupts rather than
  refuses**, and it is why the item is worth filing rather than living in a Javadoc.
- A key that is not an identifier is a parse error (§2.5), where §6's reader gives a map: `{"first name": 1}`
  and `{"a.b": 1}` are refused.
- A surrogate-pair escape is a parse error (§7.2.2) — which is how JSON must write any non-BMP character.
- There is no `\/` escape, which RFC 8259 permits.

Shared shapes — identifier-keyed objects, arrays, strings, numbers, booleans — read as they look.

**Change:** build §6's JSON reader, or say it is not coming. Both are answers this project can act on; the
present state is the one it cannot, because the method's contract is a guarantee the spec no longer makes.

**Workaround in place:** the method stays, its Javadoc states all four divergences, and the test is written to
fail when a real reader lands — that failure being the feature arriving. `CLAUDE.md`'s "Traps" carries the same
warning where someone would meet it. An endpoint whose clients send real JSON should go on answering 415.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump.

**Nothing is staged.** The four entries this section held — §8.2's policy has no artifact; naming a schema for
a document that cannot carry `!!schema`; no shorthand for a template application at a `type_ref` slot in data;
a namespace should be a value — were filed there on 2026-09-01 (#16–#19 at filing). Prose in this repo names
each by its subject, not its number. A new finding goes here in the same shape: a `### To file:` heading naming
the spec sections, the gap, what this project does meanwhile, and a priority.
