# A description's own refinement of `http.operation`

**Status:** a proposal for review; nothing is adopted. Measured by
`tson-http/src/test/java/io/ltr8/tson/http/probe/OperationRefinementProbeTest.java`, with its records under
`probe/operationrefinement/`. It concerns this project's vocabulary, `http.tn`, and the reader `TsonServiceDescription`;
tson-java admits the description's subtype at the layer's slot, and the one difference from a data document it
surfaces, an untagged refusal naming the layer's subtypes only, is staged in `UPSTREAM.md`.

## Summary

One spelling derives the shape, the composition: `paged_get => http.operation & { status?: = 200  request?: =
order_query }` loads, and the empty composition. `^` follows `!name` and takes values, so `http.operation ^ { … }`
is a parse error naming the composition. The question's premise, that a description could then write its endpoints
`!paged_get { … }`, holds. At the one place a description writes an endpoint, a slot of the layer's own
`http.resource` read in the description's value entry, tson-java admits the description's subtype ([TSON-SCHEMA]
§3.3.4 computes `subtypes` over the schema being resolved; §7.2 admits by the tag's chain), as it does at a record the
description declares and in a data document importing it. It binds only once `paged_get` is bound to the base's
class: bound as `TsonHttpSchema.bindings()` binds, naming the layer's records alone, it is no member of the union
`http.endpoint` binds against, so a description declaring `paged_get` resolves through `TsonHttpSchema` only with
`bindingsWith` naming it, a name the server must know before the description resolves.

The derived shape buys little. A list cannot be pinned (§5.2), so `media_types` stays per
endpoint; `status` already defaults to 200; a parametric record cannot fix `response`, since a parameter is a type and
`response` holds a type reference as its value; and `@service.safe` sits on the verb key, which a type does not carry.
What paged lists share is a payload shape, and `page => <T> { items: [T]  next_page?: int32 }` with `order_list =>
page<order>` says it today with no change to the layer. **Recommended verdict: not proposed.** Writing the operation
out is cheaper than the machinery, and the backlog item can close. `proposal/value-references.md` supersedes this
answer, sharing the facts in the document as a base value an endpoint completes rather than through a type.

## The question

`BACKLOG.md`, "A description's own refinement of `http.operation`": should `http.tn` and `TsonServiceDescription`
admit an endpoint shape declared in the description, so what every paged list shares is fixed once?

```
paged_get => http.operation & { … }        # or a refinement, or a parametric record applied
"/orders" => { GET => !paged_get { response: order_page  request: order_query } }
```

## What is measured

Each point is a test of `OperationRefinementProbeTest`. All but the last run against a minimal layer written in the
probe: `endpoint => { status?: integer ~ 200  media_types?: [media_type] }`, bound to a sealed interface so it has no
bare form, with `operation => endpoint & signature`, `binding` and `document` composing it, `resource => { endpoints:
{http_verb => endpoint} }` and `http => { resources: resources }`. The messages spell the probe's bare names, where the
layer would write `http.operation`. A description imports that layer and core and declares `order`, `order_query`
and `order_page`.

**Which forms load** (`aCompositionOfOperationLoadsAndARefinementOfItIsRefused`). A record derives by `&` alone.
`paged_get => operation & {}` and `operation & { status?: = 200  request?: = order_query }` load clean. A
`meta.type_ref` is a reference atom, so `request` takes a pin. Each of these is refused with one diagnostic:

- `status: = 200`, unmarked (`SCHEMA_ERROR`): *'paged_get': tightening 'status' from defaulted to required is not a
  permitted state transition -- a refinement can only restrict, never expand (§5.8)*. The spelling is `status?: = 200`.
- `media_types?: = ["application/tson"]` (`VALIDATION_ERROR`): *expected a scalar token, the void sentinel '_', or '?'
  after '=', found '['*. A value modifier is a scalar token (§5.2), so no list field is ever pinned.
- `operation ^ { status?: = 200  request?: = order_query }`, or any body (`VALIDATION_ERROR`, a parse error): *'^'
  follows '!name' and takes values (§5.5, §5.7), and 'operation' has no '!': a record's fields are restated in a
  composition, 'operation & { ... }' (§5.8)*. So the question's second form is no form.
- `!operation ^ { status: 200 }` (`SCHEMA_ERROR`, from the resolver): *'paged_get': '!operation ^ { ... }' needs an
  atom instance, an array or a map type to narrow, or a constructor of types to build from (§5.5), and 'operation' is
  a record, which takes no '^' -- its fields are restated in a composition, 'operation & { ... }' (§5.8)*.

A composition adding a field, `operation & { page_size?: int32 }`, loads. Nothing refuses a field naming no inherited
one, so a misspelt restatement (`statsu?: = 200`) adds a field too; its spelling is the author's to check.

**A parametric record cannot fix the response** (`aParametricRecordCannotFixAnOperationsResponse`).
`paged => <T> operation & { response: T }` loads, since what `T` decides is judged where it is applied. Applied:

- `paged_orders => paged<order>`: *'paged_orders': not a valid restatement of 'operation''s fields (§5.8): field
  'response' is typed 'order', which is not 'meta.type_ref' or IS-A it*. `response: T` restates the field's type,
  where the shape wants its value fixed.
- `paged => <T> operation & { response?: = T }`: *parameter 'T' stands at the value of field 'response'
  (meta.record_field.value) -- a parameter is a type, … (§5.10)*.
- `GET => !paged<order> { … }` (`VALIDATION_ERROR`): *'!paged<...>' applies type arguments, which are schema syntax
  and not available in a data value (§3.2) …*.

So the question's third form is no form: the parametric record that would parameterise an operation by its response does not
exist in the grammar.

**At a slot the description declares** (`theDescriptionsSubtypeIsAdmittedAtItsOwnRecordsSlot`). With `wrapper => {
e: endpoint }` in the description, `w => !wrapper { e: !paged_get { response: order_page } }` loads: `paged_get` is
admitted as IS-A `endpoint` through `operation`, two levels and across the import (§7.2). Unbound it is a tree tagged
`paged_get`, its fields `response`, then `status` 200 and `request` `order_query` filled from the pins. With
`paged_get` bound to the base's `Operation` class it arrives as an `Operation` with those values. `status: 201` is
refused: *'w': the body is no value of the type 'wrapper' -- at /e/status: 'status' is fixed on 'paged_get' and cannot
be given another value -- the schema declares it with '=' (fixed); for a default the data may override, use '~'*. So
the pins do what they were declared for.

**At a slot the layer declares** (`theDescriptionsSubtypeIsAdmittedAtTheLayersSlot`). The same endpoint, written
where a description writes it, `orders_http => !http { resources: { "/orders" => { GET => !paged_get { … } } } }`, is
admitted in the description's value entry, according to what is bound:

- nothing bound: a tree, the endpoint tagged `paged_get`, its fields `response`, then `status` 200 and `request`
  `order_query` filled from the pins;
- the layer bound and `paged_get` bound to nothing: the description loads and the entry's value is a tree, the
  layer's `endpoint` being `abstract` as `http.endpoint` is (an open base refuses the same value as no member of the
  union its sealed interface closes; `UPSTREAM.md`, "A subtype nothing binds, at an abstract base's slot in a value
  entry, leaves the value a tree");
- `paged_get` bound to `Operation` too: an `Operation` with status 200, request `order_query` and response
  `order_page`.

A subtype one level down (`endpoint & { request?: meta.type_ref }`) is admitted alike, and refused as `BIND_MISMATCH`
under `Operation`, whose `response` and `errors` no field of it fills. An untagged endpoint is refused by the
resolver, the base being abstract: *'endpoint' is abstract and has no direct instances, so a value here must name its
type -- one of (operation | binding | document)*, the layer's subtypes only while `paged_get` is declared after the
records it pins, and `paged_get` beside them when it is declared first, as a data document always names it.

**In a data document importing the description** (`aDataDocumentAdmitsAndBindsTheSubtypeAtTheLayersSlot`). The same
layer slot admits the subtype. The tree reader delivers the endpoint with `typeRef()` `paged_get` and the pinned
`status` and `request`. The object reader refuses it as a missing binding (`MissingBindingException`, *no bound Java
class for 'paged_get' …*) while `paged_get` binds nothing, and delivers an `Operation` once it is bound to
`Operation`. An untagged endpoint is refused naming *(operation | binding | document | paged_get)*: here the closure is
the description's. `request: order` against the pin is `FIELD_FIXED` from both readers.

**Binding to the base's class** (`aSubtypeAddingAFieldDoesNotBindToTheBasesClass`). Only a subtype with the base's
field set binds to its class. `operation & { page_size?: int32 }` is admitted at the layer's slot in the description's
value entry, written with `page_size` or without it: a tree tagged `paged_get` with nothing bound and with the layer
bound, and with `paged_get` bound to `Operation` the one diagnostic `BIND_MISMATCH`, *'orders_http': 'paged_get' and
…Operation do not agree: no component for field 'page_size' …*. The mismatch is the type's, so leaving the field out
of a value does not help. In a data document the tree reader admits it and the object reader refuses it the same way.

**What the sharing is** (`aParametricRecordOverTheResponseRecordSaysWhatPagedListsShare`). `page => <T> { items: [T]
next_page?: int32 }`, `order_list => page<order>` and a plain `!operation { request: order_query  response: order_list
}` at the layer's slot load and bind in the description's value entry, with no change to the layer.

**Through the real layer** (`theRealLayerAdmitsADescriptionsOwnOperation`). `orders-api-inline-1.tn` under a new
identity, declaring `paged_get => http.operation & { status?: = 200 }` and writing `GET /orders/{id}/items` as
`!paged_get`. Bound as a server binds by default (`TsonHttpSchema.bindings()`) it loads with the projection held
as a tree, and `describedBy` refuses it: *'orders_http' applies http.projection, and its value did not bind to
…HttpProjection but is held as TsonRecord -- a type written in it binds to no class; bind a description's own endpoint
subtype through TsonHttpSchema.bindingsWith*. Unbound it loads, the endpoint a tree tagged `paged_get` with
status 200. With `bindingsWith(Map.of("paged_get", Operation.class))` it loads, the endpoint is an `api.Operation`, and
`describedBy` reads the route with status 200, request `order_ref` and response `order_page`.

## What it would change

**In `http.tn`: nothing.** The layer need declare nothing for an importer to compose its records, and every form
that loads composes `http.operation` as it stands.

**In Java, if the proposal were taken up:**

- **`TsonServiceDescription`** would need no new case, as long as each description subtype binds to its base's class: the
  reader switches on `Operation | Binding | Document`, and a `paged_get` bound to `Operation` is one. The route would
  not know it was a `paged_get`: the bound record carries no type name.
- **Bindings must reach the description's own names**, and `TsonBindings.of` runs before the description resolves,
  when those names are unknown. Value entries bind at schema load, so the description needs its bindings while it is
  being resolved. A reader would have to resolve twice: once with a `Tson` binding nothing, read `chain.subtypes` of
  `http.operation`, `http.binding` and `http.document` from the description's compiled schema, map each
  subtype to its nearest bound supertype's class (refusing one that adds a field, which would `BIND_MISMATCH`), then
  build a second `Tson` with `bindingsWith` those and resolve again. The alternative is a library feature: a binder
  falling back to the nearest bound supertype's class. That is a request upstream, not something this project writes.
- **`TsonHttpSchema.bindings()`** stays the layer's map. The subtype entries are per description, so the
  `describedBy` contract (a `Tson` already binding the vocabulary) would change to "a `Tson` whose bindings were
  derived from this description".

## Costs

- **A second way to write an operation.** A reader of a description must find `paged_get`'s declaration to know which
  fields an endpoint has, which pins hold, and that it is an operation at all. `!http.operation` says it at the tag.
- **The reader's subtype logic**: two resolutions at startup, a nearest-supertype walk, and a refusal for a subtype
  that adds a field. That is machinery in `TsonServiceDescription` or `TsonHttpSchema` serving one convenience.
- **What a conformance client sees**: `!paged_get` in the published description. A client that knows `http.tn`'s
  vocabulary but reads the description as a tree must resolve the description's schema to learn that `paged_get`
  IS-A `http.operation`. One that binds by name, as `TsonHttpSchema.bindings()` does, fails as the server does today.
- **What it saves is small**: `status: 200` is already the default; `media_types` cannot be pinned; `request` can be
  pinned only where every paged list takes one query record; `response` differs per resource and cannot be
  parameterised; `@service.safe` is read from the verb key (`resource.endpoints().getAnnotations(verbKey)`), so every
  paged GET still writes it.

## Not proposed

- **A parametric record over an operation** (`paged<T>`): the grammar has no way to substitute a parameter into a field's
  value (§5.10), and substituting it into the type is refused as no `meta.type_ref`.
- **A list pin for `media_types`**: §5.2 restricts value modifiers to scalar tokens in v1; asking for a list pin
  is spec work for one convenience.
- **Annotations carried by a type to the keys it is written under**: `@service.safe` before `paged_get =>` sits on
  the type entry, and making the reader merge it into the verb key would invent an inheritance rule the spec does not
  have.

## Order of work

1. The untagged refusal's short hint is staged in `UPSTREAM.md` as "An importer's subtype is left out of an untagged
   refusal at an imported record's slot", pinned by `theDescriptionsSubtypeIsAdmittedAtTheLayersSlot`; that test and
   `theRealLayerAdmitsADescriptionsOwnOperation` pin the admission and the bound read.
2. Fold those pins into `UpstreamGapsTest` if the layer ever depends on the behaviour; until then the probe holds
   them, as the record of why.
3. Close the backlog item as not proposed, pointing here; the payload's parametric record is what a CRUD family should
   use, and an example in `orders-types-1.tn` (`page => <T> …`) would show it.

## Open questions

- **Does the admission reach every layer slot?** tson-java reads a value whose tag names a type beyond the slot's
  declaring schema through the resolving document's entries, so a tagged value at any layer slot should be admitted
  alike; only the endpoint is measured.
- **If upstream adds a nearest-supertype fallback**, does the verdict change? The forms would then
  cost one resolution and no reader code, leaving only the second way to write an operation. That case is worth
  reopening once it exists.

## Recommended verdict

**Not proposed**, for these reasons:

1. **A server cannot bind it without knowing the name first.** The subtype is admitted at the layer's slot and binds
   to `api.Operation`, but only through `bindingsWith` naming `paged_get`, a name the server learns from the
   description it has not yet resolved. Under `bindings()` it is no member of the union, so supporting it in general
   means two resolutions, or a nearest-supertype binder the library does not have.
2. **Even working, it fixes little.** Of what a paged list shares, the composition can fix `status` (already 200) and,
   only where it is shared, `request`. It cannot fix `media_types` (no list pins), `response` (no value
   parameters), or `@service.safe` (a key annotation).
3. **What a paged list does share is its payload**, and a response parametric record (`page<T>`) with a shared query record
   says it now, in the domain schema, with no change to the layer, the reader or the bindings.
4. **It costs every reader.** The server and every conformance client would have to resolve a description's own
   types before they can tell what an endpoint is, where `!http.operation` says so at the tag.
