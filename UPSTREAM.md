# Upstream changes wanted in `ltr8-io-tson-java`

That repo is **hands-off** for now. Anything this project would like changed there is written up here
first, and only landed on the user's say-so. Each item states what this project hits, why the workaround
is unsatisfying, and what the change would be.

**This register holds what is open, and nothing else.** An item whose change landed upstream is deleted, and
so is one whose answer was a decision — rejected, withdrawn, or built here instead. The reasoning that got
there is in git history; leaving it inline turns a to-do list into an archive nobody reads to the end of.

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

**Hit:** a JSON body that names its schemas and root type in band. [TSON-JSON] §3.4 gives a JSON document two
routes to its binding — out of band, supplied by the application, and in band, a root annotation object led by
`$import` (a list of schema references) and `$type` — and §3.5 requires a `TSON-Schema` header to agree with an
in-band binding as sets of canonical identities. tson-java's JSON reader builds the first route only, and declines a
root led by `$import` as `NOT_IMPLEMENTED`. So a body that names its binding twice, in agreement, is a 501 here,
where the spec calls it valid.

**Already on upstream's list** — `BACKLOG.md`, "A JSON document has no in-band way to name its schemas". Recorded
here because this project hits it, and because what it needs is specific: an entry that reads the in-band
binding (or reports its absence) before the body, so `TsonHttpCodec` can check §3.5's agreement against the
header — the JSON counterpart of what `TsonDocumentPeek` gives a TSON body.

**Workaround in place:** none needed for the common case — a JSON client sends the header and a bare value,
which is §3.4's "expected production route". Pinned by `UpstreamGapsTest.aJsonDocumentsInBandBindingIsRefused`.

---

## 2. The JSON reader binds nothing under the bind context `Tson` composes

**Hit:** `Json` built with `Tson.dataBindContext()`, which is what reading both encodings under one configuration
means. That context answers the kernel's own vocabulary with the schema model's classes, and the JSON bind registry,
compiling a closure, checks those records against their classes and refuses the first it meets: *"'net.iri_type' and
io.ltr8.tson.schema.net.IriType do not agree: field 'allow_relative' states 'true', which boolean cannot hold: No
value present; field 'allow_fragment' states 'true', which boolean cannot hold: No value present"*. Every closure
holds the kernel, so no JSON body binds at all, while the TSON registry binds the same value
under the same context. Given the context the application configured, the JSON reader binds.

**The change:** the JSON bind registry agreeing with the TSON one about the kernel's records, or leaving the kernel's
vocabulary to the schema model rather than checking it against a document's closure.

**Workaround in place:** `TsonHttpCodec.acceptingJson` hands the JSON reader the composed context with the kernel's
vocabulary left unbound (`withoutKernelVocabulary`), which works because a JSON body binds application names only.
Pinned by `UpstreamGapsTest.jsonBindingUnderTheComposedBindContextRefusesTheKernel`; when it flips, the filter goes.

---

## 3. An unresolved name a bundled document declares does not say which document

**Hit:** a schema naming `ipv4` while importing `temporal.tn` and not `net.tn` gets *"'server' field 'addr' has an
unresolved reference 'ipv4'"*. It reads like a typo, and the fix is an import. The bundled libraries each import the
kernel alone, so a schema sees exactly the libraries it imports, and every name a library moves or a schema forgets
surfaces as this message. The sharpest case is a schema importing nothing, which is valid and sees only its own
entries: `a => { b: text  c: integer }` there gets *"'a' field 'b' has an unresolved reference 'text'"*, one
diagnostic for the record's first such field, with no word that `text` is `meta.tn`'s.

**The change:** the bundled documents are fixed and known (`TsonBundledSchemas`), so where an unresolved reference
is a name one of them declares, the diagnostic can say so — *"… 'ipv4', which `https://tson.io/2026/39/m/net.tn`
declares; import it, or core.tn"* — as `expected`, or in the message. No resolution changes; a schema still has to
import what it uses.

**Workaround in place:** none needed here — every schema this project owns imports what it names. Pinned by
`UpstreamGapsTest.anUnresolvedNameABundledLibraryDeclaresDoesNotNameTheLibrary`, which fails the day the message
names the library, and the import-nothing case by `UpstreamGapsTest.aSchemaImportingNothingSeesNoKernelName`.

---

## 4. A reference inside an annotation's value is not judged

**Hit:** `meta-service.tn` declares `binds => @meta.annotation [meta.ref<protocol>]` and `not_bound =>
@meta.annotation [exemption]`, the `exemption` record holding `exchange: meta.ref<exchange>`. The resolver checks the
annotation value's shape (`@binds:42` is refused) but no name in it: a protocol nothing declares and an exchange key
the protocol does not hold both load clean, and each arrives as a `TypeRef` nobody resolved.

**The change:** a `meta.ref<T>` in an annotation value judged as one in a field value is, if the spec question below
is answered that way; at least, its name held to the qualified-name grammar.

**Workaround in place:** `TsonApiDescription` checks both names when it reads a description
(`TsonApiDescriptionTest`). Pinned by `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`, whose clean
load flips when it is fixed.

---

## 5. A template's bare name as a field type resolves silently

**Hit:** found migrating this project's schemas. `x => { s: tmpl }`, with `tmpl` a template, loads with no
diagnostic, declared locally or brought by an import, although a template is not a type until it is applied and the
field has no shape to read a value at. The first sign is a read: every value at the field is refused with *"no schema
in this closure declares a subtype of 'tmpl', and it is abstract, so nothing can stand here"*, which blames the
closure for a slip in the schema. A missing argument list is an easy slip in a description applying one template at
several endpoints.

**The change:** refuse the name where a type belongs, naming the template's parameters — *"'tmpl' is a template of
one parameter; apply it"*.

**Workaround in place:** none; nothing here writes the form. Pinned by
`UpstreamGapsTest.aTemplatesBareNameAsAFieldTypeResolvesSilently`.

---

## 6. `Tson.validate` on a schema document blames Class 1

**Hit:** `Tson.validate` handed a document whose root is tagged `!schema` reports one `VALIDATION_ERROR`, *"this is a
TSON schema document (its root is tagged !schema); a Class 1 (data-format-only) processor does not support schema
documents"*. Refusing is right — `validate` reads data, and `validateSchema` reads a schema — but the
`Tson` is schema-aware, so the message names the wrong cause, and the validator demo shows it to whoever pasted a
schema into the data box.

**The change:** the message saying the document is a schema and naming `validateSchema`.

**Workaround in place:** none. Pinned by `UpstreamGapsTest.validateOnASchemaDocumentBlamesClassOne`.

---

## 7. The JSON reader states one schema, where a `TSON-Schema` List states several

**Hit:** a JSON body sent with a `TSON-Schema` List of two members, its version and a schema it also uses. [TSON-JSON]
§3.5 makes the field a List because a binding is a set, read as one namespace (§3.4). tson-java's TSON readers read
under several (`withImports`); `JsonObjectReader` and `JsonTreeReader` have `withSchema` alone, so a JSON body is read
under the one schema the route states, and a type only the other member declares cannot stand in it.

**The change:** `withImports(List<String>)` on the JSON readers, as on the TSON ones.

**Workaround in place:** `TsonSchemaVersions.Routed.readObjectAs` reads a JSON body against its version alone; the
namespace is narrower than the one the field claims, never wider, so nothing is admitted that the sender did not
name. Pinned by `UpstreamGapsTest.theJsonReadersStateOneSchema`.

---

## 8. A component narrower than its field's value space binds, and fails at the read

**Hit:** found migrating `TsonDeployment.Policy.Limits` to `policy.tn`'s `policy.limit`, an `int64`. Strict binding
accepts an `int` record component against an `int64` field: the bind registry prepares the schema, so `prepareToRead`
passes, and a valid document stating a value past `int` range fails at the read with a bare `ArithmeticException:
BigInteger out of int range`. It is no `ReadException` and carries no `Diagnostic.Code`, so the status policy has
nothing to classify and a document with nothing wrong with it is answered with a 500. That is the failure
`prepareToRead` exists to move to startup.

**The change:** a component whose Java type cannot hold its field's value space is a `BindMismatchException` when the
schema is prepared, naming the field, its type and the component, as an undeclared component already is.

**Workaround in place:** none needed now; this project's exposure is gone, because every `Limits` component is a
`long` and `toLimitsPolicy` caps a count at the library's `int`. Pinned by
`UpstreamGapsTest.aComponentNarrowerThanItsFieldBindsAndFailsAtTheRead`.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers each time a
revision closes, so a finding here names spec sections, never a register number. A new finding goes here in the same
shape: a `### To file:` heading, the spec sections, the gap, what this project does meanwhile, and a priority.

### To file: every root name of a closure is reserved, so a vocabulary collides with a library it never named

**Sections:** [TSON-SCHEMA] §2.2.3 (two documents of a closure declaring one name collide), §3.3 (one namespace per
schema), §9 (the bundled documents' roots), as the Revision 39 proposal states them.

**Kind:** the cost of a design decision, with evidence from a consumer. A schema's namespace is its own entries and
its whole import closure's, and a name two documents of the closure declare is a collision wherever it is declared,
whether or not anything names it. The bundled documents keep their roots to the vocabulary an author writes, so a
schema importing `core.tn` reserves `text`, `int64`, `schema`, `date`, `time`, `period`, `uuid`, `email`, `host`,
`hostname`, `uri`, `uri_reference`, `media_type`, `title`, `comment` and the rest, and the map names `meta`, `atom`,
`numeric`, `unicode`, `net`, `binary` and `temporal`; one importing `policy.tn` reserves `policy` as well.

**Where it bit.** `meta-service.tn` declared its own `media_type`. Revision 39's `net.tn` declares one at its root,
and a service description imports `meta-service.tn` and `core.tn`, so every description failed to load on a name it
never wrote, though neither import is wrong and each document resolves on its own. `meta-service.tn` dropped its own
and imports `net.tn`'s, which happens to fit; a vocabulary whose `media_type` meant something else would have had to
rename, and so break every document written against it.

**Two consequences beyond this one.** A bundled library adding a root name is a breaking change for every published
vocabulary that declares the name and every schema importing both — the `json_pointer` proposal below is one. And an
application importing core cannot name its own type `host`, `date`, `uri` or `period`, plausible names in a domain
schema, without a diagnostic that reads as its own mistake.

**A suggestion:** a collision **on use**, not on declaration, as Java's on-demand imports have it. Each document's
own references already resolve in its own closure, so a vocabulary's internal uses are unaffected; an importer that
names a doubly-declared name is told both declaring identities, and one that does not is never troubled. The
alternatives are worse: an importer's entry shadowing an import hides a meaning silently, and a qualified import adds
syntax for a problem the resolver can settle.

**What this project does meanwhile:** `meta-service.tn` takes `media_type` from `net.tn`, and no schema here declares
a bundled root name.

**Priority:** medium-high: every vocabulary published against Revision 39 is exposed to every later root addition.

### To file: an application at a kind slot in a value needs an alias per application

**Sections:** [TSON-SCHEMA] §4.1 (value entries), §5.10 (templates and their applications), §8.2 (what a resolver
mints).

**Kind:** request for a later revision. A value body mints nothing, so at a kind-typed slot inside an entry applying
a namespace type neither the braced record nor `page<order>` is admitted; both are refused naming the alias spelling,
and `order_page => page<order>` is the one way to write it. A description applying one template at several endpoints
declares an alias per application, each a name that exists to be referenced once and that reserves a root name in
every importer (the reserved-roots item above). A data document's kind slot already admits an application the closure
closed, matched by structural identity; a value body could take the same rule, or mint as a type body does.

**What this project does meanwhile:** aliases. Pinned by
`UpstreamGapsTest.aTemplateApplicationAtAKindSlotInAValueIsNamedThroughAnAlias`.

**Priority:** low-medium: verbose rather than blocking.

### To file: a format this project needs and the vocabulary cannot state — `json_pointer`

**Sections:** [TSON-SCHEMA] §2.2.3 (a name an import binds is reserved), §4.2 and §5.5 (atom families and their
equality), §9 (the bundled libraries' contents); [TSON-DATA] §2.6 (map key identity), §5.5 (text atoms); `meta.tn`'s
`unicode.text_type` and `atom.specification`. Builds on the rules that core holds only what a schema cannot do
without, and that a text family may state a whole-value fold (`normalization: NFKC_CASEFOLD`).

**Kind:** proposal, from a place this project produces a value its schemas can only type as `text`, and one
tson-java *itself* emits.

**The question it has to answer.** An atom earns its place over `!text ^ { pattern: … }` in one of two ways:
a grammar a pattern cannot state, or an **equality** that is not `text`'s. Equality is the one that bites. TSON
compares by the type's own equality in four places: a pin (§5.2, against the decoded value), enum and member sets
(§7.4), map-key identity ([TSON-DATA] §2.6), and set uniqueness. Under `text`, two spellings of one value are two
keys. A text family can state a **whole-value** fold, so what is left to an atom is an equality over *part* of a
value, or one that drops parts. And a bundled library's root entry reserves its name in every importer, where a
constructor under the library's map reserves nothing, so placement is decided per type.

**1. `json_pointer` (RFC 6901) — a root entry of a bundled library.** `Diagnostic`'s own contract makes `path` and
`schemaPointer` RFC 6901 JSON Pointers (`/orders/3/total`). `problem.tn`'s `diagnostic` can type them only as `text`,
and so can tson-cli's `diagnostics.tn`. A pattern states the grammar (`""` or `/`-prefixed segments, `~` only as
`~0`/`~1`), but an atom gives a host value of decoded segments, which is what a consumer walking a pointer wants.
Equality is `text`'s, since the escape is a bijection. `net.tn`, beside `uri`, is the nearest home; the name it
reserves is the cost the reserved-roots item above describes. This one comes first because the library emits the
values.

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

**Deliberately not proposed:** `status_code`, `http_verb`, `header_name` and a URI template as bundled types. They are
HTTP vocabulary rather than formats, and exactly the names converted schemas declare for themselves, so they belong
with what uses them: here, `meta-service.tn` (below). A URI template in particular builds a URI rather than naming a
host, so it is not `net.tn`'s, and its uses outside describing an API — HAL links, OpenAPI and AsyncAPI paths, JSON
Hyper-Schema — are all that same use. **Raise it again** if a consumer outside API description needs RFC 6570, or a
description needs levels 3–4; the shape then is a constructor under `net.tn`'s `net` map beside `net.uri_type`, which
reserves no root name.

**What this project does meanwhile:**

- `problem.tn` types RFC 9457's `type` and `instance` as `uri_reference`, since both are URI references by that
  RFC (§3.1.1, §3.1.5) and `TsonProblem.at` is typically handed a request path. Pinned by
  `TsonProblemSchemaTest.aRelativeTypeAndInstanceAreValid`.
- `diagnostic.path` and `schema_pointer` stay `text` until 1 exists.
- The HTTP vocabulary lives in `meta-service.tn`, which uses it — `http_verb`, `status_code`, `header_name` and
  `path_template` (RFC 6570 levels 1–2, so `{+var}` is expressible) — and `media_type` is `net.tn`'s.
  `TsonApiDescription` places each endpoint's request record over its path, which no pattern can. `problem.status`
  stays `int32`.

**Priority:** medium-high, since the library emits values its own schemas cannot describe.

### To file: whether a `ref<T>` inside an annotation's value is resolved

**Sections:** [TSON-SCHEMA] §5.12 (a name at a `ref<T>` slot resolved in the schema's namespace), §6 (annotations).

**Kind:** ambiguity. §6 says an annotation in a schema document is resolved and validated against its declared type,
and also that annotation values are data values. If the first governs, a `ref<T>` in the annotation's type reaches
§5.12 and a name nothing declares is a schema error; if the second, the name is an inert token, as a type name in any
data value is. The spec does not say which, and the answer decides whether a vocabulary can state a relation in an
annotation and have it checked. tson-java takes the second reading (item 4, "A reference inside an annotation's value
is not judged").

**What this project does meanwhile:** `@binds` and an exemption's `exchange` keep their `meta.ref<…>` typing to say
what they mean, and `TsonApiDescription` checks the names.

**Priority:** medium: it decides where a vocabulary's relation checks live.

### To file: a namespace value with declared fields beside its keys

**Sections:** [TSON-SCHEMA] §4.1 (namespace types), §6 (annotations); `meta.tn`'s `meta.namespace`.

**Kind:** request for a later revision. A value of a namespace type is the map alone, and a namespace type declaring
a field beside its facets is refused (*"unknown field 'extends' on 'meta.namespace'"*,
`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`). So a fact about a namespace as a whole has nowhere to sit.
`meta-service.tn`'s http projection carries its claims, `binds` and `not_bound`, as annotations on its entry, where
the references in them go unjudged (above). The alternative, a record held one key down, doubles every qualified
name. A namespace value admitting declared fields beside its keys would make `@binds` a field, judged like any other.

**Related:** a namespace is keyed by an identifier family (`UpstreamGapsTest.aNamespaceKeyTypeMustBeAnIdentifierFamily`),
so `path_template` is an `identifier_type` widened to a path's characters, and §8.2's name hygiene reaches a path key.
A revisit should keep that, and leave a text-keyed namespace and `&` over namespace values unproposed, composition
being over types.

**Priority:** low: the annotation form works.
