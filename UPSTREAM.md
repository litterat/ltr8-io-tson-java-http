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

## 1. Thread-safety contract is not stated on the front-door types

**Hit:** a server shares one `Tson` across request threads, so "what may be used concurrently" is a
correctness question here, not a nicety. It still has to be reverse-engineered from scattered class Javadoc —
`TsonCompiledMetaRegistry` ("`register`/`get` are `synchronized`, but `loadMeta` … are not"), `Lexer` and
`TsonDataEmitter` ("single-use, not thread-safe"), and `CompiledReaders`, whose `volatile` binding shows the
intended shape: compile on one thread, read from many. `Tson`, `TsonTreeReader`, `TsonObjectReader` and
`TsonCompiledSchemaRegistry` say nothing either way, and those are exactly the types a server holds in a field.

**Change:** state the contract on the front-door types. Something as blunt as: *a `Tson` and the readers
obtained from it are safe for concurrent reads once every schema has been resolved and compiled; `resolve` and
registry mutation are not concurrent-safe and belong in single-threaded startup.*

**Half of this has landed, which is why only the documentation half is left.** Upstream now has
`ReadPathConcurrencyTest` and a written position in its `BACKLOG.md` — concurrent reads through one `Tson` are
safe and tested, a read takes no lock on the caches it hits, and what stays open there is deliberate mutation
while others read. Both remaining pieces are in a backlog rather than on the types a consumer actually holds,
so a server author still has to go looking.

**Independently confirmed from this end**, which is worth adding to a report that is otherwise about
documentation: 48,000 requests across 8 threads through one shared `Tson`, JFR recording contended monitor
entries at a zero threshold, produced 1416 contentions and **not one with a tson frame**. The shape being
documented is the shape the library has. `CLAUDE.md` carries the numbers.

---

## 2. A `data` entry is in the type system everywhere except at the one position that would name it

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
of resolution — namespace membership, `type_ref` slots that flatten and carry `@alias`, templates, structural
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

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump. Revision 34 carried fourteen of the
seventeen entries that register held and renumbered the survivors from #1; nothing here cites it by number any
more. Neither of the two below has been filed there yet, and Revision 34 addressed neither.

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

**Unchanged by Revision 34**, which left §6 alone and rewrote §7.1 around the identifier layer rather than
the media type. Still worth filing, and the second reason above is the stronger one.

**The conflict rule, whatever is decided,** has a precedent in this same spec and should follow it. §2.2.1 on
content hashes: "two that declare different hashes are in conflict — at most one describes the real bytes — and
a consumer that observes both MUST report an error rather than choosing between them". A header and a directive
naming different schemas is the same situation, and silent precedence is how a document gets validated against
a schema nobody intended.

### To file: no shorthand for a template application at a `type_ref` slot in data ([TSON-SCHEMA] §5.6, §8.1)

The meta-kernel's `type_ref` is explicit and the implementation matches it: at a `type_ref`-typed slot, a bare
token fills `name`, and *"a braced record is the explicit form. Canonical output MUST use the bare token
whenever `arguments` is absent."* So a schema can write `page<order>`, but a **data** payload at a `type_ref`
slot — an `!operation { … }` governed by a consumer's meta layer — must write

```tson
body: { name: page  arguments: [ { name: order } ] }
```

because `page<order>` in that position is a *parse* error (`adjacent values must be separated by whitespace,
a comma, or both`), the `<` never being data syntax. Measured, and both spellings are asserted by
`UpstreamGapsTest.aTemplateApplicationAtATypeRefSlotInDataNeedsTheBracedForm` — the braced record resolves,
the sugar does not parse.

**Unchanged by Revision 34**, which reworked §8.1 heavily — held bodies, `reference.target` widened to a
`type_ref` — and left the positional-form paragraph byte-identical. Still worth filing.

Worth reading alongside it, because it answers a neighbouring question and can be mistaken for this one:
§8.1 explains why the *arguments* are braced — `type_argument` has no REQUIRED field, so "a bare token cannot
self-classify as reference or literal, so its braced record is load-bearing, not ceremony." That is sound, and
it is one level down from the cost reported here, which is the **application** at the `type_ref` slot, where
`name` is REQUIRED and the positional form does apply in a schema and cannot be written in data. It does bear
on option 3 below: extending the sugar into data position would have to reach a record the spec argues must
stay braced.

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
   and gives the application an identity. If it is the intended answer, §8.2 is the place to say so.
3. **Extend the sugar to data position.** Real ergonomics, and a real cost: `<` becomes meaningful in data,
   at exactly one slot type, decided by the governing schema. Probably not worth it — noted for completeness
   rather than recommended.

One concrete diagnostic point regardless of which: a bad argument in the **alias** form is reported against
the entry the template materialised (`'array_no_such_eb84587b' element_type has an unresolved reference
'no_such'`), where the inline form names the operation. If (2) is the recommended spelling, that message is
the one to improve — the author wrote `order_page => page<no_such>` and is shown a synthetic name they have
never seen.
