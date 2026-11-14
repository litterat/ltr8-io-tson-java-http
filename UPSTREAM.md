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
is a name one of them declares, the diagnostic can say so — *"… 'ipv4', which `https://tson.io/2026/40/m/net.tn`
declares; import it, or core.tn"* — as `expected`, or in the message. No resolution changes; a schema still has to
import what it uses.

**Workaround in place:** none needed here — every schema this project owns imports what it names. Pinned by
`UpstreamGapsTest.anUnresolvedNameABundledLibraryDeclaresDoesNotNameTheLibrary`, which fails the day the message
names the library, and the import-nothing case by `UpstreamGapsTest.aSchemaImportingNothingSeesNoKernelName`.

---

## 4. A reference inside an annotation's value is not judged

**Hit:** a vocabulary stating a relation in an annotation's value. A layer declaring `binds => @meta.annotation
[meta.ref<protocol>]`, or an annotation holding a record with `exchange: meta.ref<exchange>`, gets the value's shape
checked (`@binds:42` is refused) but no name in it: a protocol nothing declares and an exchange key the protocol does
not hold both load clean. Each arrives as a `ValueRef` holding the name and never delivered (`isResolved()` false),
where the same reference in a field of a value entry is judged and delivered when the schema links. Measured on a probe
layer, `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`, whose clean load flips when it is fixed.

**The change:** a `meta.ref<T>` in an annotation's value is judged as one in a field's value: its name resolved in the
annotating document's namespace, and in a schema document at link ([TSON-SCHEMA] §5.12, §6, §3.3.3). The spec rule
stands as written; the library's failure to judge it in a schema document is work upstream's `BACKLOG.md` holds.

**What this project does meanwhile:** nothing depends on the gap. `service.tn` carries an http projection's claims,
`binds` and `not_bound`, as fields of `service.http`, so every name in them is judged at schema load and delivered its
referent (`TsonApiDescriptionTest.aBindsNamingNoProtocolIsRefusedAtLoad`).

---

## 5. `Tson.validate` on a schema document blames Class 1

**Hit:** `Tson.validate` handed a document whose root is tagged `!schema` reports one `VALIDATION_ERROR`, *"this is a
TSON schema document (its root is tagged !schema); a Class 1 (data-format-only) processor does not support schema
documents"*. Refusing is right — `validate` reads data, and `validateSchema` reads a schema — but the
`Tson` is schema-aware, so the message names the wrong cause, and the validator demo shows it to whoever pasted a
schema into the data box.

**The change:** the message saying the document is a schema and naming `validateSchema`.

**Workaround in place:** none. Pinned by `UpstreamGapsTest.validateOnASchemaDocumentBlamesClassOne`.

---

## 6. The JSON reader states one schema, where a `TSON-Schema` List states several

**Hit:** a JSON body sent with a `TSON-Schema` List of two members, its version and a schema it also uses. [TSON-JSON]
§3.5 makes the field a List because a binding is a set, read as one namespace (§3.4). tson-java's TSON readers read
under several (`withImports`); `JsonObjectReader` and `JsonTreeReader` have `withSchema` alone, so a JSON body is read
under the one schema the route states, and a type only the other member declares cannot stand in it.

**The change:** `withImports(List<String>)` on the JSON readers, as on the TSON ones.

**Workaround in place:** `TsonSchemaVersions.Routed.readObjectAs` reads a JSON body against its version alone; the
namespace is narrower than the one the field claims, never wider, so nothing is admitted that the sender did not
name. Pinned by `UpstreamGapsTest.theJsonReadersStateOneSchema`.

---

## 7. A component narrower than its field's value space binds, and fails at the read

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

## 8. A mapped class that cannot be analysed is deferred as a missing binding

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

## 9. `RecordField` states one fact through several accessors, and one of them throws

**Hit:** reading a schema this project did not write, at startup, to place a request's fields over HTTP
(`RequestPlacement`) and to check what a dependent field's sender may name (`ChosenTypes`). A record field's type is
one fact, and `RecordField` answers it four ways: `type()` returns the `TypeExpr` (a `TypeRef`, or a `TypeInline` for
a type written in place, the dependent type among them); `ownType()` returns an `Optional<TypeExpr>`, empty for a
dependent field; `sibling()` the sibling's field name as an `Optional<String>`; and `typeExpr()` returns the
`TypeExpr` and throws `IllegalStateException` on a dependent field. `typeExpr()` is the one a consumer reaches for,
since its name reads as "the field's type", and called on a record that holds a dependent field it turns a property
of a valid schema into an unchecked exception: at this project's startup a description refused with a stack trace
rather than a message, and on any path that walks a schema per request, a 500.

**The change:** no accessor that throws on a schema that loaded: `type()` read with a `switch` as the one surface and
`typeExpr()` gone, or `ownType()` the only accessor for the type a field holds.

**Workaround in place:** `RequestPlacement` and `ChosenTypes` ask `sibling()` first and switch on `type()` (`TypeRef`,
`TypeInline`), never calling `typeExpr()`. Pinned by
`UpstreamGapsTest.aDependentFieldsTypeExprThrowsWhereItsTypeIsADependentType`.

---

## 10. What a dependent field's sender may name, and whether a name binds, are reached through casts and catches

**Hit:** checking at startup that every type a sender may name at a dependent field binds (`ChosenTypes`). Nothing
answers "the types a value at this field may be read against". The answer is assembled: take the sibling's type, the
compiled schema's entry where it is named (`compiled.schema().entries()`) or the type a `TypeInline` holds where it is
written in place, follow its `Alias` hops, cast to `RefBody` and call `memberNames()`. A sibling may also be typed
`meta.type_expr`, the type-slot choice whose names are such a reference's and whose other variant is a type written in
place, one more shape the walk has to know. A pinned sibling and a discriminator, which narrow that set, are worked out
again by hand from `FieldRole.FIXED` pins and `chain().subtypes()`. Two yes-or-no questions are answered only by
exception. "Is this name the kernel's vocabulary" is `SchemaModelNameBinder.INSTANCE.resolve(name)` and
`catch (DataBindException)`, the set it consults (`Vocabulary.NAMES`) being private. "Does this type bind" is
`DataBindContext.getDescriptor(name)` and the same catch, which cannot tell a name no class is mapped to from a mapped
class that cannot be analysed without asking `nameBinder().resolve(name)` the same way first.

**The change:** public predicates for both (a vocabulary test on `SchemaModelNameBinder`, and a `DataBindContext`
query answering with the descriptor or the reason as a value), and an accessor on the compiled schema for the types a
dependent field admits, with a pin and a discriminator already applied: the set the reader judges a sibling against.

**Workaround in place:** `ChosenTypes` does the alias walk, the cast and both catches, and
`TsonHttpCodec.isKernelVocabulary` repeats the vocabulary catch to keep the kernel's names out of the JSON reader's bind
context (item 2). Not yet pinned in `UpstreamGapsTest`.

---

## 11. A name at a type-naming slot that resolves to a value is refused with the closure, not the fault

**Hit:** met writing an rpc envelope with a dependent payload, and measured by `DependentEnvelopeProbe`. The envelope
`by_type => { request: meta.type_ref  body: request! }`, read with `request: orders.exchanges.place_order` (an
exchange's qualified name), is refused `ATOM_CONSTRAINT_VIOLATION` at `/request` with *"'meta.type_ref':
'orders.exchanges.place_order' is not a member of this kind -- expected the name of a type whose value IS-A 'meta.type',
one of […]"*. The list is every member of the closure, the kernel's records among them, and the same list is the
diagnostic's `expected` (*"one of (…)"*), so a diagnostic printed whole carries it twice. What it never says is the
fault: the name resolves, to a value of `service.exchange`, and a value names no type.

**The change:** where a name at a type-naming slot resolves to a value entry, say so (*"'orders.exchanges.place_order'
names a value of 'exchange', not a type"*), and drop or cap the member list, in the message and in `expected` alike.

**Workaround in place:** none needed; nothing in the HTTP layer names an exchange where a type is wanted. Pinned over
a minimal protocol layer by `UpstreamGapsTest.anExchangesNameAtATypeRefSlotIsRefusedWithTheClosureListedTwice`, which
flips when the fault is named or `expected` stops repeating the list; `DependentEnvelopeProbe` measures it over the
orders protocol.

---

## 12. `TypeRef` names three types, and the reference types each name two

**Hit:** importing the reference types. `tson-compiler` exports `io.ltr8.tson.compiler.ast.schema` and
`io.ltr8.tson.compiler.stream`, so three public `TypeRef`s are in reach: `io.ltr8.tson.schema.meta.TypeRef`, the
model's, sealed over `NameRef` and `ParamRef` and extending `TypeExpr`; `io.ltr8.tson.compiler.ast.schema.TypeRef`,
the AST's, sealed over `NameRef` and `ApplicationRef` and extending the AST's own `TypeExpr`; and
`io.ltr8.tson.compiler.stream.TypeRef`, a stream event carrying a value's `!name` tag. `TypeExpr` and `NameRef` each
exist in both `schema.meta` and `compiler.ast.schema`, and the AST's `ApplicationRef` is the model's `ApplyBody`
under another name.
An IDE's import offers every one, and the wrong one compiles until it meets a method only the other has. The AST
mirrors the model's names deliberately upstream, as both follow [TSON-SCHEMA] §12.1's `type-expr` and `type-ref`, so
this asks that choice to be revisited for what a consumer sees.

**The change:** distinct names for the AST's forms and for the stream event (a `!name` tag is no reference), or the
two compiler packages kept from consumers that have no use for them.

**Workaround in place:** none needed; this project takes the reference types from `io.ltr8.tson.schema.meta` alone.

---

## 13. A quoted token or a tag at a kind slot in a value body is refused through an event's `toString`

**Hit:** an http projection whose endpoint lists a media type as `"application/json"` at a kind slot
(`[meta.ref<net.media_type_type>]`, and alike at `[meta.ref<meta.atom>]`), where a type's name belongs. The refusal is
right, and its message embeds *"expected a type's name, found TokenEvent[text=application/json, form=SINGLE_LINE_QUOTED,
position=Position[line=0, column=0, byteOffset=0]]"*: a Java record's `toString`, with a position of zero where the
token stands somewhere else entirely. A schema author reads a parser's internals and a location that points nowhere.
A tagged value at the same slot, an application written as `!meta.apply { head: order  arguments: [order] }`, is
refused the same way: *"found TypeRef[name=meta.apply, position=Position[line=0, column=0, byteOffset=0]]"*, the
rendering of the tag's stream event `io.ltr8.tson.compiler.stream.TypeRef`, as far as this project can see by the
same cause.

**The change:** name the token or the tag as written (*"found the quoted token \"application/json\""*, *"found a
value tagged !meta.apply"*) and report its real position.

**Workaround in place:** none needed; the status is right and nothing here shows the message to a client. Pinned by
`MediaTypeKindProbeTest.kindOfMediaTypeTypeHoldsATypeName`, over `[meta.ref<net.media_type_type>]`, which asserts the
`TokenEvent` rendering, and `aTaggedApplicationAtAKindSlotIsRefusedThroughTheTagEventsToString`, which asserts the
`TypeRef[name=meta.apply` rendering; each flips when its case is fixed.
`aKernelKindHoldsATypeNameAndRefusesAMediaTypeValue`, over `[meta.ref<meta.atom>]`, asserts only the fragments around
the token and survives the fix.

---

## 14. An application in a data value is refused as its head, then as a missing separator

**Hit:** an application at a reference slot of a data document, `!pick { m: page<order> }` with `m: meta.type_ref`.
It is refused with two diagnostics, neither naming the form: an `ATOM_CONSTRAINT_VIOLATION` at `/m` on the head alone,
*"'meta.type_ref': 'page' is not a member of this kind -- expected the name of a type whose value IS-A 'meta.type', one
of […]"* with the closure's whole member list, then the generic parse error *"adjacent values must be separated by
whitespace, a comma, or both"* at the `<`. The head was never a complete token, so the first refusal judges something
the author did not write. The same application as a tag, `!page<order> { … }`, gets one diagnostic naming the remedy:
*"'!page<...>' applies type arguments, which are schema syntax and not available in a data value (§3.2): a data
type-ref is a bare name. Name the application in the schema ('my_type => page<...>') and write '!my_type' here"*. In a
schema document's value entry the sugar gets the separator error alone.

**The change:** one diagnostic, at the application, naming the form, as [TSON-DATA] §7.2.5 and §8.1 do (a special
token the data grammar gives no role, `<` among them, so an argument list in a value): *"an argument list is no data
syntax; declare the application under a name and write that name"*. No refusal of the head as a bare token first.

**Workaround in place:** none needed; both bodies answer 400 `invalid-document` through `TsonHttpException.from`, the
status a malformed document should get. Pinned by
`UpstreamGapsTest.anApplicationInADataValueIsRefusedAsItsHeadThenASeparatorAndAsATagByItsRemedy`, which asserts both
diagnostics in order and the tag's message and flips when the two become one;
`aTemplateApplicationAtAKindSlotInAValueIsNamedThroughAnAlias` measures the schema-document case.

---

## 15. A data document importing two schemas throws at a reference through a record value's field

**Hit:** an instance document for `proposal/affordances.md`, a data document whose `!!import`s name two schemas: a
description declaring the value entry `orders => !protocol { exchanges: { get_order => … } }`, a record whose field
`exchanges` holds a namespace, and a separate vocabulary importing the same layer and declaring the instance's types.
The first reference walking that field, `exchange: orders.exchanges.get_order` at a `meta.ref<exchange>` slot, throws
`IllegalStateException` rather than reporting: *"this reference names 'orders.exchanges.get_order', and
'orders.exchanges' is a field of a record value, and this schema was linked with no bind context to read it through --
link it with the context its values were bound under (TsonCompiledVocabularyRegistry.link)"*. `validate`, the tree
reader and the object reader throw alike, whether or not the description was resolved first. The same document
importing the description alone, with the instance's types declared in the layer, reads clean and delivers every
reference. `TsonCompiledSchemaRegistry.get(List, …)` links a closure of several imports with no bind context, where
the one-import path compiles the schema the registry linked with one.

**The change:** link a several-import closure with the bind context the registry holds, as the one-import path does,
so the walk reads the field. And in any case report a diagnostic rather than throw an unchecked exception: a data
document naming two schemas is ordinary, a description and the domain schema it describes being two already.

**Workaround in place:** none needed; nothing here depends on it. The affordances proposal's instance namespace would,
and would otherwise have to declare its vocabulary inside `service.tn`. Pinned by
`AffordanceProbeTest.anInstanceImportingTwoSchemasThrowsAtAReferenceThroughAField`, which asserts the exception type
and message from all three entry points and flips when the read reports or succeeds.

**Priority:** medium.

---

## 16. An importer's subtype is left out of an untagged refusal at an imported record's slot

**Hit:** a service description declaring its own endpoint shape, `paged_get => service.operation & { status?: = 200
}`, beside an endpoint written untagged, `GET => { response: order_page }`, at a `service.resource` endpoint slot in
its own value entry (`proposal/operation-refinement.md`). `service.endpoint` is `abstract`, so the resolver refuses
the untagged value naming the subtypes it has indexed when it reads it, and in a value entry that depends on where
the description declares `paged_get`. Declared after the records its pins name, as the probe's description does, the
refusal names the layer's subtypes alone: *"'endpoint' is abstract and has no direct instances, so a value here must
name its type -- one of (operation | binding | document)"*. Declared first, as the real-layer probe's is, it names
`paged_get` beside them, and a data document importing the description always does: *"… one of (operation | binding |
document | paged_get)"*. A tagged `!paged_get { … }` is admitted at that slot in every case.

**The change:** name the subtypes of the schema being resolved in that refusal whatever the declaration order, as
[TSON-SCHEMA] §3.3.4 computes `subtypes` over its namespace, so the hint a value entry gives matches a data
document's.

**Workaround in place:** none needed; the refusal is right and only its hint is short. Pinned by
`OperationRefinementProbeTest.theDescriptionsSubtypeIsAdmittedAtTheLayersSlot`, whose last assertions state the
message in both orders and flip when the first names `paged_get`; `theRealLayerAdmitsADescriptionsOwnOperation` pins
the declared-first message through `service.tn`.

**Priority:** low.

---

## 17. A subtype nothing binds, at an abstract base's slot in a value entry, leaves the value a tree

**Hit:** `service.endpoint` declared `abstract`, and a description writing an endpoint as its own subtype,
`!paged_get { … }`, with `paged_get` bound to no class (`TsonApiSchema.bindings()` as a server passes it). While the
base was open, the description was refused at load: *"'paged_get' is not a member of the union 'service.endpoint'
binds against [Operation, Binding, Document]"*. Marked `abstract`, it loads clean, and the entry's whole value is held
as a tree rather than the bound `Http`, so a reader looking for the bound class finds no projection at all. In a data
document the object reader throws `MissingBindingException` for the same value, where the open base gave a
`TYPE_MISMATCH`.

**The change:** refuse a subtype nothing binds at an abstract base's slot as the open base's is, so whether a value
entry binds does not turn on a definition mark; or report it, rather than holding a value a bind context was given
for as a tree.

**Workaround in place:** `TsonApiDescription` refuses a value entry applying `service.http` whose value did not bind,
naming `TsonApiSchema.bindingsWith` as the remedy, so the description is a startup failure rather than one served with
its routes missing. Pinned by `UpstreamGapsTest.aSubtypeNothingBindsAtAnAbstractBasesSlotLeavesAValueEntryATree`,
which flips when the abstract case is refused, and by `OperationRefinementProbeTest`.

**Priority:** medium.

---

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers each time a
revision closes, so a finding here names spec sections, never a register number. A new finding goes here in the same
shape: a `### To file:` heading, the spec sections, the gap, what this project does meanwhile, and a priority.
A finding is deleted once filed upstream; what its ruling leaves for the library lives in the item it concerns.

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
`schemaPointer` RFC 6901 JSON Pointers (`/orders/3/total`). `problem.tn`'s `problem.diagnostic` can type them only as `text`,
and so can tson-cli's `diagnostics.tn`. A pattern states the grammar (`""` or `/`-prefixed segments, `~` only as
`~0`/`~1`), but an atom gives a host value of decoded segments, which is what a consumer walking a pointer wants.
Equality is `text`'s, since the escape is a bijection. `net.tn`, beside `uri`, is the nearest home; the root name it
reserves in every importer is the cost, since every root name of a closure is reserved ([TSON-SCHEMA] §2.2.3). This one
comes first because the library emits the values.

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
with what uses them: here, `service.tn` (below). A URI template in particular builds a URI rather than naming a
host, so it is not `net.tn`'s, and its uses outside describing an API — HAL links, OpenAPI and AsyncAPI paths, JSON
Hyper-Schema — are all that same use. **Raise it again** if a consumer outside API description needs RFC 6570, or a
description needs levels 3–4; the shape then is a constructor under `net.tn`'s `net` map beside `net.uri_type`, which
reserves no root name.

**What this project does meanwhile:**

- `problem.tn` types RFC 9457's `type` and `instance` as `uri_reference`, since both are URI references by that
  RFC (§3.1.1, §3.1.5) and `TsonProblem.at` is typically handed a request path. Pinned by
  `TsonProblemSchemaTest.aRelativeTypeAndInstanceAreValid`.
- `problem.diagnostic`'s `path` and `schema_pointer` stay `text` until 1 exists.
- The HTTP vocabulary lives in `service.tn`, which uses it — `service.http_verb`, `service.status_code`,
  `service.header_name` and `service.path_template` (RFC 6570 levels 1–2, so `{+var}` is expressible) — and `media_type` is
  `net.tn`'s. `TsonApiDescription` places each endpoint's request record over its path, which no pattern can.
  `problem.details`'s `status` stays `int32`.

**Priority:** medium-high, since the library emits values its own schemas cannot describe.

### To file: a free sibling's member order against an unordered JSON object

**Sections:** [TSON-JSON] §6.1.6 (member order), [TSON-SCHEMA] §5.2 (dependent fields); RFC 8259 §4.

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

### To file: the typing field has an everyday name

**Sections:** [TSON-SCHEMA] §5.1 and §5.2 (selectors, dependent fields), §5.5 and §5.7 (selector facets).

**Kind:** editorial. Part 2 keeps "sibling" for the field whose value types a dependent field (§5.2), and makes
`meta.type_ref` the reference `!meta.ref { of: meta.type }` (§4.2). "Sibling" is a technical term spelled as an
everyday word, in the text and in the diagnostics, while a family's discriminating field, which a sibling may be, is
already a *selector* (§5.1, §5.2).

**A suggestion:** one term for the typing field, kept for that use alone. "Selector" is this project's suggestion,
offered as such: a sibling may be a discriminator, so the term would extend rather than collide; its other use, a
facet picking among a closed vocabulary (§5.5, §5.7), would then want a word of its own.

**What this project does meanwhile:** writes "sibling" for the typing field only.

**Priority:** low.
