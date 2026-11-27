# Value references: a construction as a reference to data instead of repetition

**Status:** a proposal for review, aimed at upstream's Revision 41; nothing is adopted. Measured by
`tson-http/src/test/java/io/ltr8/tson/http/probe/ValueReferenceProbeTest.java`, with its records under
`probe/valuereference/`. It asks the spec for one change, open construction over a record; this project's `http.tn`
changes wait on it. It supersedes `proposal/operation-refinement.md`, which answered the same need through types.

## Summary

Half of value references exists. A value entry declared once and reached by `meta.ref<T>` is YAML's anchor and alias:
`bearer => !http.auth { scheme: Bearer }`, named from every `security` list that wants it, is exactly that. What
is missing is the other half, a value written as another value with fields added.

Revision 40's open construction is that mechanism, one restriction away. `!integer ^ { min: 100 }` is "the value
`integer` with fields restated, judged whole"; an incomplete entry is a value with fields left open; a completion fills
them ([TSON-SCHEMA] §5.5). The restriction is that `^` takes a type a constructor built, or a constructor of types, and
refuses a record. The proposal: extend open construction from constructors of types to records, with `^` at a value slot
completing an incomplete entry.

```
paged => !http.operation ^ { status: 200  request: order_query }

orders_http => !http.projection { resources: {
  "/orders"    => { GET => !paged ^ { response: order_page } }
  "/customers" => { GET => !paged ^ { response: customer_page } }
} }
```

The resolved form writes each endpoint flat, `!http.operation { status: 200  request: order_query  response:
order_page }`, and `paged` not at all. Over an incomplete entry nothing is replaced (Revision 40 changelog items
107–108), so this is OpenAPI's `components` and not YAML's merge key: no override, so no question of which side wins,
how deep a merge goes, or what a pin means.

## The problem, from this project

Endpoints share facts. Every paged list in a description answers 200, takes the same query record, speaks the same
media types, sits behind the same security and sends the same cache headers. Today each endpoint writes them out, or
the layer grows a rule that shares one of them.

`http.tn` has grown such rules by hand (`design/http-projection.md`). A projection's `security` is inherited by every
endpoint that writes none: an endpoint's `security`, when written, replaces the projection's, `security: []` says the
endpoint is open, and absent inherits ("Security schemes", the override rule). The binder must keep absent apart from
empty (`Optional<List<ValueRef<SecurityScheme>>>`), `TsonRoute.security()` is a resolved list the reader computes, and
`CLAUDE.md` carries the sharp edge as a trap. The default status 200 is a record default that the reader then
qualifies: it holds for an endpoint with a response, and an operation with none must write 204, 205 or 304 ("Status").
Each is a rule of this vocabulary alone, implemented in `TsonServiceDescription`, with its own paragraph explaining where it
cuts. A mechanism at the document level lets `http.tn` drop the one that is sharing, the inherited `security`, and
leave the other as what it properly is, a record's own default.

## Why the type-system answer failed

`proposal/operation-refinement.md` measured the alternative: a description declares its own subtype, `paged_get =>
http.operation & { status?: = 200  request?: = order_query }`, and writes `!paged_get { … }`. The subtype is admitted
at the layer's slot since upstream's imported-head read, but:

- **a server must bind it by name before resolving the description that declares it.** Bound as
  `TsonHttpSchema.bindings()` binds, it is no member of the union `http.endpoint` binds against, so supporting it in
  general means two resolutions or a nearest-supertype binder the library does not have;
- **the pins fix little.** A composition pins `status` and `request` only: no list is pinned (§5.2), so `media_types`
  stays per endpoint, and a parameter is a type, so no parametric record fixes `response` (§5.10);
- **every reader pays.** The server and every conformance client must resolve a description's own types before they can
  tell what an endpoint is, where `!http.operation` says so at the tag.

Sharing facts through types costs every reader. Sharing them in the document costs only the resolver: a value entry's
value is read at schema load, a completion is gone from the resolved form, and the reader, the bindings and a client
reading the resolved description see `!http.operation` exactly as if it had been written out.

## The proposal

**(a) Open construction over a record.** `!R ^ { values }` where `R` resolves, past its alias chain, to a record declares
an incomplete entry: no type and no value, named only before `^` and as the head of a completion (§5.5). Today it is a
resolver error naming `R & { … }` (`openConstructionOverARecordIsRefused`). Item 107 decides incompleteness by a
required field left absent, refusing a body that writes them all as `!C { … }`. That test does not fit a record:
`http.operation` and `http.document` declare no required field, so `paged` above writes every one, vacuously. Over
a record the `^` itself declares the entry open, a record's optional fields being as much for a completion to write as
its required ones; `!R { … }` stays the spelling of a value. Over a constructor of types item 107's test stands, since
there completeness decides whether a type exists.

**(b) Completion at a value slot.** `!X ^ { values }` at a value slot, a field of a value entry or a map value inside
one, completes the incomplete entry `X`. The body writes any field `X` has not written and MUST leave no required field
absent. The result is the value `!R { … }` over the two bodies together, with no relation to `X`, and `R`'s closure,
constraints and defaults judge it whole where it is written. A declared completion still carries `@meta.completion` on
its key (§8.1); a completion at a slot carries none, as a completion written in place carries none today.

**(c) Nothing is replaced.** A field `X` has written is a resolver error in the body, a restatement of the same value
included, as items 107–108 rule for an incomplete entry. So there is no override, no direction to choose between the
base and the slot, no shallow-against-deep merge of a map or a list, and no question whether a base's value is a pin or a
restatement: a field is written once, by the base or by the slot. An endpoint needing a different status declares
another base, or writes the operation out.

**(d) Admission.** §7.2 admits a value by its tag's chain; a completion's tag names an incomplete entry, which names no
type. The completion's type is `R`, so `!paged ^ { … }` is admitted exactly where `!R` is, through the construction's
type rather than a chain of its own: at `http.endpoint`'s slot it is an `http.operation`, IS-A the abstract base.
§7.2 needs one sentence saying so.

**(e) The resolved form is flat.** Resolver output writes the completed value as the plain construction, as it writes
`set<text>` as `!meta.array { element_type: text  ordered: false  unique_items: true }`, and writes no incomplete entry
(§8.1). A reader of the resolved form never learns that `paged` existed.

**(f) A base crosses an import.** An incomplete entry is imported like any entry (§5.5), so a base declared in a layer, a
house style of `secured` and `paged` endpoints, and completed in a description needs no extern.

## Why `^` at the slot, not `!paged { … }`

The spelling tells a reader what `paged` is. `!paged { … }` reads as constructing a type `paged`, or as a value of one;
`!paged ^ { … }` says `paged` is a value with fields written onto it, which is what `^` means everywhere after Revision
40: `^` follows `!name` and its body is values (item 109). The division is then one rule. `!name { … }` constructs: the
name is a type or a constructor. `!name ^ { … }` extends a value: the name is an incomplete entry or a type's
construction (`!integer ^ { min: 100 }`). A document whose spellings reveal its structure is what this project's aim
asks for: a model reading `!paged ^ { response: order_page }` knows to look for a partial value named `paged`, and
knows the slot adds to it rather than declaring something new.

## Layering

A completion is schema syntax. A service description is a schema document, so its value entries are read by the schema
grammar, and the form lives there and nowhere else. In a data document `^` after a tag stays a parse error, and a tag
naming an incomplete entry stays a validation error (§7.2). JSON encodes data against a schema's resolved form, never a
schema or a schema-side form (`design/json-encoding.md`, "A schema document has one syntax"), and the resolved form holds
the completed value flat, so Part 3 needs no change. This is stated because the first draft of this idea reached for a
JSON spelling of a base and its completion, and needing one was the sign of a layering mistake.

## What this project would do with it

**`http.tn` drops the projection's `security`.** `http.projection` loses `security?`, and an endpoint's `security`
becomes a plain field: absent is open, `[]` the same, and no rule relates an endpoint to its projection. A description
secured throughout declares its base once:

```
secured => !http.operation ^ { security: [bearer] }
paged   => !secured ^ { status: 200  request: order_query }    # an incomplete entry written onto another (item 107)
```

The bound records type `security` as a `List`, `TsonRoute.security()` is the endpoint's own list, and the override paragraph
of `design/http-projection.md` goes with its invariant.

**The default status stays.** `status?: http.status_code ~ 200` is the record's own default, not a sharing rule; a
base can still write `status: 200` explicitly for endpoints that must not drift.

**Paged endpoints and cache headers become bases.** A description's paged lists complete `paged`, and its cacheable
documents complete `cacheable => !http.document ^ { media_types: ["application/tson"]  response_headers: {
Cache-Control => "max-age=3600" } }`. A map is written whole, by the base or by the slot, so an endpoint wanting one more
header beside the base's declares another base.

**The trap goes.** `CLAUDE.md`'s "An endpoint's `security: []` is open and an absent one inherits the projection's;
never fold absent into empty" has nothing left to describe. `@service.safe` and its siblings stay on the verb key, which
no base carries.

**What it costs.** Secure by default is lost: with inheritance an endpoint that forgets `security` is secured, and with a
base an endpoint that forgets to complete `secured` is open. `TsonServiceDescription` can recover the property as a check,
a projection stating that every endpoint names a scheme, but that is a rule the layer would have to add back. A reader of
the source must find `paged`'s declaration to know an endpoint's status and request, as with any named value.

## What is measured

Each point is a test of `ValueReferenceProbeTest`, run against a minimal layer written in the probe: an `abstract`
`endpoint` with `status?: integer ~ 200`, `media_types` and `security?: [meta.ref<scheme>]`, `operation => endpoint &
signature`, a resource of `{http_verb => endpoint}` and a projection whose resources are a map keyed by path. A
description imports it and core and declares `order`, `order_query` and `order_page`.

**(i) Open construction over a record is refused** (`openConstructionOverARecordIsRefused`). `paged => !operation ^ {
status: 200  request: order_query }` is one `SCHEMA_ERROR`: *'paged': '!operation ^ { ... }' needs an atom instance, an
array or a map type to narrow, or a constructor of types to build from (§5.5), and 'operation' is a record, which takes
no '^' -- its fields are restated in a composition, 'operation & { ... }' (§5.8)*. This is the restriction the proposal
lifts; `OperationRefinementProbeTest` pins the same message as a refinement's refusal.

**(ii) No spelling writes a value as another value with fields added**
(`aValueSlotTakesNoCaretAndATagNamingAValueEntryIsNotAdmitted`). With `paged => !operation { status: 200  request:
order_query  response: order_page }`, a complete value entry, which loads:

- `GET => !paged ^ { response: order_page }` at the endpoint slot is a parse error (`VALIDATION_ERROR`): *expected a
  value (record, map, array, empty braces, the void sentinel '_', or a token), found '^'*. The data grammar has no `^`
  after a tag, and the message names no construct;
- `GET => !paged { response: order_page }` parses and is refused by the resolver (`SCHEMA_ERROR`): *… at
  /resources/~1orders/endpoints/GET: 'paged' is not admissible at a 'endpoint' position -- 'endpoint' is abstract and
  has no direct instances, so a value here is a value of one of (operation)*, a tag naming a value entry being no type
  (§7.2);
- `paged2 => !paged ^ { response: order_page }` at an entry is refused (`SCHEMA_ERROR`): *'paged2': '!paged ^ { ... }'
  needs an atom instance, an array or a map type to narrow, or a constructor of types to build from (§5.5), and 'paged'
  is a '!operation' value, which is finished and takes no refinement*.

**(iii) The alias half works** (`aValueEntryNamedByReferenceIsDelivered`). `bearer => !scheme { name: Bearer }` and `g
=> !guard { security: [bearer] }`, `guard` holding `[meta.ref<scheme>]`, load; bound, the reference arrives with
`isResolved()` true and its referent the `Scheme` the entry declared.

**(iv) Over a constructor of types the mechanism works** (`openConstructionOverAConstructorOfTypesCompletes`). `nonempty
=> !meta.array ^ { min_items: 1 }` is an incomplete entry with `element_type` absent. `tags => nonempty<text>` and `tags
=> !nonempty { element_type: text }` each complete it into `!meta.array { element_type: text  min_items: 1 }`, equal
without its chain to that construction written directly, its key carrying `@meta.completion:{ of: [nonempty] }`. A data
document reads `!tags [a b]` and refuses `!tags []` (`TYPE_MISMATCH`): *'tags' has 0 elements, fewer than the minimum
1*. Named where a type is, `holder => { t: nonempty }`, it is refused: *'holder' field 't' names 'nonempty', an
incomplete 'meta.array' with [element_type] absent, which is no type -- complete it where it is used ('!nonempty { ...
}'), or declare its completion and name that*.

Not measured, since it is the change proposed: anything over a record base. Whether tson-java's incomplete-entry
machinery carries a record's construction unchanged is the implementation's question; (iv) is the evidence that the
form, the write-once rule and the flat resolved form already exist.

## Prior art

YAML's anchors and aliases (`&base`, `*base`) are the alias half, and its `<<` merge key, a YAML 1.1 type left out of
1.2's core schema, is the other half with override: local keys win, shallowly. OpenAPI's `components` and `$ref` share a
value with no override, a `$ref`'s siblings ignored in 3.0 and limited to `summary` and `description` in 3.1, and `allOf`
composes schemas. JSON Schema's `$ref` shares schemas, never instance data. SDF's `sdfRef` (RFC 9880) references a
definition and patches it by JSON Merge Patch (RFC 7396), override settled by the patch. CUE unifies, so a field set
twice to different values is an error rather than an override, the rule (c) takes. Protocol Buffers has no value
sharing.

## Open questions

- **Two spellings of completion, by position.** Items 107–108 spell a declared completion `tags => !nonempty {
  element_type: uuid }` and `!enum [A B]`, and an in-place completion at a type slot `!nonempty { … }`, as (iv)'s refusal
  names it; `!X ^ { … }` at a declaration writes more of an incomplete entry. If `^` completes at a value slot,
  completion has two spellings by position. The tidy version is `^` everywhere, completeness decided by what is left
  open (`!enum ^ [A B]`), at the cost of locality: a declaration meant as complete that leaves a field absent becomes an
  incomplete entry, caught only at its first use. Keeping `!X { … }` as "I assert this is complete, check it here" at
  declarations and `^` at value slots is defensible, since a value slot can only mean completion.
- **Pins in a base.** Whether a base may write a field definition such as `status?: = 200` for completions to inherit as
  fixed. Probably not: a base's body is values, and the record's declaration owns its pins.
- **Chains of bases.** `!X ^ { … }` over an incomplete entry already declares another (item 107), and `@meta.completion`
  already lists what a declared completion closed, nearest first (item 108), so `secured` then `paged` is existing
  machinery. Open is whether a value-slot completion over a chain should be flattened in diagnostics too, naming the
  base that wrote a field when the slot writes it again.
- **A complete value entry as a base.** The alternative to (a) is to let `!v ^ { … }` at a value slot write onto a value
  entry of a record type, lifting (ii)'s third refusal, the analogue of item 109's construct-then-refine over a
  constructor with no required field. It needs no new kind of entry, but makes every record-valued entry a base and
  leaves `paged` usable, wrongly, as a value in its own right.

## Recommended verdict

**Propose to upstream for Revision 41** as the resolution of the shared-facts problem: open construction over a record,
completion at a value slot, write-once as items 107–108 already rule, admission by the construction's type, the resolved
form flat. This project's `http.tn` changes, dropping the projection's `security` and the trap with it, wait on it.
`proposal/operation-refinement.md`'s question is answered here rather than through types, and it is superseded.
