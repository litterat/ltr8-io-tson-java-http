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

## 3. A `SCHEMA_UNAVAILABLE` diagnostic drops the `Reason` that says whose fault it is

**Hit:** choosing a status for a request body whose schema could not be fetched. `TsonSchemaFetchException`
carries a `Reason`, and this project maps all five: `NOT_PERMITTED` and `NOT_FOUND` are the document's fault
(400 — it named a schema this server will not load, or one nothing serves), while `TIMEOUT` is 504 and
`TRANSPORT`/`TOO_LARGE` are 502, this server's dependency failing while the request was perfectly good. The
retry advice differs, which is the whole reason for splitting them.

**`Diagnostic.ofSchemaUnavailable` keeps none of it.** It is built from that exception and from nothing else —
which is what makes the code trustworthy — but stores only the message, with `expected` and `actual` set to
`""`. So a consumer that reads through a collecting receiver gets "nobody would supply this schema" and no way
to learn which of the five it was.

**That is the common path, not an edge.** Every read through `TsonHttpCodec` collects, so in this project a
schema-fetch failure now essentially always arrives as a diagnostic and essentially never as the exception —
the carefully split mapping in `TsonHttpException.from` is reachable only from startup (`preload`,
`prepareToRead`). Two channels for one failure now answer differently: `NOT_PERMITTED` is a 400 thrown and a
502 collected.

**Change:** carry the reason on the diagnostic. `expected` is already free and already spare-typed, so
`expected = reason.name()` would cost nothing and need no new field — though a typed accessor would be better
if `Diagnostic` is willing to grow one. Either way the point is that the distinction survives the receiver,
since it is not recoverable from the message and deliberately should not be parsed out of one.

**Workaround in place:** round to 502 for the whole class, and say why in the Javadoc. It rounds away from the
client on purpose — given one status for both, the wrong one to pick is the one that tells a client with a
good document to go and fix it because a host did not answer. But it means a reference no allow-list permits,
which really is the caller's mistake, is reported as this server's dependency failing.

**Priority: medium** — it is a wrong status on a live path rather than an ergonomic edge, and the fix is one
field.

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
