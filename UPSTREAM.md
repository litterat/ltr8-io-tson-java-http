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

## 2. A templated `~data &` constructor declares, but its application cannot be named

**Hit:** the CRUD-family payoff of an API description. `fetch => <T> !operation { method: GET  path: "/x"
responses: [ { status: 200  body: T  description: "found" } ] }` — one declaration standing for every
fetch-by-id endpoint — now **declares and resolves**, where it used to be a parse error. The remaining gap is
one stage later: nothing may apply it.

`getOrder => fetch<order>` materialises the application correctly — the synthetic entry's name records the
substitution — and is then refused:

> `'fetch<order>' names 'operation_GET_/x_200_order_found_1f8d998a', which is built with 'operation' and
> describes something other than a data value — it is declared by this schema but is not a type, so nothing
> can be typed by it`

**The refusal is right on its own terms**, which is what makes this a design question rather than a bug.
`name => application` is an alias, an alias names a type, and §4.1 makes naming a `kind: DATA` entry where a
type is expected an error. `@alias` does not change it. So a templated data constructor is currently
*declarable and unusable*: the template resolves, and there is no spelling that binds a name to the data entry
an application of it materialises.

**Change:** a way to name the application of a templated `~data &` constructor. Whether that is the alias
position learning that a `data` entry may be named by one, or a separate spelling, is upstream's call — the
constraint is only that the resulting name is what a consumer sees, since a generated name like the one above
is no use to anything that looks an operation up by name (`TsonApiCoverage.serving`).

**Workaround in place:** write each operation out untemplated, which is what this project's description does.
That costs a full record per endpoint where the template would have cost an application, and it is the cost
the `data` base kind otherwise removes.

**Priority: low** — the description is written once and read often, so verbosity there is cheap. Worth
recording because the parse-level half has just closed, and the half left is small enough to look already
done. Pinned at both stages by
`UpstreamGapsTest.aTemplatedDataConstructorDeclaresButItsApplicationCannotBeNamed`: asserting only the throw
would go on passing if the declaration regressed to a parse error, which is a different gap wearing the same
red.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump. Revision 33 closed the two that were
cited here (#55, transitive imports, now §2.2.3; #20, `.tn` versus `.tn1`, now §7.1) and left both of the
below untouched.

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

**Unchanged by Revision 33.** §7.1 was rewritten (the `.tn`/`.tn1` extension rule) and §6 sharpened, and
neither gained an out-of-band channel. Still worth filing, and the second reason above is the stronger one.

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
a comma, or both`), the `<` never being data syntax. Measured, and both spellings are asserted by
`UpstreamGapsTest.aTemplateApplicationAtATypeRefSlotInDataNeedsTheBracedForm` — the braced record resolves,
the sugar does not parse.

**Unchanged by Revision 33**, which rewrote §8.2 around synthetic entries and left §8.1's positional-form
paragraph saying exactly what it said before. Still worth filing.

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
