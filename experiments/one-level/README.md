# One level: schemas without the meta ladder

**Status: design direction, adopted under the rule below.** Measured at tson-java `a53c3f66` by
[`OneLevelProbe`](java/io/ltr8/tson/http/experiment/onelevel/OneLevelProbe.java) and
[`NamespaceTemplateProbe`](java/io/ltr8/tson/http/experiment/onelevel/NamespaceTemplateProbe.java). Nothing here
resolves in the proposed form, and nothing in this project depends on it. [`SLOTS.md`](SLOTS.md) reads the same design
through Self's and Newspeak's slots, as a language designer would, and [`COMPUTATION.md`](COMPUTATION.md) keeps some
future-dreaming about computation that sits beside TSON without entering it. [`IDENTITY.md`](IDENTITY.md) is a research
map for identity and delegation, for agents acting on people's behalf.

**Since then, tson-java PR #753 (merged) retires `data`** and makes `namespace` a constructor under `top`, which is this
note's "entries are types or values" step taken inside the ladder. The probes are ported to it: a namespace is
written as the bare map and walked as `orders.get` with no reading-side rule, and the record-template workaround is
gone. The rest of the note still reads as measured at `a53c3f66`, except "The reference atom", measured at
`5c39e2df` beside tson-java #756, which confines `ref<T>` to kernel-governed schemas under the ladder.

## Verdict

**Design rule: correctness over churn.** TSON has no users yet, and its base is meant to last for generations. A
rewrite of the spec and of every implementation is an acceptable price for a more correct base, so nothing in this note
is weighed by how much it changes: re-pinning every meta layer, rewriting the corpus, and redoing the resolver all count
for nothing against a rule that is more correct.

**Under that rule, one level is warranted:**
- **One checked rule replaces five that leaked.** Visibility, placement, direction of service and the meta-as-import
  cycle check held the ladder up (measured below), and an import across levels went through all four until tson-java
  PR #755 added a fifth rule, the level of an import. Read-defer-resolve with a cycle check is one rule, and it is
  checked.
- **The bootstrap becomes an instance of the rule.** The kernel's pre-loaded self-reference is the one cycle closed by
  hand, and its resolved form is the fixed point, instead of being an exception four rules carve out.
- **`data` duplicates the kind system,** and the duplication keeps producing special cases: #751's value-body read
  mode, §4.1's DATA refusals, "a constructor is never the type of a value".
- **The types become right.** `record_field.value`, defaults, annotation values and `type_argument` values get their
  real types through dependent fields, instead of an atoms-only `value_type`.
- **The fixture check gets stronger.** A resolved form trusts only the pre-loaded kernel.
- **The reference vocabulary converges.** `kind_type`, `ref_type` and the name-only `type_ref` are one atom over a
  qualified name, kept apart only because `of` resolves in two stores; under the ladder `ref<T>` is dead at the level
  data lives and the `members` index names the wrong level's types ("The reference atom", below).
- **Explicitness survives at a finer grain.** The header used to say a document *may* declare constructors; each entry
  now says it *is* one, by composing `top`. A data document still declares nothing, and a document's kind is still in
  its header.

**Dependent fields are not separable from one level.** A default whose type is declared later in the same schema, or
whose type is the record being declared, needs the same read-defer-resolve and cycle check as a constructor applied in
the document that declares it. Building the machinery for one is building it for both, so the choice is both or
neither. Under the rule, both.

**The correctness work is the dependency model** ("The dependency model: shape and value", below): what an entry's
reading may wait on, and what counts as a cycle. Everything else in this note rides on that definition, and it is
specified, and proved on the kernel, before anything is removed ("Order of work").

**Under a rule weighted by cost, the verdict would differ.** Most of the problems that motivated this have local
fixes: a namespace kind written as a record template works today (`NamespaceTemplateProbe`), and the host-shape gap
is a bug fix. That is recorded so the rule stays visible as the reason for the verdict, and so the verdict is revisited
if the rule ever changes.

## The question

Within schemas, TSON has a ladder: a schema is governed by a meta layer, a meta layer by the meta-kernel, and only a
kernel-governed document may declare a constructor. Suppose the ladder goes:
- the meta-kernel stays self-referencing;
- every schema imports it;
- a constructor and its instances can sit in one document;
- the resolver works out the order to resolve things in, instead of the author declaring it with levels.

What does that remove, and what does it cost? The part that is hard is that a body can't be read until the declaration
it is an instance of has been resolved. That is a type depending on a term in the same scope.

**Scope: the schema level only.** Data governed by a schema stays, and stays a separation worth keeping. The ladder
that goes is the one between schemas: `!!meta` as a rung, meta layers, and placement. A schema then has to identify
itself some other way, and the `-resolved.tn` forms, which today prove the resolver isn't drifting, have to survive.

Then three threads this note pulls on:
- whether the data/schema line, which this project treats as fixed, blurs as well;
- how an API description and its HTTP routes combine with the schema records they name;
- how the agent sketches (`agent.tn`, `agent-vm.tn`) sit in that picture.

## The ladder today, measured

| case | outcome on `a53c3f66` |
|---|---|
| constructor declared in a meta layer, applied in a schema it governs | resolves |
| a meta layer applying its own constructor | refused: "'!step' does not resolve against the structure namespace" (§3.3.1) |
| an ordinary schema declaring `step => data & { … }` | refused: "supertype 'data' names no type this schema declares or imports" |
| an ordinary schema importing the kernel and core | refused: "'void' is declared by two different schemas"; since tson-java PR #755, at the import's level |
| an ordinary schema importing the kernel, declaring a constructor | refused by placement: "only a schema chaining to meta-kernel.tn directly may declare new constructors" (§2.2.2); since PR #755, at the import's level |
| a schema whose `!!meta` names itself, declaring only types | refused: "is part of an import cycle" ([TSON-DATA] §2.2.3) |
| in one governed schema, an instance naming a type declared beside it | resolves; a kind slot naming an instance beside it is refused |

**What the table shows:**
- **Separate mechanisms hold one ladder up:**
  - visibility: the kernel's names aren't in an ordinary schema's namespace, so `data` is simply undeclared;
  - placement: a constructor may be declared only under the kernel;
  - direction of service: a schema's `!` targets come from its meta, never from its own entries;
  - the import-cycle rule, which counts `!!meta` as an edge;
  - and since tson-java PR #755 a fifth, the import's level: an ordinary schema imports no kernel-governed one, so the
    kernel's names never reach it and placement is reached by no loaded document.
- **The kernel escapes the first four by being pre-loaded.** Its own doc says so: "the circularity is closed by pre-loading
  rather than by resolution". §2.2.2's "when a schema is its own meta, the two namespaces are the same entry set" is
  true only for the one document that is pre-loaded.
- **Ordering is already solved inside one document for types.** An instance naming a type declared beside it resolves,
  and so do recursive types. The ladder only constrains *constructors*.

## What collapsing it would remove

**From this project:**
- **The two-file split for an API.** `meta-http.tn` exists as a separate document because `operation => data & { … }`
  may only be declared under the kernel. With one level, `http.tn` is an ordinary library declaring `operation` as an
  ordinary record type beside `http_method`, `status_code` and `uri_template`, and an API description imports it like
  any other schema. No kernel extension is involved (see "Entries are types or values, and `data` goes").
- **The fold of `http.tn` into `meta-http.tn`** (commits `abadf10` and `0224035`). It was forced by levels: a meta
  layer couldn't import an ordinary library's vocabulary, a rule tson-java PR #755 now enforces. Under one level the
  rule is withdrawn and the fold reverses.
- **The namespace roadblock.** The design `namespace => abstract top & { of: kind<map> }` put a fact about the
  constructor in an instance field, and made `field_map` a constructor that can't type `record.fields`. With one level,
  `myapi => !api { get_orders => … }` is a value entry of an ordinary record type, and a namespace kind is a record
  whose one field is typed by a pinned sibling (see "Dependent fields and the namespace kind").
- **The meta-service experiment's two documents.** `meta-service.tn` and its examples would be one library and its
  users, with no `!!meta` between them.

**From the kernel and the spec:**
- the placement rule (§2.2.2);
- the direction-of-service rule (§3.3.1's split between the structure namespace and the type namespace);
- the pre-loading special case. Pre-loading becomes the general bootstrap: the kernel is the one document whose
  read-dependency cycle is closed by hand.

**And `data`.** Once an entry may be a value of an ordinary type, "this entry is not a type" is said by the kind
system already, and `data` only restates it (see "Entries are types or values, and `data` goes").

**What stays:** the kind checks. A kind slot refusing an instance declared beside it is already one-document behaviour.

## The proposal

### One namespace per schema

- A schema's namespace is its own entries plus its imports'. `!` targets resolve in that same namespace. An entry's
  `!T` names either a constructor, making the entry a type, or an ordinary type, making it a value.
- `!!meta` stops being a rung. A schema that wants the kernel imports it, which is what `meta.tn` already does
  (`!!import` of the kernel beside its `!!meta`). What still has to say "this is a schema" is below, under "How a
  schema identifies itself".
- `core.tn` imports the kernel instead of declaring `void` itself, which is the collision the probe measures. A schema
  then imports `core.tn` and gets both.

### Two kinds of dependency

| | example | cycles |
|---|---|---|
| **link** | `order => { items: [line] }`: a name at a type position | fine: recursive types; linked after everything is declared |
| **read** | `get => !operation { … }`: a body whose reader *is* a declaration | an error, except the kernel's own, closed by pre-loading |

Today's levels are a coarse, declared ordering that guarantees every read dependency points into another document.
That's the same move as GHC's Template Haskell stage restriction, where a splice may run only code from an imported
module. One level computes the ordering instead, and the next section says exactly what is ordered.

### The dependency model: shape and value

**The central definition.** A read dependency is never on a whole entry. Reading a value needs its type's **shape**,
never the type's **values**. Splitting each entry into the two, and stating which waits on which, is what lets the
cycle check refuse exactly the unsound schemas: without the split it either refuses sound ones or admits unsound ones.

**Shape** is what a reader needs to read a value of the type:

| entry | its shape |
|---|---|
| a type built by a constructor (`order => { … }`, `!integer_type { … }`) | its constructor; field names, field types as names, optional and voidable marks, groups; key, element and value types; parents; family selectors and their pins |
| a constructor (an entry composing `top`) | the shape of its instances: it is a record type whose values are entries |
| a template | its parameters and its open body's shape |
| an application | its template's shape with the arguments, as names, substituted |
| an alias | its target's shape |
| a value entry (`create_order => !operation { … }`) | none: it is not a type |

**Values** are everything read *against* a shape: a value entry's payload, a field's default or pin (`~ v`, `= v`),
a dependent field's content, an annotation's value.

**Edges:**
- **shape → shape (read).** An entry's declaration is an instance of a constructor `C`: its shape waits on `C`'s shape,
  including constructors tagged inside the declaration. An alias waits on its target's shape, an application on its
  template's.
- **value → shape (read).** A value waits on the shape of the type it is read against.
- **names (link).** A type name inside a shape (a field's type, an argument, a pinned kind-typed sibling) is a link. It
  orders nothing, which is why recursive types are sound.

**The invariant: a shape never waits on a value.** Values are leaves of the graph, so a cycle can only run through
shapes, and every value can be read once the shapes are closed. Two cases look as if they break the invariant, and
both are held to it by a rule that already exists or is stated here:
- **A family selector's pins are part of the base's shape,** since dispatch reads them. §5.2 already confines a selector
  to an atom or an enum, whose shapes never wait on a user value, so the pins are read with readers that are always
  closed.
- **A pinned dependent field (`of?: = method_map`) is part of the type's shape.** Its sibling is kind-typed, so its value
  is a name: a link, not a read. A dependent field whose sibling isn't kind-typed is refused at its declaration.

**A cycle is a cycle of shape → shape edges,** refused naming the entries on it. The kernel is the one exception: the
shape of `record` is described by a `!record`, and that cycle is closed by pre-loading the kernel's shapes.

| declaration | edges | verdict |
|---|---|---|
| `point => { x: int32  next?: point = { x: 1 } }` | the default (a value) → shape of `point` | sound: a default never makes a cycle |
| `frame => { origin: point = { x: 0  y: 0 } }`, `point` declared after it | value → shape of `point` | sound: order of declaration is irrelevant |
| `step => top & { takes: type_kind }`, then `s => !step { takes: order }` | shape of `step` → kernel `record`; `s`'s value → shape of `step` | sound |
| `api => ns ^ { of?: = method_map }` | shape of `api` names `method_map` (link) | sound |
| `record_field { type: point  value: { x: 0 } }` | the value → shape of the type its sibling names | sound |
| `a => b`, `b => a` | shape of `a` → shape of `b` → shape of `a` | cycle: refused |
| `c1 => !c2 { … }`, `c2 => !c1 { … }` | shape of `c1` → shape of `c2` → shape of `c1` | cycle: refused |

**What the model guarantees:**
- **Termination.** Shapes are finite and acyclic apart from the kernel, values are leaves, and nothing computes.
  Template expansion is the one place a bound must be stated (open below).
- **Order independence.** The result doesn't depend on the order entries are written in. This is the property that lets
  a resolved form be read without any order asked of it.
- **One algorithm.** A schema's source, its resolved form and an agent plan are read by the same rule over different
  pre-loaded sets.

**The proof obligation.** The model is proved on the kernel before anything else changes: `meta-kernel-resolved.tn`,
read by this algorithm over the pre-loaded kernel shapes, must describe that kernel exactly. A kernel that isn't a fixed
point under the model means the model is wrong, not the kernel.

### Resolution in passes: read, defer, resolve

No order is asked of the author, or of any output. Where `!T { … }` is met and `T` isn't resolved yet, its AST is
read and held, and it isn't processed until `T` is resolved:

1. **Read.** Every entry is read to its AST. TSON reads any body without a schema, so nothing has to be resolved
   first. Each `!T` met, at an entry's head or nested inside its body (`!response` inside an `!operation`), is
   recorded as an edge, shape or value as the model above assigns it.
2. **Shapes: defer and resolve.** A shape whose shape edges are all resolved is processed and its reader compiled.
   Processing one can release others waiting on it. Repeat until nothing changes.
3. **Fail on what is left.** A shape still waiting when nothing more can be released is on a cycle, or waits on one.
   That fails the compile, naming the cycle: "'step' waits on 'step'". The kernel's own cycle is the one exception,
   closed by pre-loading.
4. **Facts over the closure:** chains, `subtypes`, a kind's `members`, family discriminators (§3.3.4). These need
   every shape and no value.
5. **Values.** Every value is read against its now-closed shape: value entries' payloads, defaults and pins, dependent
   fields, annotation values. Then kind slots are judged for membership, and references resolved. Nothing here can
   wait on anything in this step, because values only wait on shapes.

**"Partially where required"** is step 2 doing only what is needed, on shapes alone. A kind's membership needs every
entry's kind, and an entry's kind comes from its head (`!operation`), which is known once the AST is read. So
`kind<operation>` can be judged without any operation's body having been processed, and judgement never waits on
itself.

**This is the same algorithm wherever it runs.** A schema's own source, its resolved form (below) and an agent plan are
all read this way. The only difference is what is pre-loaded: the kernel for a schema and a resolved form, the
kernel plus the interface for a plan.

**Why the dependent part stays manageable:**
- The dependency is on *declarations*, and the only reduction is template substitution.
- There's no evaluation to loop. Type : Type is a hazard for languages that compute; Cardelli's polymorphic
  λ-calculus with Type : Type is consistent as a language, just not normalising.
- Termination narrows to template expansion, which already has to be answered with or without levels.

### What it costs

Churn isn't counted (see "Verdict"). What is left are properties of the design, and each needs an answer:
- **A document's role is no longer visible from its header.** Whether a schema defines vocabulary becomes visible per
  entry instead: an entry composing `top` is a constructor. The spec currently prefers the document-level statement
  ("the ladder keeps that role explicit rather than emergent"), and the entry-level one replaces it.
- **A schema can extend the language it is written in.** Its meaning then depends on itself, so tooling and readers
  have more to hold in their heads.
- **More work per untrusted schema.** A schema named by a request body can declare constructors and use them. §9.1
  would want a bound on read-dependency depth beside nesting depth.
- **Less push toward sharing.** Today one meta layer is *the* `operation`. With one level, any schema can declare its
  own; sharing still works through imports, but nothing pushes toward it.

### How a schema identifies itself

Today classification *is* `!!meta`. [TSON-DATA] §2.2's kind dispatch: after an optional `!!id`, a `!!meta` makes the
document a schema; anything else makes it data. That takes at most two directives of lookahead and no value parsing,
so a stream or a content sniffer can classify a document from its opening bytes. Whatever replaces `!!meta` as a rung
must keep that budget.

| option | classifies by | for | against |
|---|---|---|---|
| **keep the directive, change its meaning** (recommended) | `!!meta` naming the kernel revision the schema is written in, the same value for every schema in a series; any other value refused | dispatch unchanged; carries real information, the kernel revision a schema and its resolved form depend on | a directive with one legal value per revision is a version marker, so it may want a new name (`!!kernel`) so the old meaning isn't read into it |
| **classify by `!!import`** | a header with any `!!import` is a schema; a data header can't carry one (`meta-resolved.tn`'s own doc says so) | no directive just for classification | ties classification to an import list; the kernel imports nothing, so it is special again |

Either way `!!id` stays optional in the grammar and required for publication, as now.

### Resolved forms

The `-resolved.tn` fixtures (`spec/m/meta-kernel-resolved.tn`, `meta-resolved.tn`, `core-resolved.tn`,
`net-resolved.tn`) are the one check of the resolver that isn't the implementation marking its own work
(`ResolvedFixtureTest`): parse the source, run the resolver, canonicalise, compare by name. They must survive.

**Today a resolved form is data, read under its source's meta.** `meta-resolved.tn` is `!!schema` the kernel;
`core-resolved.tn` is `!!schema` `meta.tn`. Its type names are inert: the namespace they resolve in "is declared by
the source schema's header and supplied at ingest … never by this document". The meta is what supplies a reader for
every tag the fixture uses. That is the one place the ladder carries weight for the proof, and the probe shows it:

| case | outcome on `a53c3f66` |
|---|---|
| a governed schema's resolved entries (`order`, `s => !step { … }`) read as data under the layer that declares `step` | reads |
| the same entries plus `step`'s own resolved body, read as data under the kernel alone | `step`'s body reads as an ordinary `!record`; `s` is refused: "'step' is not admissible at a 'top' position" |

**Under one level the source's meta is gone, and its own constructors are in the fixture.** `!step { … }` sits beside
`step => !record { … }`, and the kernel alone has no reader for it. Reading the fixture with the read-defer-resolve
algorithm is what closes the gap:

- `!!schema` names the kernel, always.
- Each entry is read to its AST. `s => !step { … }` waits on `step`; `step`'s own entry is a `!record` the kernel
  reads, so it resolves first and compiles the reader `s` was waiting for.
- No order is asked of the fixture. A fixture whose constructors wait on each other in a cycle doesn't read, as a
  source with the same cycle doesn't compile.

**What that keeps, and what it strengthens:**
- **The drift check is unchanged.** A fresh resolution of the source is compared with the fixture by name, as
  `ResolvedFixtureTest` does now.
- **The trusted base shrinks to the pre-loaded kernel.** The fixture's readers come from the fixture's own entries,
  never from a fresh resolution, so a drift in how the resolver handles a constructor's declaration shows up as a
  difference instead of being absorbed into the reader. Today `core-resolved.tn` is read under `meta.tn`, whose own
  resolution is also under test.
- **The kernel's fixture stays the fixed point.** `meta-kernel-resolved.tn` read under the pre-loaded kernel must
  describe that kernel: the one closed loop, already checked.
- **A resolved form re-resolves to itself.** Every stage is explicit in it, so reading it and resolving the result is a
  no-op. That is a second check the fixtures can carry once their readers come from themselves.

**What the fixture still can't say on its own** is the namespace its type names resolve in: `order` in a kind slot
names an entry of the source's closure, imports included. That stays "supplied at ingest" from the source's header,
as today. Without `!!meta` that header is the source's `!!import` list alone.

### Entries are types or values, and `data` goes

Today `data` exists to say "this entry is not a type", so that a kind slot refuses it (§4.1) and the meta binder knows
to bind it. Under one level an entry's value is either built by a constructor or is an instance of an ordinary type:

| entry | its value | IS-A | a type? |
|---|---|---|---|
| `order => { sku: text }` | an instance of the constructor `record` | `record`, `type`, `top` | yes |
| `create_order => !operation { … }`, `operation` an ordinary record | an instance of `operation` | `operation` | no |
| `uuid_type => atom & { … }`, a kernel extension | a constructor | `atom`, `type`, `top` | builds types |

- **An entry is a type exactly when its value is built by a constructor in the `type` family.** `type_kind`'s members
  are computed that way already, so a value entry is excluded without being marked. `data` has nothing left to say.
- **The `top` family is entered only to build types.** Extending the kernel (a new kind of atom, a new constructor)
  still composes `top`, `atom`, `product` or `sum`. `operation`, `interface` and `api` don't: they are ordinary record
  types, and their instances are value entries.
- **What placement guarded moves to the kernel import.** Today only a kernel-governed document may declare a
  constructor. Under one level, any schema importing the kernel can extend it. Whether that needs a guard of its own
  (an explicit marker, or a policy a deployment can refuse) is open below.

**The cost is the split between the two binders.** Today `metaNameBinder` binds a meta layer's `data` constructors and
the data binder (`bindings`) binds document types, and `CLAUDE.md` calls the separation deliberate ("a meta layer's
vocabulary is a separate namespace on purpose"). Without `data`, a value entry is an instance of an ordinary type, so
the type no longer says which binder it belongs to. The natural line is between types whose instances are entries and
types whose instances are in documents. A type used both ways has to bind once for both, or the line has to be drawn
by the use instead of by the type.

### Dependent fields and the namespace kind

**The dependent feature is a field whose type is a sibling field's value.** `record_field.value` is typed
`value_type` and restricted to atoms because nothing can say "the type of this field's own `type`":

```
record_field => { type: type_kind  value?: type  … }    -- value read against the field's own type
```

- **The record's kind-typed fields are in scope for its other fields' types,** as a template parameter is (`v: T`
  resolves `T` among the parameters before the schema root).
- **Kept decidable by three rules:**
  - the sibling must be kind-typed, so it names a type and nothing is computed;
  - siblings mustn't depend on each other in a cycle;
  - reading the dependent field waits until the sibling has resolved: read-defer-resolve within one record.

**The namespace kind under it:**

```
ns        => abstract { of: kind<namespace>  entries: of }       -- an ordinary record type, not a constructor
field_map => ns ^ { of?: = !namespace { key_type: text  value_type: record_field  ordered: true } }
api       => ns ^ { of?: = method_map }
governed:  myapi => !api { get_orders => { verb: GET  response: order_list } }
```

Each concrete kind pins `of`, so `entries` has a fixed type at the declaration. **A pinned dependent field is a template
parameter**, so this static case is `ns => <M> { entries: M }` applied once per kind. Record templates are records
since #727/#731, so the static case can be measured today, under a meta layer, with `data &` standing in for what one
level makes unnecessary ([`NamespaceTemplateProbe`](java/io/ltr8/tson/http/experiment/onelevel/NamespaceTemplateProbe.java)):

| case | outcome on `a53c3f66` |
|---|---|
| `iface => data & ns<meth_map>`, `meth_map` a `!namespace` | resolves |
| written `!iface { get => { request: order } }` | the same value as `{ entries: { … } }`: §5.6 lets the map stand in for the one required field |
| `request: no_such` inside | refused at `/entries/get/request` |
| `ref<meth>` to `orders.entries.get` | resolves |
| `ref<meth>` to `orders.get` | refused: "'orders' is an 'iface', which has no field 'get'" |
| the same template closed over a plain map `{identifier => meth}` | not entered: "a map that is no namespace" |
| `ref<meth>` bound to a `String` host component, naming `nobody.get` | **resolves**: not judged; judged since PR #753 (`NamespaceTemplateProbe`) |

**How it answers the three problems with `of` as a constructor field:**
1. **`of` was at the wrong level.** Here `of` *is* an instance field, pinned per kind. The instance's shape is its own
   record fields, so no special reader rule is needed; the only new rule is dependent typing, which has uses of its own.
2. **`field_map` couldn't type a slot.** `ns` is a record type, not a constructor, so `record.fields: field_map` works.
3. **The walker asks the type.** Being a namespace stays on the map type, as upstream has it.

**The wrinkle is the path, `orders.entries.get`.** The fix is the reading-side counterpart of §5.6: a record whose one
required field holds a namespace is entered through that field. Then `orders.get` walks, and writing and walking agree.

**What dependent fields add beyond templates is variation per value.** Templates cover every namespace kind, because
each fixes its map type. Dependent fields are needed where each value differs:
- `record_field.value`: every field has its own `type`, so a template would need one application per field type;
- a map or array default;
- an annotation declaration's value;
- `type_argument`'s split between a type name and a value, where the value could be typed by the parameter.

§7.8's scoped slot is the existing per-value case. There the type travels inside the value (`!T`); a dependent field
puts it in a sibling.

**Two limits:**
- **Streaming.** In a data document, a free (unpinned) sibling has to arrive before the field it types, or the reader
  buffers. Requiring it first matches how a tag is read first. A pinned sibling, and a schema body (read to its AST
  anyway), are unaffected.
- **Placement is one step further.** "`query` names fields *of* the record at `request`" is a type *computed from* a
  sibling, not a sibling's value. Dependent fields don't reach it.

### What `kind<T>` means

Today `kind<T>` is "the name of a type built with the constructor `T`", and `ref<T>` "the name of a value whose type
IS-A `T`", refused for a constructor (`ref<record>` is `kind<record>`). With value entries in the schema map, both read
as one rule:

> `kind<T>` is the name of an entry whose value IS-A `T`, for any `T`.

- **For a constructor `T`, nothing changes.** A type entry's value is an instance of its constructor, so `kind<record>`
  and `type_kind` (`kind<type>`) have today's members.
- **For an ordinary type it is new.** `kind<operation>` names the operation entries: today's one-segment
  `ref<operation>`.
- **`ref<T>` is the qualified form,** reaching through namespace values (`orders.methods.place_order`). The one-segment
  cases of the two merge.
- **The slot's meaning comes from `T`, not from the spelling.** If `T` is in the `type` family the name is a type
  reference: it takes part in structural identity and is delivered as a `TypeRef`. Otherwise it names a value.
- **In a data document** the probe already shows `type_kind` judged for membership. The same rule extends to value
  entries, which is how an agent plan's `method` could be checked (see the agent section).

### The reference atom: `kind`, `ref` and `type_ref` are one

The section above was an argument from what one level would allow. This one is an argument from what the kernel
already does, measured at tson-java `5c39e2df` while working tson-java #756: the three ways the kernel holds a name in
a slot are one atom, and the only thing keeping them apart is the ladder.

**The three forms.** `kind_type` is an atom whose `of` names a constructor of the structure namespace and whose values
are one-segment type names, with a resolver-computed `members` index. `ref_type` is an atom whose `of` names a type of
the writer's own namespace and whose values are qualified names walking entries, then namespace keys and record
fields. `type_ref` is a record, `{ name: type_name  arguments?: [type_argument] }`, the application form. The types
under `type` otherwise describe data: an atom's values are tokens, a record's are records, an array's are arrays. These
three describe a *location in the namespace*, and all three are represented by the one thing, a qualified name.

**Four measured facts.**
1. **Every slot delivers into one host shape.** tson-java reads a kind-typed, a reference-typed and a `type_ref`-typed
   slot into the same `TypeRef`, and the judgement belongs to the slot (`design/references.md`: "One host shape for every
   reference: a `TypeRef`, whatever the slot. Only the judgement differs by slot, and it is the slot's, never the host's").
   The implementation found the one atom before the spec did.
2. **`type_ref` without arguments is already `kind<type>`.** No bundled resolved output carries an `arguments:` field:
   every closed application lifts to a minted entry (`array_type_name_xxhash`, `kind_type_type_c430c650`), so in
   resolved output a closed type reference is a bare name. The kernel's own slots that name a type (`record_field.type`,
   `map.key_type`, `array.element_type`, `choice.variants`, `template_param.bound`) are typed `type_kind`, which is
   `kind<type>`; `type_ref` survives in three slots (`type_chain.parents`, `reference.target`, `type_argument.name`).
   What it carries beyond a name is an application still open: the kernel's `type_ref` doc keeps `arguments` "inside
   template bodies and in an alias's `reference.target`", and a record template's resolved body is one (#17). The
   bundled outputs show none only because no bundled schema has one. So `type_ref` is two things: a bare name, which
   is `kind<type>`, and the open-application form, which nothing else expresses.
3. **`kind<C>` is `ref<C>` read at the meta level.** Under SPEC-FEEDBACK #14 a schema map's entry is its value, so
   `order => { sku: text }` is a value of the kernel type `record`. `kind<record>`, "the name of an entry whose value is
   built with `record`", is then "the name of an entry whose value IS-A `record`", which is `ref<record>`'s predicate
   word for word, minus #22's exclusions: not a constructor whose instances are types (a namespace constructor, whose
instances are maps, is a member), and not a template over another constructor (a record template is a type and a
member). The spec refuses `ref<record>`
   and makes `kind<record>` the spelling for one reason only: `of` resolves in two stores, the structure namespace for a
   kind and the type-name namespace for a reference. Two stores is the ladder. The exclusions are facts about `top`'s
   family, not about stores, and survive as one predicate on the referent.
4. **The ladder makes half the vocabulary dead and the index wrong.** A reference's first segment names an entry
   holding a value, and under the import-level rule (PR #755) a namespace type, the only thing that puts a value in an
   entry, exists only in a kernel-governed schema. So `ref<T>` in a schema governed by meta.tn, which is where data
   lives, admits no value at all: an empty atom, which tson-java PR #760 removed from core.tn (#756). And `kind_type.members`, the derived
   index in resolved output, is computed over the *declaring* schema's closure, while the names at the slot resolve in
   the *holding* document's. For a kind declared at level 2 and held at level 1 the index lists level-2 types, and the
   linker rightly ignores it; it is honest only when declaring and judging closure coincide, a level-1 kind on level-0
   data. Neither is a bug in the atoms. Both are the two stores showing through them.

**Is `kind_type` an atom or a sum?** An atom. A sum's values are values of a variant; a kind's values are names. #22's
example decides it: `name_type: kind<identifier_type>` admits `identifier` and refuses `NewYork`. `choice` is the sum,
and its `variants: [type_kind]` is built over the kind; `enum_type` is an atom with a `members` field on the same
terms. What `members` shows is only fact 4, that the set is closure-relative.

**What one level makes of it.** One namespace is one store, so one atom:

```
reference => atom & { of: type_name }    -- a qualified name; past entries, keys and fields, the referent IS-A `of`
kind      => <T> !reference { of: T }    -- the one-segment spelling
ref       => <T> !reference { of: T }    -- the qualified spelling; the two may be one
```

- `of` names any entry of the one namespace, constructor or type: `kind<record>` and `ref<operation>` are the same
  declaration with a different `of`, which is "What `kind<T>` means" above, now with its kernel shape.
- `type_ref` stays as the application form. A closed one lifts to a minted entry as it does today, so no slot holding a
  closed name is typed by it; an open one, in a template body or an alias's target, is still a `type_ref` in resolved
  output, and that is the one place it is needed.
- `members` goes. With one closure the declaring closure is the judging closure, so the index is computable at ingest
  and says nothing a reader cannot recompute; if a resolved form keeps it for inspection it is honest for the first time.
- The slot's meaning still comes from `of`, not the spelling: an `of` in the `type` family makes the slot a type reference
  (structural identity, delivered as a `TypeRef`); any other `of` names a value. **`namespace` is where that rule is not
  yet clean.** Upstream already lists `myapi`, a value, under `kind<api>` and `kind<namespace>` beside the namespace types
  (SPEC-FEEDBACK #23, stated there as a consequence for the spec to decide), which supports the merge: a kind reaches
  values today. But under one level a namespace type and its values share a closure, which the ladder prevents (a meta
  layer cannot apply its own constructor), so `!reference { of: namespace }` admits both; and `namespace` sits under
  `top`, not `type`, so the rule above reads it as naming values and drops the namespace types #22 and #23 put in
  `type_kind`. The atom has to say which before it is written.
- **`kind` and `ref` cannot differ by spelling alone.** Two templates expanding to the same `!reference { of: T }` make
  `kind<X>` and `ref<X>` one application with one canonical content (§8.2), so a slot cannot know which was written. A
  one-segment restriction is then a field of `reference` (a segment bound, say), or it goes and the two spellings are
  one name.

**Why this is a second proof.** The verdict's first argument was that one checked rule replaces five that leaked. This
one is from the other end: the kernel's own vocabulary, designed under the ladder, converges on one reference atom at
exactly the point where the two stores become one, and the cost of not collapsing shows up inside the atoms as a dead
spelling and a lying index. A design that is right tends to show the same simplification from both directions.

**Not for Revision 38.** Writing `reference` while `of` resolves in two stores would put the store choice in a field
name (`{ name | of | kind }`) and carry the ladder inside the atom. Under the ladder the honest Revision 38 kernel is the
one it has, with `ref` confined to kernel-governed schemas (PR #760).

### Templates and dependent fields: one mechanism

A pinned dependent field *is* a template parameter: `ns ^ { of?: = method_map }` and `ns<method_map>` say the same thing,
and `NamespaceTemplateProbe` measures the second. A base meant to last shouldn't carry two mechanisms for one idea,
because two mechanisms drift: each gains rules the other lacks, and authors have to know which to reach for.

**The direction: one parameterised record, with the parameter fixed at one of two times.**
- **Fixed per application** is a template: `box => <T> { v: T }`, closed by `box<order>`. The argument is part of the
  application's identity (§8.2) and is never written in a value.
- **Fixed per value** is a dependent field: `record_field => { type: type_kind  value?: type }`. The argument is a field
  of each value, written (or injected by a pin), and is not part of the record type's identity.

This carries RECORD-TEMPLATES option B one step further. There a record template is a `record` with `parameters`; here
a record's parameters and its kind-typed fields are one list, each entry saying when it is fixed. `<T>` stays as the
spelling for "fixed per application", so no source changes shape.

**What has to be checked before committing:**
- **The wire.** A parameter fixed per application is never written; a dependent field is, unless pinned and injected. A
  pinned dependent field and a template parameter must therefore produce the same wire form, which an injected pin
  (`of?: = …`) does.
- **Binding.** An injected field has no component in a bound class today unless one is declared. A parameter fixed per
  application has none either, so the two agree if an injected kind-typed pin needs no component.
- **Identity.** Two applications with equal arguments are one entry (§8.2). Two values with equal dependent fields are
  two values. The unified model must keep both.
- **Value parameters.** `status: int32 = S` is a template's value parameter. In the unified model it is a pinned
  value-typed field, which needs the `record_field` split RECORD-TEMPLATES names (a literal, or a reference to a
  parameter).

## Pulling the thread: does the data/schema line blur too?

This project's rule is "data can name a schema; only a schema can name a type" (`CLAUDE.md`). The probe says the
strong form of that is already untrue:

| case | outcome |
|---|---|
| user schema imports core, declares `pick => { t: kind<type> }`; data writes `!pick { t: order }` | admitted |
| the same, `t: no_such` | refused: "'no_such' is not a member of this kind" |
| the same, `t: record`, a constructor | refused: not a member |
| the same schema adds `rpick => { m: ref<order> }` | refused: "has an unresolved reference 'ref'" -- core declares no `ref` since tson-java PR #760, the closure holding no value entry to name |
| data governed by `meta.tn`: `!record { fields: { sku => { type: no_such } } }` | admitted: grammar and shape judged, membership not |

**A data document can hold a checked name for a type today.** Membership is judged over the governing schema's
closure. This is the "value type for naming a type in data" that `CLAUDE.md` records as weighed and set aside. It
exists through core's own `kind`, which keeps it in an ordinary schema's reach now that PR #755 refuses importing
the kernel. A reference to a value does not, under the ladder: it names an entry holding a value, and only a
kernel-governed schema can declare the namespace type that puts one there, so a schema that governs data has none to
name. Under one level it does ("The reference atom", above).

**So what separation is left?** A data document *declares nothing*: no name in it can be resolved against by anyone
else. It can *refer* to types (kinds), and under one level values (refs), in the namespace it names. The line is between a
document that **exports a namespace** and one that **only refers into one**, not between "may name a type" and "may
not".

**And that line blurs too.** A document can have names of its own that it doesn't export: local bindings. The agent
plan, below, is exactly that. So the restatement this experiment arrives at:

> A document that exports a namespace is a schema. A document with at most a local scope is data. Any document may
> refer to a type or a value in the namespace it names.

**The separation is kept, on that footing.** Data governed by a schema is what this note keeps: one level applies to
schemas, and a data document still declares nothing others resolve against, still names its schema with `!!schema`,
and still can't extend the language it is read in. What the probe changes is the *reason* for the line, which moves
from "may name a type" to "exports a namespace". A schema refers into its imports. A data document refers into its
`!!schema`, and into a §7.8 scope push where a value names one.

### The API and the deployment descriptor

`CLAUDE.md` keeps these apart with an argument that doesn't survive the probe: the API must be a schema because it
names types, and a descriptor is data because it names none. Data can already hold a checked type name, so on that
argument alone an API could be data.

**The arguments that do hold have nothing to do with levels:**
- §8.2: no document may name, import or otherwise select the policy it is judged under.
- §3.5: an API is immutable once published and pinned by identity, while a policy has to move without minting one.
- A descriptor configures one instance, not a contract, and nothing may name it.

**The restatement draws the line in the same place, for those reasons.** The API exports a namespace that others refer
into: a binding's `ref<method>`, an agent plan's methods, `TsonApiCoverage` claiming operations by name. So it is a
schema. A descriptor exports nothing and nothing names it, so it is data.

**What opens up is one-directional.** A descriptor could refer *into* the API: `ref<operation>` for what this instance
serves, or `TsonApiCoverage.notServedHere` exemptions moving from code into configuration. That is data referring into
a namespace it names, which the rule allows. The API naming a descriptor still isn't, and neither is a descriptor
naming the schema whose policy it sets.

**Two inconsistencies the probe turned up**, worth asking upstream:
- **Membership depends on the governing schema.** At a kind slot under a user schema, membership is judged. At the same
  kind under `meta.tn`, reading a type definition as data, it isn't.
- **The not-a-member message leaks closer-minted names.** It lists every member, including names like
  `array_text_1_3600e7d5` that no author wrote.

## API descriptions and HTTP routes over schema records

**Today:**
- `orders-api-1.tn` is governed by `meta-http.tn` and imports `order-1.tn`.
- An operation's `request` and `response.body` are kind slots naming records in that ordinary schema, and the resolver
  judges them.
- What the resolver can't judge is checked in Java at startup:
  - path template against PATH parameters (`TsonApiDescription.requirePathMatchesItsParameters`);
  - in the meta-service sketch, placement against the request record's fields (`Routes`, `Placement`).

**Under one level:**

```
!!id:"https://schemas.example.com/2026/38/app/orders-api-1.tn"
!!import:"https://tson.io/2026/38/io/ltr8/http/http.tn"       -- operation, response, http_method, uri_template
!!import:"https://schemas.example.com/2026/38/app/order-1.tn"
!!import:"https://tson.io/2026/38/m/core.tn"
{
  page          => <T> { items: [T]  next?: uri  total: int32 }
  order_page    => page<order>
  paged_get     => operation & { method: = GET }                  -- an application's own endpoint shape, declared here
  list_orders   => !paged_get { path: "/orders"  responses: [ !response { status: 200  body: order_page } ] }
}
```

**What changes:**
- The description is a schema like any other. Its records, its templates and its own endpoint shapes (`paged_get`, a
  refinement of the ordinary record type `operation`) sit beside its operations, which are value entries.
- Today `paged_get` would need a meta layer per application. That is why a CRUD family is written out operation by
  operation.
- Payload names are kind slots, judged once the closure's facts are in. `order_page` is an alias, resolved like any
  entry, so the alias-not-inline rule (#751) still holds: an operation body is a value body and mints nothing.
- `list_orders` waits on `paged_get`, which waits on `operation` from the import. Nothing is asked of the order the
  entries are written in.

**What stays dependent, and staging doesn't touch.** Some checks relate two fields *of one value*:
- a `{segment}` in `path` must be a PATH parameter's `name`;
- a placement's `query: [status]` must name a field of the record at `request`.

These depend on a value, not on a declaration, so no ordering of passes reaches them. Reaching them needs a *relative*
reference: a ref rooted at a sibling field, along the lines of "a field of the record named at `request`".
- `ref<T>` already walks record values and namespace keys, and `field_map` is a namespace. So the absolute form,
  `order_query.fields.status`, may already be expressible; the probe hasn't tested it.
- The relative form is new.
- Until it exists, those checks stay in Java, which is the tier `CLAUDE.md` gives them: "an unsound description has no
  model".

**Routes become a projection.** `Routes` reads an `!api` into a table. Under one level that's unchanged. What changes
is that the description, its vocabulary and the records it names are one closure with no `!!meta` hop, so the gateway
reads one schema and its imports.

## The agent and the agent VM

The agent sketches are the strongest evidence in this note, because they already describe one-level documents and
work around the ladder to do it.

**The plan is a one-level document.** `agent.tn`'s own doc defines the plan surface as "an ordinary TSON map document,
`name => !method { request }` entries and a final `return`". Read against this note:
- the steps are local declarations, def-before-use;
- each applies an interface's method like a constructor;
- `login.user.id` is a qualified name into an earlier step's response.

Today the surface "reads schemaless, method names as preserved unknown type refs", the meta-service README's decision
4. That sidesteps the ladder, with the hazard that a method named `date` parses as the built-in.

**Its typing is read-defer-resolve, one step at a time.**
- A step's response type comes from its method, which is its head, so it is known once its AST is read, before its
  body is processed.
- A selector into it is then typed in one forward pass, which is what `agent.tn` already specifies.
- "Plans cannot branch on values" is what makes this the easy end of dependent typing: each step depends on earlier
  declarations, never on values.

**Is a plan data or a schema?** By the restatement above, it's data:
- it has a local scope (step names);
- it refers into an interface;
- it exports nothing, since nobody imports a plan;
- its `return` is its root value.

It is the clearest case of the data/schema line blurring into "local scope versus exported namespace".

**The VM is the plan's resolved form, and its admission is the same algorithm.** `agent-vm.tn` already says an agent
is to a plan "as a resolved schema is to its source":
- pool entries intern names;
- slots follow step order, which is the stage order;
- admission re-runs a forward pass.

If the schema resolver is itself staged, the agent verifier and the resolver are one algorithm over different
documents.

**What the agent needs that doesn't exist yet:**
- The pool's `type: name` and `method: name` are `text` patterns today. They want to be `kind<…>` and `ref<method>`,
  judged on read. Kinds in data work today (measured); refs in data are `NOT_IMPLEMENTED`.
- **The namespace they refer into is the interface's, not the plan's own schema.** Today a kind or a ref resolves in
  the *governing* schema's closure, and a plan names its interface in a field, `interface: uri`. A field whose value
  sets the scope for later fields is the dependent pair again, the same shape as last turn's `of` design.
- The answer TSON already has is to make the scope visible instead of computed. Either:
  - the plan is governed by a derived schema that imports both `agent.tn` and the interface, the pattern
    `orders-wire-1.tn` uses for RPC packets; or
  - `steps` carries a §7.8 scope push naming the interface.

  Both fit decision 1 of the meta-service README (one interface per plan, reached through `extends`). Both retire
  `interface: uri` as a field.

## Order of work

Each step is checked against the `-resolved.tn` fixtures before the next starts, so a step that moves a fixture is
caught at that step.

1. **Specify the dependency model:** shape and value, the edges, the invariant that a shape never waits on a value, what
   a cycle is, and the limits (dependency depth for untrusted schemas, a template-expansion bound, sibling-first for a
   free dependent field in a streamed data document).
2. **Prove it on the kernel.** `meta-kernel-resolved.tn` comes out as a fixed point under read-defer-resolve over the
   pre-loaded kernel shapes. Until it does, nothing below starts.
3. **Remove the ladder:** `!!meta` becomes the kernel-revision directive (or `!!import` classifies), and placement and
   direction of service go. `core.tn` imports the kernel instead of redeclaring `void`.
4. **Remove `data`.** Value entries become instances of ordinary types; the binder split is redrawn.
5. **Replace `kind_type`, `ref_type` and the name-only `type_ref` with one reference atom,** `kind<T>` and `ref<T>` its
   spellings and `members` gone ("The reference atom").
6. **Add dependent fields, with templates unified into them.** `record_field.value` gets its real type.

Steps 1 and 2 are the correctness work. Steps 3 to 6 are mostly deletion once they hold.

## What this would change in the project's rules

Not edited; recorded here until the direction is decided.
- **`CLAUDE.md`, "Data can name a schema; only a schema can name a type":** measured to be untrue in its strong form.
  The restatement above ("exports a namespace" versus "refers into one") is the candidate.
- **The import-level rule** tson-java PR #755 added would be withdrawn under one level: it is a ladder rule. A data
  document's kinds reach it through core today and through the one namespace then.
- **The reference rule** tson-java PR #760 landed (core.tn declares no `ref`; a reference is declared only in a
  kernel-governed schema) is withdrawn with it: under one level a schema that governs data has value entries, so
  `ref<T>` is valid everywhere and core.tn needs no sibling of anything.
- **The "Describing an API" section's** argument that "the meta layer owns the vocabulary its constructors use" is a
  consequence of levels. Under one level, `http.tn` returns as an ordinary library.
- **"Describing an API"'s** wiring note that `metaNameBinder`, not `bindings`, binds the description's vocabulary, and
  that "a meta layer's vocabulary is a separate namespace on purpose", goes with `data`.
- **The "Deployment descriptors" section's** argument that a descriptor is data because "it references no types"
  moves to the reasons above: self-certification, immutability, and that nothing may name one.

## Open

- **The shape/value boundary**, entry by entry: the table under "The dependency model" is the proposal, and every
  kernel constructor's facets need assigning to one side or the other before step 1 is done.
- **Unifying templates and dependent fields:** the four checks under "Templates and dependent fields".
- **Self-identification.** Keep `!!meta` as the kernel revision (renamed or not), or classify by `!!import`.
- **The bootstrap.** Does the kernel keep a directive naming itself, or is pre-loading stated as the one closed cycle?
- **Re-resolving a resolved form to itself** as a second fixture check, once its readers come from the fixture.
- **The cycle message**, and whether a circular dependency through an import is the same error as one within a
  document (today a cycle through `!!import` is refused by [TSON-DATA] §2.2.3).
- **§9.1:** a bound on read-dependency depth, and where it sits beside nesting depth.
- **Relative references** for the checks above: a sibling-rooted `ref`, or the Java tier for good.
- **A guard on extending the kernel.** Placement goes, so any schema importing the kernel can declare a constructor:
  an explicit marker, a deployment policy, or nothing.
- **The binder split** once `data` goes: by the type, by the use, or one binder.
- **The reading-side §5.6 rule** that walks `orders.get` through a record's one namespace-typed field, or parent
  slots in its place (`SLOTS.md`), which also absorb an interface's `extends` and add acyclic lookup edges to the
  dependency model.
- **Streaming a free dependent field:** require its sibling first, or buffer.
- **The two upstream inconsistencies** above, which are worth filing regardless of this direction.
- **#22's exclusions under one reference atom:** "not a constructor whose instances are types, not a template over
  another constructor" as one predicate on the referent (has instances), and whether `kind<type>` then admits an
  abstract type, which SPEC-FEEDBACK #22 leaves open today.
- **`namespace` under one reference atom:** whether `!reference { of: namespace }` names the namespace types, their
  values, or both, once one closure holds both ("The reference atom").
- **Where `members` is computed:** fact 4 says over the declaring schema's closure; the kernel's `kind_type` doc says
  "over the governing schema's closure". One of the two should change, whichever way the atom goes.
