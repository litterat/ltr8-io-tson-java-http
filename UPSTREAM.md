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

## 9. A mapped class that cannot be analysed is deferred as a missing binding

**Hit:** found binding a request record that holds a dependent field (`payload: kind!`), whose component is `Object`.
Under a bind context that does not register `Object` as an atom, the class the map names cannot be analysed, and the
bind registry treats that as a missing binding: `prepareToRead` passes, and every read of the record throws
`MissingBindingException: 'typed_note' binds …TypedNote, which cannot be analysed: Failed to resolve: Unable to find a
valid data conversion for class: class java.lang.Object`, a 500 per request. Deferral is right for a type no class is
mapped to, since a schema declares types a consumer never binds; a class the map does name and the binder cannot
build is a mismatch, and the startup step is where it belongs.

**The change:** a mapped class that cannot be analysed is a `BindMismatchException` when the schema is prepared,
naming the type, the class and the component, as a disagreeing class already is.

**Workaround in place:** `TsonBindings` registers `Object` as an atom, so a dependent field binds, and
`TsonApiDescription` analyses a bound request record holding one at startup. Pinned by
`UpstreamGapsTest.aMappedClassThatCannotBeAnalysedPreparesCleanAndFailsEachRead`.

---

## 10. `RecordField` states one fact through several accessors, and one of them throws

**Hit:** reading a schema this project did not write, at startup, to place a request's fields over HTTP
(`RequestPlacement`) and to check what a dependent field's sender may name (`ChosenTypes`). A record field's type is
one fact, and `RecordField` answers it four ways: `type()` returns the sealed `Reference` (a `TypeRef` or a
`SiblingRef`); `typeRef()` returns a `TypeRef` and throws `IllegalStateException` on a `SiblingRef`; `namedType()`
returns an `Optional<TypeRef>`; and `sibling()` the sibling's field name as an `Optional<String>`. `typeRef()` is the one
a consumer reaches for, since every other reference slot of the model is typed `TypeRef`, and called on a record that
holds a dependent field it turns a property of a valid schema into an unchecked exception: at this project's startup a
description refused with a stack trace rather than a message, and on any path that walks a schema per request, a 500.
`RecordTemplateField` has the same split one level down: its `type` is an `Optional<Reference>` beside `type_param`,
`closed()` throws `IllegalStateException` while a parameter remains, and `RecordField.typeRef(Reference)` is the
throwing accessor's static form for both.

**The change:** the sealed `Reference` as the one surface, read with a `switch`, and `typeRef()` and `namedType()`
gone; short of that, `typeRef()` returning `Optional<TypeRef>`, so that no accessor throws on a schema that loaded. The
same for the record template field.

**Workaround in place:** `RequestPlacement` and `ChosenTypes` match on `Reference` (`instanceof SiblingRef`, `instanceof
TypeRef`). `ChosenTypes` calls `typeRef()` once, on a dependent field's sibling, which the linker guarantees names a
type: a sibling that is itself a dependent field is a schema error. Pinned by
`UpstreamGapsTest.aDependentFieldsTypeRefThrowsWhereItsTypeIsASiblingRef`.

---

## 11. What a dependent field's sender may name, and whether a name binds, are reached through casts and catches

**Hit:** checking at startup that every type a sender may name at a dependent field binds (`ChosenTypes`). Nothing
answers "the types a value at this field may be read against". The answer is assembled: take the compiled schema's
entry for the sibling's type (`compiled.schema().entries()`), follow its `Alias` hops, cast to `ReferenceBody` and call
`memberNames()`; a pinned sibling and a discriminator, which narrow that set, are worked out again by hand from
`FieldRole.FIXED` pins and `chain().subtypes()`. Two yes-or-no questions are answered only by exception. "Is this name
the kernel's vocabulary" is `SchemaModelNameBinder.INSTANCE.resolve(name)` and `catch (DataBindException)`, the set it
consults (`Vocabulary.NAMES`) being private. "Does this type bind" is `DataBindContext.getDescriptor(name)` and the
same catch, which cannot tell a name no class is mapped to from a mapped class that cannot be analysed without asking
`nameBinder().resolve(name)` the same way first.

**The change:** public predicates for both (a vocabulary test on `SchemaModelNameBinder`, and a `DataBindContext`
query answering with the descriptor or the reason as a value), and an accessor on the compiled schema for the types a
dependent field admits, with a pin and a discriminator already applied: the set the reader judges a sibling against.

**Workaround in place:** `ChosenTypes` does the alias walk, the cast and both catches, and
`TsonHttpCodec.isKernelVocabulary` repeats the vocabulary catch to keep the kernel's names out of the JSON reader's bind
context (item 2). Not yet pinned in `UpstreamGapsTest`.

---

## 12. Two diagnostics name internals, and one misses the fault

**Hit:** both met writing an rpc envelope with a dependent payload, and measured by `DependentEnvelopeProbe`.

- **A minted name where the author wrote a reference.** The envelope
  `by_exchange => { exchange: meta.ref<exchange>  body: exchange! }` is refused, rightly (spec feedback below, "a
  payload typed by its exchange has no spelling"), with *"'by_exchange':
  field 'body' takes its type from 'exchange', which is typed 'reference_type_exchange_false_34db4f25' -- a sibling is
  typed by a reference whose values name types, meta.type_ref, a meta.ref<C> over a constructor, or a refinement of
  either, so that every value it holds names a type to read 'body' against (§5.2)"*. The author wrote
  `meta.ref<exchange>`; the message names the entry the inline application lifted to, spelled from its arguments, the
  `allow_sibling` it pins and a hash.
- **A member list in place of the fault.** The envelope `by_type => { request: meta.type_ref  body: request! }`,
  read with `request: orders.place_order` (an exchange's qualified name), is refused `ATOM_CONSTRAINT_VIOLATION`
  at `/request` with *"'meta.type_ref': 'orders.place_order' is not a member of this kind -- expected the name of
  a type whose value IS-A 'meta.type', one of [[array_text_1_c67f2b5e; 1..], [cidr4], [cidr6], [diagnostic], …,
  {http_verb => endpoint}, {meta.field_name => header_name}]"*. The list is every member of the closure: the
  kernel's records, minted entries shown by their application (`[text; 1..]`, `{http_verb => endpoint}`), one still
  minted inside its brackets (`[array_text_1_c67f2b5e; 1..]`), and references (`meta.ref<exchange>`). The same list
  is the diagnostic's `expected` (*"one of (…)"*), so a diagnostic printed whole carries it twice. What it never says
  is the fault: the name resolves, to a value of `exchange`, and a value names no type.

**The change:** name a reference as written, as the reader already does for an application in data
(`TypeNameRefParser`: *"An application is named as written, never by the entry minted for it"*); where a name at a
type-naming slot resolves to a value entry, say so (*"'orders.place_order' names a value of 'exchange', not a type"*),
and drop or cap the member list, in the message and in `expected` alike. The minted name inside a shown form is
`ReferenceBody.shownMembers` applying its display rule one level only.

**Workaround in place:** none needed; nothing in the HTTP layer names an exchange where a type is wanted. Pinned over
a minimal protocol layer by `UpstreamGapsTest.aSiblingTypedByAnInlineValueReferenceIsRefusedUnderAMintedName` and
`UpstreamGapsTest.anExchangesNameAtATypeRefSlotIsRefusedWithTheClosureListedTwice`, which flip when the reference is
named as written, the fault is named, or `expected` stops repeating the list; `DependentEnvelopeProbe` measures both
over the orders protocol.

---

## 13. `TypeRef` names three types, and the reference types each name two

**Hit:** importing the reference types. `tson-compiler` exports `io.ltr8.tson.compiler.ast.schema` and
`io.ltr8.tson.compiler.stream`, so three public `TypeRef`s are in reach: `io.ltr8.tson.schema.meta.TypeRef`, the
model's, a sealed `Reference` over `NameRef` and `ApplicationRef`; `io.ltr8.tson.compiler.ast.schema.TypeRef`, the
AST's, the same shape; and `io.ltr8.tson.compiler.stream.TypeRef`, a stream event carrying a value's `!name` tag.
`Reference`, `SiblingRef`, `NameRef` and `ApplicationRef` each exist in both `schema.meta` and `compiler.ast.schema`.
An IDE's import offers every one, and the wrong one compiles until it meets a method only the other has. The AST
mirrors the model's names deliberately upstream (the naming proposal staged there for [TSON-SCHEMA] §12.1's
`type-ref`), so this asks that choice to be revisited for what a consumer sees.

**The change:** distinct names for the AST's forms and for the stream event (a `!name` tag is no reference), or the
two compiler packages kept from consumers that have no use for them.

**Workaround in place:** none needed; this project takes the reference types from `io.ltr8.tson.schema.meta` alone.

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

### To file: a dependent field lets the sender choose the type a value is read against

**Sections:** [TSON-SCHEMA] §5.2 (dependent fields, as proposed), §5.12 (a reference's members); [TSON-JSON] §6.1.6.

**Kind:** gap. A dependent field (`payload: kind!`) is read against the type its free sibling names, and the sibling
is typed by a reference whose members are the closure's types, so the sender of a document chooses which type of the
closure the receiver reads a value against. §5.2 says nothing about that choice: not that a receiver may hold a
sender to fewer types than the reference admits, nor what a processor that binds types to classes does when the
chosen type is one it does not bind. `meta.type_ref` admits the kernel's records and a closure's minted entries, so
`kind: meta.record` makes a binding receiver build a schema-model value inside an application's request, and `kind:
array_text_621f8dcb` is accepted though no author wrote that name. tson-java answers an unbound choice with
`MissingBindingException`, a receiver's misconfiguration, where the sender made the choice.

**A suggestion:** say that the set a sender may choose from is the receiver's to narrow, and that a chosen type the
receiver cannot read is the document's fault, not the receiver's. A processor would then refuse an unbound choice as a
diagnostic at the sibling, as it refuses a name that is no member. Minted names would be no member of a reference
whose values a sender writes.

**What this project does meanwhile:** `TsonApiDescription` refuses at startup a bound request record whose dependent
field lets a sender name a type no class binds, or one of the kernel's vocabulary (`ChosenTypes`). A free
`meta.type_ref` sibling therefore never loads in bind mode; a pinned sibling, or a discriminator over bound subtypes,
does. Pinned by `UpstreamGapsTest.aDependentFieldsSiblingNamingATypeNothingBindsIsAMissingBinding`.

**Priority:** medium-high: in a binding receiver it is a server-fault status any client can produce.

### To file: a free sibling's member order against an unordered JSON object

**Sections:** [TSON-JSON] §6.1.6 (member order), [TSON-SCHEMA] §5.2 (dependent fields, as proposed); RFC 8259 §4.

**Kind:** inconsistency with the host format. §6.1.6 requires a dependent field's free sibling to precede the
dependent member, so `{"payload": {...}, "kind": "point"}` is a validation error where RFC 8259 §4 calls an object an
unordered collection of members. A JSON client that sorts its keys, or a library that serialises a map in hash order,
writes a document the encoding refuses, and nothing in the JSON it holds tells it why. The rationale, a streamed reader
that cannot type a member before its sibling arrives, is sound, and it is the text encoding's rule ([TSON-DATA] §2.5);
in the JSON encoding it is a constraint the host format does not have.

**A recommendation:** state in §6.1.6 that a record meant for the JSON encoding pins its sibling or makes it a
discriminator, which a decoder reads as a selector before any other member, so no order is imposed beyond the one
the encoding already has. A free sibling then stays the text encoding's form.

**What this project does meanwhile:** answers the refusal as the spec has it, `VALIDATION_ERROR` at `/payload`, a 400.
Pinned by `UpstreamGapsTest.aJsonBodyWritingADependentFieldBeforeItsSiblingIsRefused`.

**Priority:** medium: it bites any JSON client that does not control its key order.

### To file: a payload typed by its exchange has no spelling

**Sections:** [TSON-SCHEMA] §5.2 (dependent fields, as proposed), §5.12 (values by name), §4.2 (a reference, and what
its `of` names).

**Kind:** gap, and a request to state a rule's reason. A request/reply envelope wants `body` read against the request
type of the exchange another field of the packet addresses. A sibling must be a reference whose values name types, so
`{ exchange: meta.ref<exchange>  body: exchange! }` is refused at load: *"… which is typed
'reference_type_exchange_false_34db4f25' -- a sibling is typed by a reference whose values name types, meta.type_ref,
a meta.ref<C> over a constructor, or a refinement of either, so that every value it holds names a type to read 'body'
against (§5.2)"* (`DependentEnvelopeProbe`). The refusal is sound, since an exchange is a value and a reference over
values names no type, but the type wanted is one hop away, in the exchange's `request` field, and nothing projects
through a value reference to reach it (`exchange!.request`, in any spelling). The form that loads, `{ request:
meta.type_ref  body: request! }`, reads each body against the type its packet names and ties that type to no
exchange: a packet may address `orders.place_order` and carry any type the closure holds. Pinning `request` per
exchange in a refinement is the template application spelled longer, so a record template closed per exchange stays
the only typed envelope.

**A suggestion:** when §5.2's text lands, state the refusal of a sibling typed by a reference over values, with its
reason; the rule runs on the branch and is written nowhere a schema author reads. And record the projection as
deliberately absent, naming the per-exchange template as the form, or propose it: a dependent field typed by a
type-naming field of the value its sibling names, which needs that value resolved when the packet is read.

**What this project does meanwhile:** the rpc experiment closes `rpc.tn`'s templates per exchange
(`orders-wire-1.tn`). HTTP needs neither form, a route's request type being fixed by the description.

**Priority:** medium for the rpc wire form; low for HTTP, which never needs it.

### To file: "sibling" and "type reference" each carry several meanings

**Sections:** [TSON-SCHEMA] §3.3.3, §3.3.5, §4.2, §5.5, §5.7, §5.10, §5.11, §8.1, §9 ("sibling"); §1.2, §5.1, §8.1,
§8.3, §12.1 ("type reference", `type_ref`, `type-ref`); §5.2 (dependent fields, as proposed); [TSON-JSON] §5.6.

**Kind:** editorial. In Part 2's draft, "sibling" means:

- a same-named entry core declares beside the kernel's: "a fresh sibling (§3.3.5)" (§4.2, and so §3.3.3, §7.3 and
  §9), "a core sibling remains a distinct type from its kernel original" (§5.10);
- a family founded by construction: "Construction creates siblings, not subtypes" (§5.5), "constructing a fresh
  sibling (`port => !integer_type { … }`, IS-A nothing)" (§8.1);
- two refinements of one source: "two sibling refinements such as `int8` and `int32`" (§5.10);
- a facet's neighbours: "`float_type`'s `allow_nan` and its three siblings" (§5.7);
- a field group's other members: "contiguous with its sibling members" (§5.11).

Revision 39 adds the field whose value types a dependent field (§5.2 as proposed), the one use that is a technical
term: it is in a facet's name (`allow_sibling`), a grammar production (`sibling-ref`) and the diagnostics.

"Type reference" means a value's `!name` tag ("Every type reference (`!name`)", §1.2); an alias declaration (`id =>
uuid ; type reference (§8.3)`, §5.1); the grammar's `type-ref`, which also covers the structural forms (§12.1); the
kernel's `meta.type_ref`, the names of types with the sibling reference withdrawn; any reference over a constructor,
which [TSON-JSON] §5.6 and the sibling's notes call a *kind*; and tson-java's `TypeRef`, which is delivered for a
reference over values too, so this project holds a `meta.ref<exchange>` value in `Exemption.exchange` and builds
`ExchangeName` from one, both typed `TypeRef` though an exchange is no type. The grammar's half is already staged
upstream, a proposal to rename the production `type-expr` and keep `type-ref` for the reference; this adds the rest.

**A suggestion:** one term for the typing field, kept for that use alone. "Selector" is this project's suggestion,
offered as such: Part 2 already calls a family's discriminating field a selector (§5.1, §5.2), and a sibling may be a
discriminator, so the term would extend rather than collide; its other use, a facet picking among a closed vocabulary
(§5.5, §5.7), would then want a word of its own. And the grammar's names, the kernel's and the implementation's lining
up: a reference over values not delivered as a `TypeRef`, and "type reference" not used for a `!` tag or an alias.

**What this project does meanwhile:** writes "sibling" for the typing field only, and "type reference" for
`meta.type_ref` and a reference over a constructor, and lives with `TypeRef` on an exchange's name.

**Priority:** low.

### To file: the Part 2 text lags the rules this project depends on

**Sections:** [TSON-SCHEMA] §1.6, §4.1, §4.2, §5.6, §5.7, §5.10, §8.1, as proposed for Revision 39; the draft in the sibling
repository is headed Revision 38.

**Kind:** request to land the text. Each rule below runs on the branch, is already staged in tson-java's register,
and is cited here "as proposed" because Part 2's draft does not hold it:

- **`meta.type_ref` withdraws the sibling reference.** The kernel declares `type_ref => !meta.field_type_ref ^ {
  allow_sibling: false }`, which is what keeps an exchange's `request`, `response` and `errors` (`meta-service.tn`'s
  `signature`) naming a type and never a sibling. §4.2 and §8.1 still describe `type_kind`, `kind_type` and a
  `type_ref` record.
- **A restated field's type narrows by IS-A** (§5.7). The draft says a restatement may narrow a field's type, and not
  by which relation; the branch judges it nominally at link, so `[int8]` does not restate `[int32]`.
  `design/schema-authoring.md` cites the rule.
- **A record template is its own constructor**, `meta.record_template`, and nothing reads or writes a value against
  it. §5.10, with §1.6, §4.1 and §5.6, still makes it a `!record` with `parameters`. `experiments/rpc/README.md`
  cites the rule.
- **The kernel has no `type_argument` record**, while §8.1 is titled for it and declares it beside `type_ref`.

**What this project does meanwhile:** cites the sections "as proposed" and cannot check its citations against text.

**Priority:** medium, since this project's notes cite sections the draft does not yet hold.
