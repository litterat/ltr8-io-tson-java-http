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

## Spec feedback to file

Staged here, for tson-java's `SPEC-FEEDBACK.md`, since that file is hands-off. That register renumbers from #1
each time a revision closes, and its convention is *cite the spec, not the argument that got it there* — so
re-check every `SPEC-FEEDBACK.md #N` in this repo after a revision bump.


### To file: namespaces — a `namespace` kind, a members facet records share, and a projection that names a member

**Sections:** [TSON-SCHEMA] §2.2.3 (the flat namespace), §4.1 (the base kinds, `data`), §5.2 (selector pins
distinct as values), §5.5 (kind determination; construction transfers kind, not IS-A), §5.10 (templates), §8.1 (the
constructor type slots, `schema`), §8.2 (instantiation identity), §11.4 (the look-alike scopes); [TSON-DATA] §2.6
(map keys are values), §7.1 (`.` reserved as an identifier separator), §8.2 (name hygiene). Builds on four entries
of the Revision 37 register, cited below by the numbers they carried there: #2 *a namespace should be a value*, #6 *a
type slot cannot be bounded*, #7 *`identifier` should be a text family* and #9 *a template parameter should carry its
type*. None is live any more.

**Status after Revision 37.** The change log (§5) disposes of all four: #7 adopted; #9's typed parameters adopted and
its constructor bound **declined** — a type parameter names a local type, so its bound is local ([TSON-SCHEMA]
§5.10); #6's field half **withdrawn**, a dependent record being the wrong thing to add now; and #2 carried open, then
taken off the register upstream to be reworked together with the JSON member-name entry against Revision 38. That
rework is upstream's namespace sketch, which models a naming role as a `name_type` carrying a `target` and a
`relation`, so a reference is typed by its role rather than by a bounded type slot. **Step 4 below is overtaken** —
both halves it leaned on are decided against — and the rest of this entry stands as consumer evidence for the
sketch: the candidates table, the member-identity argument, and what the meta-service experiment needs from each
step.

**Kind:** a recommendation, from the consumer whose experiment #2 came out of. It takes #2's "read against #6 and #7"
addendum to a build order — the namespace itself, then reaching into it, then bounded references — and tests #2's
primitive against every candidate the consumer could find. Two results change #2's sketch: filling the empty cell *by
reference* reaches a member's type and never the member, and *having members* is a facet a record shares, so the
`namespace` kind is that facet on an entry with no instances.

**Where the kernel stands.** Checked against `spec/m/meta-kernel.tn` on Revision 37's `main`:

- **Built:** `identifier` is a text family (`identifier_type`, #7 Proposal 1), so whether a key is a name is a
  question about its *type*. Core no longer declares one (#15), so a schema wanting it declares
  `identifier => !identifier_type { continue_add: "-" }` itself. Also built: `enum_type.type` (#7 Proposal 2);
  `template_param.bound`, checked at the application and resolved in the type-name namespace only (#9); `ordered`
  on `map` (#10), which that entry says prepares #2's keyed sets; and **§8.2 at identifier-typed values in data**
  — a field value, an identifier-keyed map's keys and a unique array of identifiers are names, the key and element
  sets look-alike scopes, in both encodings and both read modes, with `IdentifierPolicy.withSkeletonDistinctness`
  as the look-alike rule's own switch — and **in a schema governed by a meta layer** as well, a `data` body's
  payload and an annotation value being read under the same policy. That last is where an interface's methods are
  written, so a namespace's member set already has its hygiene.
- **Not built:** a projection production; members as declarations. **Decided against:** a bounded type slot at a
  *field* (#6's other half, withdrawn) and a bound naming a constructor rather than a type (declined).

**1. What a namespace is: a keyed set of declarations, where a map is a keyed set of values.** A declaration
describes values and has instances of its own; a map's member *is* a value. Neither unique keys (every map has them)
nor being referenced by key (a foreign key references a row, which is a value) separates the two. The key's type is
free — names or data — and so is whether the container has instances. Tested against the candidates
(`experiments/meta-service/README.md`, "What a namespace is", has the full table):

| | key | member | namespace? |
|---|---|---|---|
| schema, database | type name, table name | a type, a table | ✓ |
| interface, agent tool set | method name, tool name | a method, a tool | ✓ |
| record fields, table columns | field name | a field, a column | ✓ — and the record is a type besides |
| api | path, then verb — **data** | an endpoint, describing exchanges | ✓ |
| discriminated family (§5.2) | the selector pin — **data** | a subtype | ✓ — already in the spec |
| problem types, AsyncAPI channels | a URI, an address — **data** | a problem, a message | ✓ |
| template parameters | `param_name` | a parameter, with #9's type | ✓ — lexically scoped |
| table rows, a request's headers, config | a key | one value | ✗ map |
| enum | a label | one value | ✗ set |

Two patterns. **The declaration side is a namespace and the instance side a map**, every time: header registry and
message, columns and rows, schema and document, interface and calls. And **a namespace keyed by data is common and
already specified once**: §5.2's selector pins, "pairwise distinct as values", are a key-uniqueness rule under value
equality over a set of subtypes. So #2's empty cell is not empty in the spec; it is unnamed.

**2. By reference, an interface cannot say which of its methods a binding binds.** #2's step 2 makes an interface
a record type and `orders.create` "the declared type of `orders`' field `create`", as TypeScript's
`Orders["create"]`. A field type is a use-site application, and §8.2 resolves one to the declaration owning it or
to one minted entry — "two fully-bound applications denote the same entry" — so in

```
orders  => { place_order: method<order, order>  update_order: method<order, order> }
binding => { method: <: method>  … }
```

`orders.place_order` and `orders.update_order` are one type, and `method: orders.place_order` satisfies the bound
while recording nothing about which method it binds. An api's `implements` claim, held per (interface, method), then
has nothing to count. #2's own example composes the projection (`orders.create & http`), which is why the loss does
not show there. The way out by reference is a top-level declaration per method, since §8.2 makes "two declarations
naming one application … two entries" — which puts `place_order` back in the flat namespace, the collision an
interface exists to scope. **A projection has to name the member**, and that needs members that are declarations.

**3. Having members is a facet; a `namespace` is the kind whose entries are nothing else.** Record fields are a
namespace and a record is a product, and §5.5 gives an entry exactly one base kind — so members cannot *be* the kind,
or a record could never have them. The table's last column draws the line: a container with instances of its own
(record, table, a family's base) is a product with members; one without (schema, interface, api, database) is a
`namespace`.

**The meta-kernel change**, step 1 below:

```
members   => { key_type: type_ref  member?: type_ref }    -- the facet: composes no base kind; no `member`, any entry
namespace => top & members & {
  entries?: {value => type_definition}                    -- resolved form only: each member, under its key
}
record    => product & members & { … }                    -- the facet's other user: the record-fields entry, not this one
```

`schema` is unchanged. **A schema document is the root namespace**, and the kernel needs no constructor to say so:
its key type is `type_name`, it admits any entry, the document is its scope and `!!id` its identity, and its resolved
form is the map `schema => {type_name => type_definition}` already is. A namespace entry's resolved body holds its own
members in the same shape one level down, in `entries`. `entries` is keyed `value` because its key type is the
namespace's own `key_type` — a dependent field, which step 4's field half would state; until then the resolver checks
each key against `key_type`, as it checks a record's field names.

**An extension meta-schema and a schema it governs:**

```
-- meta-service-1.tn
interface => namespace & { key_type: = method_name    member: = method }
api       => namespace & { key_type: = path_template  member: = resource  implements?: [<: !interface] }

-- orders-1.tn: a namespace keyed by type_name, declaring two keyed by method_name
!!id:"https://example.com/2026/37/app/orders-1.tn"
!!meta:"https://tson.io/2026/37/ltr8/http/meta-service-1.tn"
{
  order     => { sku: text  quantity: int32 }
  order_ref => { id: text }
  reads     => !interface { get_order => method<order_ref, order> }
  orders    => reads & { place_order => method<order, order>  update_order => method<order, order> }
}

-- its resolved form
!schema {
  order     => !type_definition { body: !record { … } }
  order_ref => !type_definition { body: !record { … } }
  reads     => !type_definition {
    body: !interface { key_type: method_name  member: method  entries: {
      get_order => !type_definition { source: { name: method  arguments: [ … ] }  body: !record { … } }
    } }
  }
  orders    => !type_definition {
    supertypes: [reads]
    body: !interface { key_type: method_name  member: method  entries: {
      place_order  => !type_definition { source: { name: method  arguments: [ … ] }  body: !record { … } }
      update_order => !type_definition { source: { name: method  arguments: [ … ] }  body: !record { … } }
    } }
  }
}
```

`namespace & product` reaches two base kinds and is a resolver error, correctly: something with both instances and
members is a record.

- **A namespace is what a schema document already is, at entry level.** Pass 1 registering names, pass 2 resolving
  bodies, the collision rule, forward references, the look-alike scope and the `(identity, name)` reference all exist
  today, for the document only. The kind lets that machinery run for an entry, so an interface is a small schema
  inside a schema.
- **It is not `data`, and should not be spelled `data & members`.** What each gives:

  | | `data` | `namespace` | `product & members` |
  |---|---|---|---|
  | naming the entry | refused everywhere | an operand of `&`, `^`, `-`; a `<: !interface` slot; never a type slot | a type |
  | its payload | values, checked against the vocabulary and opaque past it | declarations the resolver resolves | fields |
  | members reachable | no | from outside once step 2 builds projection | `order.sku`, likewise |
  | operators | none | `&` extends, `^` pins across members, `-` subsets; templates (`crud<T>`) | the same, over fields |
  | example | an OpenAPI `x-` extension, a vendor blob | schema, interface, api, database | record, table |

- **`data` narrows to an embedded payload for a reader that knows it.** §4.1's motivating case — "an HTTP operation
  binding request and response types by name" — becomes a namespace member, and §4.1's wording ("vocabulary a
  meta-schema introduces … whose instances ride along in a schema map without being types") then describes a
  namespace better than what `data` is left for. Both should change together.
- **Composed, not applied.** `interface => !namespace { … }`, as #2 spells it, would make `interface` an instance, IS-A
  nothing (§5.5), and unappliable as `!interface`. Composition keeps it a constructor, a meta-schema privilege already.
- **`extends` is `&`, not a field.** On a namespace `&` merges member sets — `orders => reads & { … }` has `reads`'
  members and its own, a collision between them refused as it is between two composed records' fields — so the
  relation is structural and needs no reference slot. The spelling of a composed namespace is the author's to settle;
  the point is that the operator already means it.
- **An inherited member is not copied.** `orders` reaches `get_order` through `supertypes`, and its `entries` hold
  only what it declares, so a method composed into several interfaces stays one declaration with one identity. A
  record copies inherited fields into its body (§5.8); a namespace cannot, without minting a second identity for one
  method.
- **`:` binds the constructor's own fields and `=>` declares a member**, so `implements: [orders]` and
  `"/orders" => …` share one body without ambiguity.
- **Not composed with `map`.** `map.value_type` says each value is data *of* a type, `member` that each value is a
  declaration IS-A one — exactly the difference between the two cells — and composing would also make a namespace a
  product. What `map` does is *describe* a namespace's resolved form, as it already describes the one the kernel has:
  `schema` types the resolver's output, not the schema document, and `entries` is the same description one level
  down.
- **Out of scope: an interface's instances.** They are its implementations — a server, a capability reference — and
  that is a later hook, not part of this cell.

**4. The key's type decides how a member is reached.** An identifier key projects with `.`, which [TSON-DATA] §7.1
reserves for it. A data key cannot — `orders_api./orders.POST` is not a name — and needs the pointer form the
"formats" entry below stages, `/orders/POST`: projection and the JSON-Pointer profile are two spellings of one walk.
Hygiene follows the key type as #7 already arranged: the identifier policy per name at identifier keys, the token
policy at data keys, the look-alike rule on every declared key set.

**Recommendation: the namespace itself first, then reaching into it, then bounded references.** Each step is usable
without the next. The order is chosen because each builds what the next exposes: the facet's `member` *is* a bound —
every member IS-A `method` — so step 1 implements an IS-A check over declarations internally, and step 4 makes the
same check a slot type.

1. **The namespace itself: the `namespace` kind and the members facet, over identifier keys.** Members are declared
   with `=>` inside an entry, resolved in the namespace's own scope, and checked there; nothing outside names one yet.
   Four rules to state with it:
   - **A constructed namespace is IS-A nothing**, as §5.5 has every construction: `orders => !interface { … }` is not
     IS-A `interface`. An exception for namespaces would make `<: interface` work later and spare step 4 a relation,
     at the price of the one place construction creates IS-A; the "any scalar" bound needs the relation anyway.
   - **Member conformance.** A member declared as an application, `place_order => method<order, order>`, IS-A
     `method` only where `method` is a family base (§8.2's entry shape). Either the member bound requires that, or
     conformance reads "an application of" from `source`; the first keeps it one IS-A check.
   - **Composition** merges member sets and copies nothing (point 3), so `extends` is `&`.
   - **§4.1 narrows `data`** in the same edit, its motivating case moving to a namespace member (point 3).
2. **Reaching a member: projection, and the name it needs.** `orders.place_order` at type-ref and `!name` positions
   names the member declaration — its own entry by §8.2's two-entries rule — not its type. Three rules to state with
   it:
   - **A qualified name in `type_ref.name`.** `type_ref.name` is a `type_name`, an identifier, which admits no `.`.
     Widen it to identifier segments joined by `.` — the identifier policy's existing per-segment unit is then the
     hygiene unit. Step 4's references reuse this shape.
   - **Nested or flat output.** Step 1's `entries` nests one level, and projection walks it. Flattening — every member
     under its qualified name in the `schema` map — makes every name-level index one token and one lookup again, at
     the cost of qualified keys in `schema`; it needs this step's name, so it is decided here or not at all.
   - **Records take the facet in the same step**, or `.` means the member on an interface and the type on a record.
     The next entry's `key_type` is then the facet's, not a field of `record`'s own.

   It answers the two questions the meta-service experiment left open: **a method's identity** is
   `(interface identity, key)` — `…/orders-1.tn` and `place_order` — with no invented `operationId`; and **a plan
   document can bind a method**, `!orders.place_order { request: { … } }` checked against it, where today a data
   document can bind only the type namespace and writes `method: place_order` as data. What each relation of the
   experiment gets before step 4:

   | relation | at step 1 | at step 2 |
   |---|---|---|
   | member conformance | ✓ the facet's `member` bound | |
   | `extends` | ✓ as `&` — no field | |
   | a method's identity | a declaration, named only inside its namespace | ✓ `orders.place_order` |
   | `binding.method` | a `type_name` token | a `type_ref`: `method: plaec_order` is a resolver error |
   | `implements` | a `type_name` token | still: a namespace entry is a non-type, which a `type_ref` slot refuses |

3. **Data keys, which the api needs, with two questions this entry does not settle.** An interface proves the kind;
   an api adds keys that are not names, projection by pointer, and these, none of which changes what steps 1–2
   built:
   - **Two levels or one.** An api is keyed by path and then by verb. Nested, `resource` is itself a namespace and a
     namespace becomes a member — nesting, which step 1 refuses. A compound key `[path, verb]` stays one level, and
     has no pointer token: this project measured that a compound key is `?` in a TSON path and an index pair in a JSON
     one (`UpstreamGapsTest.aCompoundKeyHasNoPointerTokenAndTheEncodingsDisagree`). Nested is the one a member can be
     reached in; it should be admitted for the api's case rather than in general.
   - **What an endpoint member is.** A binding is the method plus transport facts — placement, status — and #2's
     method-as-type measurement showed the smell of making those fields: they are injected into every call. Either
     they ride as annotations on the member's key, where `@safe` and `@summary` already sit, so the member is the
     method itself under a route; or the member's body is something that is not IS-A the method.
4. **Bounded references: #6's field half in its unnamed form, and a bound that may name a constructor** —
   *overtaken by Revision 37, which withdrew the first and declined the second; kept for the two consumers it names
   last, which the sketch's typed name roles would have to serve instead.* A map value
   cannot bind a parameter name — each entry names a different type — so the bounded reference has to exist as its
   own type, `<: method>`, with `<T: method>` the sugar that also binds `T`. Three parts:
   - **The resolved form #6 left open.** `<: X` lifts (§5.3) to a synthetic entry of one kernel constructor whose
     values have `type_ref`'s shape, arguments included — `extends`-style slots may name `crud<order>`:

     ```
     ref_type     => <kind> & { bound?: type_ref  relation?: ref_relation ~ IS_A }
     ref_relation => !enum [IS_A BUILT_BY]

     implements?: [<: !interface]     -- record_field.type names array_7d1
     @synthetic array_7d1 => !array    { element_type: ref_3c9 }
     @synthetic ref_3c9   => !ref_type { bound: interface  relation: BUILT_BY }
     ```

     Its kind is open: its values are `type_ref`-shaped records, which says `product`.
   - **`type_ref` becomes the unbounded case, `type_ref => !ref_type {}`.** §9's rule that a `type_ref` slot
     participates in reference walking and structural identity then belongs to the constructor rather than to one
     record by name, so every bounded slot has it, and every existing `type_ref` field resolves unchanged —
     `record_field.type: type_ref` being `<: top>` by another spelling. `ref_type.bound` is itself a `type_ref`, a
     cycle the kernel closes as it closes its other local self-references.
   - **A bound may name a constructor, and a reference may name an entry that is not a type — decide #6's open
     question as yes.** "Any scalar", which a URL segment needs, is not a type bound: `text` and `int32` share no
     supertype that says *scalar*, and #6 rules out following form or discrimination class. `BUILT_BY` is that bound,
     `<: !atom` or `<T: !text_type>` as #9 sketches it, and it is what `implements` needs, `orders` being built by
     `interface` and IS-A nothing. `type_ref.name` then names an entry, as `type_argument`'s own doc already allows
     ("a type, an entry, or … a parameter").

   Over steps 1–2 it adds `binding.method: <: method` and `implements?: [<: !interface]`. It is also worth taking on its
   own, for two things that need no namespace:
   - **`enum_type` as structure:** `type: <T: text>  members: set<T>` replaces §7.4's conformance and bound rules,
     which #7 Proposal 2 currently states in prose and the linker enforces.
   - **This project's `meta-http-1.tn`**, whose `parameter.type` carries a `@doc` saying it "names a scalar and
     nothing here enforces that" — `<: !atom`.
5. **Hold: general nesting, visibility and named imports.** A namespace as a member beyond step 3's case, a private
   member an importer does not see, and `!!import` binding a name are each a decision about §2.2.3's flat namespace
   rather than about this cell. Privacy has a precondition here: an unreserved private name breaks "one name denotes
   one type throughout a resolution", which tson-java's `DataNameBinder.resolve(String)` relies on.

**Out of scope, deliberately:** the `(schema identity, root type name)` pair this project reassembles at every call
site (`describing`, `readObjectAs`, the `TSON-Schema` header plus a route-supplied type). It exists because a *data*
document cannot hold a reference to a type, and nothing above changes that. Folding it in would blur both
questions.

**What this project does meanwhile:** nothing that presumes the answer, and offers itself as the measuring stick. The
meta-service probes join `tson-http`'s test build, so each step shows up as a probe changing state: at step 1
`interface` moves from `data &` to `namespace &` and `extends` becomes `&`; at step 2 `binding.method` becomes a
`type_ref` and `Routes` keeps only the IS-A half of that check and its `implements` check; `api` and `resource`
follow at step 3; at step 4 both of those become resolver checks and `meta-http-1.tn`'s `parameter.type` takes
`<: !atom`. `meta-http-1.tn` (`operation => data & { … }`) is the one published-shape consumer §4.1's narrowing
touches, and migrates to an `api` namespace rather than being grandfathered — the evidence #2's point 4 asked for.
`InterfaceMapProbe` keeps measuring §4.1's open reading — whether a `data` *constructor* may be a map's value type —
which the kind makes moot for an interface, its methods becoming members rather than map values.

**Priority:** high if Revision 37 carries any namespace work. Step 1 stands alone; step 2 is the one that gives a
method an identity anything outside its interface can name, and nothing short of it does; step 4 is worth taking on
its own, namespace or not.

### To file: a record's fields as a namespace, and field names that are not identifiers — where the rule moves

**Sections:** [TSON-DATA] §1 (layering), §2.5 (record, `field-name`), §4 (base type resolution), §7.7, §8.2;
[TSON-SCHEMA] §5.2, §5.11 (field groups), §8.1 (`record`, `record_field`, `field_group`), §11.4; [TSON-JSON] §3.2
(the reserved member namespace), §6.1.1 (closure and NFC matching), §6.1.6 (member order). Builds on #2, #3 *a JSON
member name that is not an identifier*, #6, #10 and #18 *a field group's option should hold several fields*,
numbered as above.

**Status after Revision 37.** The numbers are the Revision 37 register's. #6's field half was withdrawn, so point 3
cannot lean on it; #3 left the register with #2, to be reworked together against Revision 38 in upstream's namespace
sketch, where `record.fields` is already a namespace keyed by a naming role. Points 1, 2, 4 and 5 bear on that
rework unchanged; point 3's dependent key type is the question the sketch answers with a role instead.

**Kind:** a proposal responding to a direction under consideration upstream — `record.fields` becoming a namespace
whose key type need not be `field_name`, so that a JSON member name such as `@context`, `_id` or `2fa_enabled` is a
declarable field. The direction is sound; what decides whether it is clean is where *a field name is an identifier*
ends up living.

**1. The lexer already spells it, so the rule is semantic — and its current home breaks layering.**
[TSON-DATA] §2.5 has `field-name = unquoted-token / single-line-token`, so `{ "@context": x }` lexes today; the
identifier requirement is §2.5 and §7.7 applied on top. No lexer change is needed and the Class 1 freeze holds. But
if a schema may declare text keys while that rule stays where it is, a Class 1 processor — which does not act on
`!!schema` — refuses a document a schema-aware processor admits, against §1's *each part adds capability without
modifying the parts below it*.

**Recommended:** move the rule into base type resolution (§4), which the series already confines to positions no
schema types. A schemaless record's key type is `field_name`; a schema-typed record's is what it declares. The
schemaless behaviour is unchanged, name hygiene included (pinned here by
`UpstreamGapsTest.everyFieldNameOfASchemalessRecordMeetsAllThreeNameRules`). §4.1 should then say outright what a
Class 1 processor does with a record in a document carrying `!!schema`, since Revision 36's restatement of
applicability by position is what this leans on.

**2. `$` must stay unmintable, structurally.** [TSON-JSON] §3.2 calls the reservation "sound by construction"
*because* declared field names are identifiers. A text key type would let a schema declare `$schema` as a field.
Rather than refuse bare `text` by a rule, bound every record's key type by IS-A a kernel `member_name` — a text
refinement excluding `$`-initial names — and restate §3.2 over the bound. Refinement only narrows, so every key type
inherits the exclusion and no processor has to remember a check.

**3. The key type is a dependent field, so this needs #6's field half.** Three slots reference the record's own
keys and must be typed by its key type: `discriminators`, and — since #18 made a group's option a set of fields —
both of `field_group`'s, `members: [[field_name]]` (the options) and `optional?: [field_name]` (the members marked
`?` within theirs):

```
field_group => <K: member_name> {
  members:   [[K]]
  optional?: [K]
  state?:    element_state ~ REQUIRED
}

record => product & {
  key_type?:       <K: member_name>       -- absent: field_name
  fields:          {K => record_field}    -- ordered: true
  groups?:         [field_group<K>]
  discriminators?: [K]
  …
}
```

#18 widens what rides on getting this right: its census finds "at least one of a set of keys" at 55 SchemaStore
sites, all of them converted JSON contracts — the population a text key type exists to serve — so a group over
keys like `@id` and `@type`, `( "@id": I | "@type": T )+`, is a shape the two proposals would admit only together.
Whether any of the 55 has such keys is not something the census records. #6 also records that §5.2
cannot default a type slot today; its type-param slot is what admits the default.
**`fields` is `ordered: true`**, since field order is observable — resolved output, §6.1.6's encoder order, host
constructor order — which #10 anticipated. **`record_field.name` retires**, changing resolved output for every
record; in this project that touches two tests reading `RecordBody.fields()` as a list.

**4. Hygiene follows the key type, with the look-alike rule kept on every declared set.**

- **Per-name rules** (character, script): the identifier policy at identifier keys, the token policy at text keys —
  #7's line, applied to records.
- **The look-alike set rule runs at schema load on every declared key set, whatever its type.** A record is closed,
  so data can spell only declared names, and the spoofing surface is the schema's own set; checking it is cheap and
  catches `user-name` beside `usеr-name` (U+0435) in a converted contract — the case #3 shows a
  `patternProperties`-style map cannot.
- **Matching stays NFC for every key type**, as [TSON-JSON] §6.1.1 states it for JSON today. Otherwise two
  spellings of `café` are two fields, which is the same attack through normalisation.

**5. A host-binding row, as `enum` has one.** `@context` is not a Java record component. §7.4's binding row is the
pattern: identifier-keyed records guarantee generated host members, text-keyed ones require a host-side name
mapping. tson-java's `@Profile` `fields` already maps constructor parameters to field names and may be that
mapping; whether it admits a non-identifier is unverified.

**6. This supersedes #3, and should say so.** #3's `@json_name` projection and this direction answer one gap, and
leaving both open leaves no stated reason to prefer either. #3 declined to relax §7.7 because it "changes the
*model* to serve one encoding". The answer is the meta-service experiment's finding: these are names from a
**borrowed namespace** — JSON-LD's, MongoDB's, a vendor's — and a path template or a header name has the same
shape. TSON text spells them already, so the need is not JSON's alone. Filing this should retire #3 explicitly.

**What this project does meanwhile:** nothing. A JSON body with such members is typed as a map or is not readable
as a record; `problem-1.tn` is unaffected, RFC 9457's members all being identifiers. What it would gain:
`acceptingJson` reading `_id`/`@context`-shaped APIs as records rather than maps, `meta-http-1.tn` parameter names
that are not identifiers (`page[size]`), and the experiment's `Placement` mapping URL segments, query keys and
header names onto request fields directly rather than by a consumer-side check.

**Priority:** medium. It depends on the previous entry's step 1 (the members facet) and on how the sketch types a
key position by role, now that #6's field half is withdrawn, and
its point 1 should be settled before any schema-side text key is admitted, since that is the one part that touches
Part 1.

### To file: formats this project needs and the vocabulary cannot state — five types, by strength of evidence

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
JSON Pointers (`/orders/3/total`). `problem-1.tn`'s `diagnostic` can type them only as `text`, and so can tson-cli's
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
- `deployment-1.tn`'s `schema_hosts` is `[text]`.
- A set or map of references cannot dedupe `https://x/a.tn` against `http://x/a.tn?sha256=…`.

Proposed: a reference family whose values are absolute, identifying references held to the canonical-form rule, and
whose **equality is canonical identity**, with an instance for the canonical spelling beside it. It is not a
normalization: the host folds, the path keeps its case, and the scheme and the pin are dropped rather than folded.
tson-java's `CanonicalIdentity` is the existing implementation.

**3. `hostname` (RFC 1123 labels, RFC 3986 `reg-name`) — constructor in `meta`, core instance.** Core has `ipv4` and
`ipv6` but no host name, so an RFC 3986 host, `(hostname | ipv4 | ipv6)`, is not expressible. `deployment-1.tn`'s
`listener.host` and `schema_hosts` are `text`, and §2.2.1's own identities are keyed by host. DNS names compare
case-insensitively, which #19's `normalization: NFKC_CASEFOLD` now states — for A-labels the fold is ASCII lowercasing
— so the family needs a grammar and a fold, not an equality of its own. Proposed ASCII (A-labels) to match `uri`, with
IDN U-labels left to an `iri`-style sibling if wanted. `hostname` is a plausible schema-declared name, so the core
instance is weighed against #15.

**4. `media_type` (RFC 6838; parameters per RFC 9110 §8.3.1) — constructor in `meta`; core instance pending.** An atom
for its equality, which no whole-value fold can state: type, subtype and parameter names fold, while a parameter
*value* keeps its case unless its own registration says otherwise — `charset`'s does, `boundary`'s does not — and
`charset="utf-8"` equals `charset=utf-8`. Proposed facets, following `uri_type`'s rule that permissions are withdrawn
and never granted back:

- `allow_parameters?` (default false: a media type proper, not a `Content-Type` value);
- `types?`, a set of top-level types;
- `suffixes?`, RFC 6839's structured suffixes. This project's `acceptingJson` admits `application/json` and any
  `+json` type, which is `suffixes: [json]`.

Media ranges (`*/*`, `text/*`) are `Accept` syntax and are refused. **The core instance carries the highest collision
risk of the five:** an OpenAPI conversion naturally declares `media_type` for OpenAPI's Media Type Object. #15's
evidence method answers it — grep the `ltr8-io-tson-benchmarks` conversions for type declarations named
`media_type`, `mime_type` and `content_type` — and until then the constructor alone costs nothing. Name it after RFC
6838's term, *media type*; "MIME type" is the legacy one.

**5. `uri_template` (RFC 6570) — constructor in `meta` beside `uri_type`; core instance.** `meta-http-1.tn`'s
`operation.path` is `text`, so nothing checks a template's variables against the declared `PATH` parameters. One
demo template is wrong under the RFC: `/{schemaPath}` percent-encodes the slashes the value carries, where the
servers serve `{+schemaPath}`. The meta-service experiment's `path_template` approximates level 1 with a pattern. A
`level?` facet (1–4) bounds the expression operators admitted, since only the low levels can be matched as well as
expanded. Collision risk is low.

**Deliberately not proposed:** `status_code`, `http_method` and `header_name` as types in core. They are HTTP
vocabulary rather than formats, and exactly the names #15 shows converted schemas declare for themselves, so they
belong in a library a schema opts into. This project owns that library (below).

**What this project does meanwhile:**

- `problem-1.tn` types RFC 9457's `type` and `instance` as `uri_reference`, since both are URI references by that
  RFC (§3.1.1, §3.1.5) and `TsonProblem.at` is typically handed a request path. Pinned by
  `TsonProblemSchemaTest.aRelativeTypeAndInstanceAreValid`.
- `diagnostic.path`, `schema_pointer` and `schema_id` stay `text` until 1 and 2 exist.
- The HTTP vocabulary becomes an `http-1.tn` here when wanted: `status_code`, declared identically in
  `meta-http-1.tn` and the experiment while `problem.status` is an `int32` that admits `42`; `http_method`, declared
  twice under two names; and `header_name`, which the meta-service experiment already declares as a case-folding
  text family (`ApiProbe.aHeaderNameIsHeldFolded`).

**Priority:** 1 and 2 medium-high, since the library emits values its own schemas cannot describe; 3–5 medium to
low, each serving one use here.

---

**Already filed.** Five entries staged here have gone to `SPEC-FEEDBACK.md` and left this file: §8.2's policy has no
artifact, which Revision 37 closed with the bundled `policy.tn`; naming a schema for a document that cannot carry
`!!schema`; no shorthand for a template application at a `type_ref` slot in data; a namespace should be a value (all
four on 2026-09-01), now being reworked upstream against Revision 38; and `identifier` as a text family with `enum`
stating its type, adopted in Revision 37. Prose in
this repo names each by its subject, not its number. A new finding goes here in the same shape: a `### To file:`
heading naming the spec sections, the gap, what this project does meanwhile, and a priority.