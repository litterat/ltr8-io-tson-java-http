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

## 1. A JSON document cannot name its own binding

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

## 2. An unresolved name a bundled library declares does not say which library

**Hit:** a schema naming `ipv4` without importing `net.tn` gets *"'server' field 'addr' has an unresolved reference
'ipv4'"*. It reads like a typo, and the fix is an import. Revision 38 moved `ipv4`, `ipv6`, `cidr4`, `cidr6` and
`mac` out of core into `net.tn`, and Revision 37 trimmed core before it, so every such move turns a working schema
into this message. This project recorded the core trim as a trap in `CLAUDE.md` for exactly that reason.

**The change:** the bundled libraries are fixed and known (`TsonBundledSchemas`), so where an unresolved reference
is a name one of them declares, the diagnostic can say so — *"… 'ipv4', which `https://tson.io/2026/38/m/net.tn`
declares; import it"* — as `expected`, or in the message. No resolution changes; a schema still has to import what
it uses. It turns a move between libraries from a debugging session into a one-line fix.

**Workaround in place:** none needed here — nothing in this project used the moved types. Pinned by
`UpstreamGapsTest.anUnresolvedNameABundledLibraryDeclaresDoesNotNameTheLibrary`, which fails the day the message
names the library.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump.


### To file: namespaces after tson-java PR #753 — what this project still needs

**Sections:** [TSON-SCHEMA] §4.1 (`namespace`, in place of `data`), §5.8 (composition), §8.1 (the constructor type
slots); [TSON-DATA] §2.6 (map keys are values). Upstream tracks the design as its `SPEC-FEEDBACK.md` "A namespace is a
constructor whose instances are maps" and "A reference to a value is the kind's sibling", and the work in
`design/meta-layer-namespaces.md`; this entry is the consumer evidence for what those leave open.

**Where it stands.** PR #753 landed in Revision 38: `namespace` is a constructor under `top`, a namespace
type composes it, a value of one is a map (`Namespace`), the schema map and a record's fields are two of them, and
`data` is retired. A `ref<T>` slot reaches a value by a qualified name through namespace keys and record values'
fields (`orders.get`, `i.orders.methods.place`, PR #758), and a `kind<C>` slot holds a type's name; both are judged by
the slot's declared type, at any depth and under any host. A reference is declared only in a kernel-governed schema
(PR #760), since only there can an entry hold a value. A namespace type declares only its facets, so a construct with
fields beside its map is a record held under a key. `meta-service.tn`, the shipped service-description layer, runs
on all of it.

**Kind:** consumer evidence. Each item is something the meta-service experiment does in its reader today
(`Routes`) because the schema cannot say it, with the probe that changes state when it can.

1. **`&` over namespace types, so `extends` and `implements` are structural.** A namespace value has no fields, so
   the experiment's `interface` and `api` are records under keys of `interfaces` and `apis`, carrying `extends?:
   [ref<interface>]` and `implements?: [ref<interface>]`: references the resolver judges, but not compositions, so an
   inherited method is named where it is declared, `Routes` walks `extends` and refuses a cycle, and a binding spells
   `orders.orders.methods.place_order`. With `&` merging member sets, `orders => reads & { … }` would have `reads`'
   methods and its own, a collision refused as between two composed records' fields, and the extra level and the
   `methods` segment would go (`orders.place_order`). An inherited member should not be copied: a method composed
   into several interfaces stays one value with one identity.
2. **Data keys.** An api is keyed by path, then verb, and a namespace's `key_type` is an identifier family, so a
   text-keyed namespace is refused (`NameRoleProbe.aNamespaceCannotBeKeyedByText`) and the experiment's `resources`
   is a plain map that no qualified name enters. A key that is not a name also needs a reach other than `.`: the
   pointer form the "formats" entry below stages, `/orders/POST`, where a compound `[path, verb]` key has no pointer
   token today (`UpstreamGapsTest.aCompoundKeyHasNoPointerTokenAndTheEncodingsDisagree`).

**Out of scope, deliberately:** the `(schema identity, root type name)` pair this project reassembles at every call
site (`describing`, `readObjectAs`, the `TSON-Schema` header plus a route-supplied type). It exists because a data
document cannot hold a reference to a type, and nothing above changes that.

**Priority:** item 1 first. It is the one the experiment's shape is waiting on.

### To file: formats this project needs and the vocabulary cannot state — two types, by strength of evidence

**Sections:** [TSON-SCHEMA] §2.2.3 (a name an import binds is reserved), §4.2 and §5.5 (atom families and their
equality), §7.4 (text member sets), §9 (core's contents); [TSON-DATA] §2.2.1 (canonical identity), §2.6 (map key
identity), §5.5 (text and network atoms); the meta-kernel's `text_type`, `uri_type` and `atom_specification`. Builds
on #13/#14 (`uri`, `uri_reference`, `iri`), #15 *core should hold only what a schema cannot do without* and #19
*`normalization` should be `text_type`'s … and offer `NFKC_CASEFOLD`*, numbered as they stood on the Revision 37
register; all three were adopted in Revision 37.

**Kind:** proposals, one per type, each from a place this project produces or reads a value its schemas can only
type as `text`. Ordered by evidence: the first two are values tson-java *itself* emits.

**The question each one has to answer.** An atom earns its place over `!text ^ { pattern: … }` in one of two ways:
a grammar a pattern cannot state, or an **equality** that is not `text`'s. Equality is the one that bites. TSON
compares by the type's own equality in four places: a pin (§5.2, against the decoded value), enum and member sets
(§7.4), map-key identity ([TSON-DATA] §2.6), and set uniqueness. Under `text`, two spellings of one value are two
keys. Since #19 a text family can state a **whole-value** fold — `normalization: NFKC_CASEFOLD` holds the value
folded — so what is left to an atom is an equality over *part* of a value, or one that drops parts. And under #15,
each *core instance* reserves its name in every schema importing core, so placement is decided per type: a
constructor in `meta` reserves nothing a schema declares, while a core instance does.

**1. `json_pointer` (RFC 6901) — core instance.** `Diagnostic`'s own contract makes `path` and `schemaPointer` RFC 6901
JSON Pointers (`/orders/3/total`). `problem.tn`'s `diagnostic` can type them only as `text`, and so can tson-cli's
`diagnostics.tn`. A pattern states the grammar (`""` or `/`-prefixed segments, `~` only as `~0`/`~1`), but an atom
gives a host value of decoded segments, which is what a consumer walking a pointer wants. Equality is `text`'s, since
the escape is a bijection. Collision risk is low. This one comes first because the library emits the values.

**It needs a TSON profile of RFC 6901, and that is the larger half.** RFC 6901 assumes string keys; a TSON map key
is any value ([TSON-DATA] §2.6), and the series does not say what a non-text key's reference token is. What
tson-java does, measured in this project's `UpstreamGapsTest`:

| Key as written | TSON pointer | JSON pointer |
|---|---|---|
| `0x10`, `16`, `1_6` (one key) | `/counts/0x10`, `/counts/16`, `/counts/1_6` | the same, from `"0x10"` and `"16"` |
| a compound key, `{x: 1 y: 2}` | `/grid/?` | `/grid/0/1`, into the array of pairs |
| `"a/b~c"` | `/tags/a~1b~0c` | `/tags/a~1b~0c` |

- **A scalar key's token is its spelling,** so one key has as many pointers as spellings. The encodings agree, a
  JSON member name being able to spell the same forms, so this is not a parity failure. It does mean two reports
  about one entry need not compare equal, and a consumer correlating diagnostics by pointer misses the match.
- **A compound key has no token:** `?` is `MapAbstractReader.keySegmentFor`'s fallback, which is not a location,
  and JSON reports a real one into its encoding. The same entry and the same failure get two pointers, which is a
  parity failure.
- **Escaping is right in both.**

Not a new path syntax. JSONPath (RFC 9535) is the nearest candidate and fits neither need: it is a query language,
its Normalized Paths (§2.7) are bracket-only, and its dot shorthand admits no `-`, so a kebab-case identifier cannot
use it. The reserved `.` of a qualified name stays a separate thing: a name resolved through the namespace, where a
pointer is a location in a document as written. What is needed is a short profile in [TSON-DATA], since both
implementations must render it alike:

- **A non-text key's reference token is its canonical text** — the token the writer produces for the decoded value
  — not the spelling read. `0x10` and `16` are then both `/counts/16`.
- **A compound key either gets a defined canonical serialisation as its token,** escaped per RFC 6901, **or is
  declared unaddressable**, with its entry's pointer stopping at the map. The second is honest and small. The first
  is what makes a compound-keyed entry reportable. Either beats `?`, which reads as a key that is literally `?`.
- **Tuple and set elements are indexed in written order**, which output already preserves (§7.5), so an index is
  stable across a round trip.
- **The JSON encoding's pointer is the same pointer,** however [TSON-JSON] carries the map. For a compound key that
  means agreeing with whichever of the two rules above is chosen, not reporting into the array of pairs.

The `json_pointer` atom then pins RFC 6901 *and* this profile, and the two `UpstreamGapsTest` pins
(`aMapKeysPointerTokenIsItsSpellingNotItsValue`, `aCompoundKeyHasNoPointerTokenAndTheEncodingsDisagree`) fail the
day it lands.

**2. A schema reference with §2.2.1's equality — core.** [TSON-DATA] §2.2.1 defines a reference's canonical identity
(lowercase host plus path; scheme and `?sha256=` pin ignored) and a canonical-form rule for an identifying URI (no
port, userinfo or fragment). It is the series' own format, and the type system cannot state it:

- `diagnostic.schema_id` carries the canonical form, `example.com/people.tn`, which is not a URI at all.
- A set or map of references cannot dedupe `https://x/a.tn` against `http://x/a.tn?sha256=…`.

Proposed: a reference family whose values are absolute, identifying references held to the canonical-form rule, and
whose **equality is canonical identity**, with an instance for the canonical spelling beside it. It is not a
normalization: the host folds, the path keeps its case, and the scheme and the pin are dropped rather than folded.
tson-java's `CanonicalIdentity` is the existing implementation.

**Deliberately not proposed:** `status_code`, `http_verb`, `header_name` and a URI template as types in core or
`net.tn`. They are HTTP vocabulary rather than formats, and exactly the names #15 shows converted schemas declare for
themselves, so they belong with what uses them: here, `meta-service.tn` (below). A URI
template in particular builds a URI rather than naming a host, so it is not `net.tn`'s, and its uses outside
describing an API — HAL links, OpenAPI and AsyncAPI paths, JSON Hyper-Schema — are all that same use. **Raise it
again** if a consumer outside API description needs RFC 6570, or a description needs levels 3–4; the shape then is a
constructor in `meta` beside `uri_type`, which reserves no name, with no instance in core or `net.tn`.

**What this project does meanwhile:**

- `problem.tn` types RFC 9457's `type` and `instance` as `uri_reference`, since both are URI references by that
  RFC (§3.1.1, §3.1.5) and `TsonProblem.at` is typically handed a request path. Pinned by
  `TsonProblemSchemaTest.aRelativeTypeAndInstanceAreValid`.
- `diagnostic.path`, `schema_pointer` and `schema_id` stay `text` until 1 and 2 exist.
- The HTTP vocabulary lives in `meta-service.tn`, the meta layer that uses it — `http_verb`, `status_code`,
  `header_name`, `media_type` and `path_template` (RFC 6570 levels 1–2, so `{+var}` is expressible) — since a meta
  layer owns the vocabulary its constructors use and an import stands at the importer's governing level.
  `TsonApiDescription` places each endpoint's request record over its path, which no pattern can. `problem.status`
  stays `int32`.

**Priority:** medium-high, since the library emits values its own schemas cannot describe.

---

**Already filed.** Five entries staged here have gone to `SPEC-FEEDBACK.md` and left this file: §8.2's policy has no
artifact, which Revision 37 closed with the bundled `policy.tn`; naming a schema for a document that cannot carry
`!!schema`; no shorthand for a template application at a `type_ref` slot in data; a namespace should be a value (all
four on 2026-09-01), the last since landed in Revision 38 as the `namespace` constructor; and `identifier` as a text
family with `enum` stating its type, adopted in Revision 37. From this project's review, tson-java also filed and
built for Revision 38: `hostname` and `host`, a non-ASCII identity's host as one identity in either label form,
`media_type` in `net.tn`, an import standing at the importer's governing level (PR #755), a reference delivered
through a record value's field (PR #758), and a reference declared only in a kernel-governed schema (PR #760). A
reference in a data document is a Revision 39 question, with the one-level work (`experiments/one-level/README.md`,
"The reference atom"). Prose in this repo names each by its subject, not its number. A new finding goes here in the
same shape: a `### To file:` heading naming the spec sections, the gap, what this project does meanwhile, and a
priority.