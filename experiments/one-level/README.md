# One level: schemas without the meta ladder

**Status: design direction, adopted under the rule below.** Measured on Revision 38 by
[`OneLevelProbe`](java/io/ltr8/tson/http/experiment/onelevel/OneLevelProbe.java) and
[`NamespaceTemplateProbe`](java/io/ltr8/tson/http/experiment/onelevel/NamespaceTemplateProbe.java), which pin every
outcome quoted here. Nothing here resolves in the proposed form, and nothing in this project depends on it.
[`SLOTS.md`](SLOTS.md) reads the same design through Self's and Newspeak's slots, as a language designer would, and
[`COMPUTATION.md`](COMPUTATION.md) keeps some future-dreaming about computation that sits beside TSON without entering
it. [`IDENTITY.md`](IDENTITY.md) is a research map for identity and delegation, for agents acting on people's behalf.

**Revision 38 took one step of this inside the ladder.** It has no `data` kind: `namespace` is a constructor under
`top` whose instances are maps (§4.1), so a governed schema's entry may hold a value, written as the bare map and walked
as `orders.get`. That is this note's "entries are types or values" step, confined to namespaces.

## Verdict

**Design rule: correctness over churn.** TSON has no users yet, and its base is meant to last for generations. A
rewrite of the spec and of every implementation is an acceptable price for a more correct base, so nothing in this note
is weighed by how much it changes: re-pinning every meta layer, rewriting the corpus, and redoing the resolver all count
for nothing against a rule that is more correct.

**Under that rule, one level is warranted:**
- **One checked rule replaces five that leaked.** Visibility, placement, direction of service and the meta-as-import
  cycle check held the ladder up (measured below), and an import across levels went through all four, so Revision 38
  added a fifth, the level of an import (§2.2.3). Read-defer-resolve with a cycle check is one rule, and it is checked.
- **The bootstrap becomes an instance of the rule.** The kernel's pre-loaded self-reference is the one cycle closed by
  hand, and its resolved form is the fixed point, instead of being an exception four rules carve out.
- **A value entry needs no mark of its own.** Revision 38 lets a governed schema's entry hold a value only as a
  namespace map, since a namespace type is the one constructor whose instances are not types (§4.1). One level lets an
  entry be a value of any ordinary type, which the kind system already tells from a type ("Entries are types or
  values").
- **The types become right, and `value_type` narrows to one slot.** The `sibling!` dependent-field syntax gives
  `record_field.value` (`value?: type!`), and so every default and pin, its real type. With a constructor and its
  instance in one document, every `value`-typed facet in `meta.tn` (`decimal_type.min`, `float_type.min`,
  `date_type.min`, `ipv4_type.within`) gets one too. What is left of the escape hatch is `type_argument.value`, whose
  type is a parameter's, reached through a reference (see "Two limits on `field!`").
- **Most coherence rules become per-value checks.** A facet refined by a sibling (`max_items: !non_negative_integer ^
  { min: min_items! }`) turns a prose schema-load check (`min_items <= max_items`) into ordinary range validation. Not
  every rule fits: a bound reached from either of two alternatives in a field group does not (see "Per-value facet
  checks", below).
- **The fixture check gets stronger.** A resolved form trusts only the pre-loaded kernel.
- **The reference vocabulary converges.** `kind_type`, `ref_type` and the name-only `type_ref` are one atom over a
  qualified name, kept apart only because `of` resolves in two stores; under the ladder `ref<T>` is dead at the level
  data lives and the `members` index names the wrong level's types ("The reference atom", below).
- **Explicitness survives at a finer grain.** The header used to say a document *may* declare constructors; each entry
  now says it *is* one, by composing `top`. A data document still declares nothing, and a document's kind is still in
  its header.

**Half of `type!` is separable from one level.** `record_field.value: type!` needs the shape of the type its sibling
names, and under the ladder that is always a type in the closure, never a constructor declared beside it: a value
waiting on a shape, which Pass 2 already does. A default naming a type declared later, or the record being declared,
is the same edge. What does need one level is `meta.tn`'s facets: typing `decimal_type.min` as `number` needs the
constructor and its instance in one document, which is the ladder's removal.

**So it is two revisions, not one.** The dependency model, the separable half of `type!`, and three decisions made on
paper ("Decisions before the ladder goes") are Revision 39. Removing the
ladder, and with one store in hand the reference atom, is Revision 40 ("Order of work"). Two of the deletions carry
correctness questions of their own, who may extend the kernel and which names a schema may use, and landing them in
the revision that introduces the dependency model would make it impossible to tell, when a fixture moves, which
decision moved it.

**The correctness work is the dependency model** ("The dependency model: shape and value", below): what an entry's
reading may wait on, and what counts as a cycle. Everything else in this note rides on that definition, and it is
specified, and proved on the kernel, before anything is removed.

**Under a rule weighted by cost, the verdict would differ.** Most of the problems that motivated this have local
fixes: namespaces became a constructor inside the ladder (Revision 38), and the host-shape gap is a bug fix. That is
recorded so the rule stays visible as the reason for the verdict, and so the verdict is revisited if the rule ever
changes.

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

The probe's constructor is a namespace type, `steps => namespace & { key_type: = identifier  value_type: = step }`, the
shape every meta layer's vocabulary has.

| case | outcome on Revision 38 |
|---|---|
| constructor declared in a meta layer, applied in a schema it governs | resolves |
| a meta layer applying its own constructor | refused: "'!steps' does not resolve against the structure namespace" (§3.3.1) |
| an ordinary schema declaring `steps => namespace & { … }` | refused: "supertype 'namespace' names no type this schema declares or imports" |
| an ordinary schema importing the kernel, with core or declaring a constructor | refused at the import: "an !!import stands at the importer's governing level" (§2.2.3), before core's `void` collides or placement (§2.2.2) is reached |
| a schema whose `!!meta` names itself, declaring only types | refused: "is part of an import cycle" ([TSON-DATA] §2.2.3) |
| in one governed schema, an instance naming a type declared beside it | resolves; a kind slot naming the namespace value beside it is refused: "'s' is a value of the namespace type 'steps', a map of names -- a value and not a type" |

**What the table shows:**
- **Separate mechanisms hold one ladder up:**
    - visibility: the kernel's names aren't in an ordinary schema's namespace, so `namespace` is simply undeclared;
    - placement: a constructor may be declared only under the kernel;
    - direction of service: a schema's `!` targets come from its meta, never from its own entries;
    - the import-cycle rule, which counts `!!meta` as an edge;
    - and a fifth, the import's level (§2.2.3): an ordinary schema imports no kernel-governed one, so the kernel's names
      never reach it and placement is reached by no loaded document.
- **The kernel escapes the first four by being pre-loaded.** Its own doc says so: "the circularity is closed by pre-loading
  rather than by resolution". §2.2.2's "when a schema is its own meta, the two namespaces are the same entry set" is
  true only for the one document that is pre-loaded.
- **Ordering is already solved inside one document for types.** An instance naming a type declared beside it resolves,
  and so do recursive types. The ladder only constrains *constructors*.

## What collapsing it would remove

**From this project:**
- **The two-file split for an API.** The meta layer (`meta-service.tn`) exists as a separate document because its
  constructors may only be declared under the kernel. With one level, `http.tn` is an ordinary library declaring
  `operation` as an ordinary record type beside `http_verb`, `status_code` and `path_template`, and an API description
  imports it like any other schema. No kernel extension is involved (see "Entries are types or values").
- **The HTTP vocabulary inside `meta-service.tn`.** It is declared in the layer because of levels: a meta layer
  imports no ordinary library (§2.2.3). Under one level the rule is withdrawn and the vocabulary returns to an ordinary
  library.
- **The meta-service experiment's two documents.** `meta-service.tn` and its examples would be one library and its
  users, with no `!!meta` between them.

**From the kernel, `meta.tn`, `core.tn`, and the spec:**
- the placement rule (§2.2.2);
- the direction-of-service rule (§3.3.1's split between the structure namespace and the type namespace);
- the import-level rule (§2.2.3);
- the pre-loading special case: pre-loading becomes the general bootstrap, the kernel being the one document whose
  read-dependency cycle is closed by hand;
- **sibling duplication** (§3.3.5, §5.10): `core.tn` no longer redeclares fresh siblings of the kernel's `void`,
  `boolean`, `integer`, `text`, `regex`, `iri`, `kind` and `doc`, nor of `meta.tn`'s `set`, and §5.10's rule that two
  same-named entries with the same resolved body are one type when parameter types are compared is deleted;
- **`value_type` and `value`** (§4.2, §7.4): out of `record_field` through `type!` (Revision 39) and out of `meta.tn`'s
  facets through mutual atom declarations (Revision 40), leaving `type_argument.value`.

Namespaces are not on this list: Revision 38 made `namespace` a constructor whose instances are maps, inside the
ladder, and one level keeps it ("Namespaces stay a constructor", below).

**What stays:** the kind checks. A kind slot refusing an instance declared beside it is already one-document behaviour.

## The proposal

### One namespace per schema

- A schema's namespace is its own entries plus its imports'. `!` targets resolve in that same namespace. An entry's
  `!T` names either a constructor, making the entry a type, or an ordinary type, making it a value.
- `!!meta` stops being a rung. A schema that wants the kernel imports it, which is what `meta.tn` already does
  (`!!import` of the kernel beside its `!!meta`). What still has to say "this is a schema" is below, under "How a
  schema identifies itself".
- `core.tn` imports `meta.tn` (which imports the kernel) instead of redeclaring `void`, `boolean`, `integer`, `text`,
  `regex`, `iri`, `set`, `kind`, and `doc`. A user schema then imports `core.tn` and gets the entire vocabulary once,
  with one canonical identity per type.

### Two kinds of dependency

| | example | cycles |
|---|---|---|
| **link** | `order => { items: [line] }`: a name at a type position | fine: recursive types; linked after everything is declared |
| **read** | `get => !operation { … }`: a body whose reader *is* a declaration | an error, except the kernel's own, closed by pre-loading |

Today's levels are a coarse, declared ordering that guarantees every read dependency points into another document.
That's the same move as GHC's Template Haskell stage restriction, where a splice may run only code from an imported
module, and Racket's phase levels (Flatt 2002), where a module's dependencies are declared per phase. One level
computes the ordering instead, and the next section says exactly what is ordered.

### The dependency model: shape and value

**The central definition.** A read dependency is never on a whole entry. Reading a value needs its type's **shape**,
never the type's **values**. Splitting each entry into the two, and stating which waits on which, is what lets the
cycle check refuse exactly the unsound schemas: without the split it either refuses sound ones or admits unsound ones.

**Shape** is what a reader needs to read a value of the type:

| entry | its shape |
|---|---|
| a type built by a constructor (`order => { … }`, `!integer_type { … }`) | its constructor; field names, field types as names (or `sibling!` markers), optional and voidable marks, groups; key, element and value types; parents; family selectors and their pins |
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
- **value → shape (read).** A value waits on the shape of the type it is read against. For a dependent field
  `value?: type!`, reading `value` waits on the shape of the type named by the sibling `type` in that record instance.
- **value → value (intra-record read).** Inside one record instance, a field whose type (`value?: type!`) or facet
  (`max_items: !non_negative_integer ^ { min: min_items! }`) dereferences a sibling `S!` waits on `S`'s value in that
  same record instance.
- **names (link).** A type name inside a shape (a field's type, an argument, a pinned kind-typed sibling) is a link. It
  orders nothing, which is why recursive types and mutual constructor/instance pairs (`decimal_type` and `number`) are
  sound.

**The invariant: a shape never waits on a value.** Values are leaves of the entry-level graph, so an entry cycle can
only run through shapes, and every value can be read once the shapes are closed. Within a record, `sibling!` edges
order the fields of that record instance acyclically. Two cases look as if they break the shape-level invariant, and
both are held to it by a rule that already exists or is stated here:
- **A family selector's pins are part of the base's shape,** since dispatch reads them. §5.2 already confines a selector
  to an atom or an enum, whose shapes never wait on a user value, so the pins are read with readers that are always
  closed.
- **A pinned dependent field (`type?: = order` with `value?: type!`) is part of the type's shape.** Its sibling is
  typed by a type reference (`type_kind`, or a `kind<C>`), so its value is a name: a link, not a read. A type-position
  `sibling!` whose sibling isn't a type reference is refused at its declaration, and so is a pin that is not a name
  (an inline constructor application), since reading it would make the shape wait on a value.

**A cycle is a cycle of shape → shape edges (or intra-record `sibling!` edges),** refused naming the entries or fields
on it. The kernel is the one exception: the shape of `record` is described by a `!record`, and that cycle is closed by
pre-loading the kernel's shapes.

| declaration | edges | verdict |
|---|---|---|
| `point => { x: int32  next?: point = { x: 1 } }` | the default (a value) → shape of `point` | sound: a default never makes a cycle |
| `frame => { origin: point = { x: 0  y: 0 } }`, `point` declared after it | value → shape of `point` | sound: order of declaration is irrelevant |
| `decimal_type => atom & { min?: number … }`, `number => !decimal_type {}` | `decimal_type` links `number`; `number`'s shape → `decimal_type`'s shape | sound: `decimal_type`'s shape is a kernel `record` |
| `step => top & { takes: type_kind }`, then `s => !step { takes: order }` | shape of `step` → kernel `record`; `s`'s value → shape of `step` | sound |
| `order_field => record_field ^ { type?: = order }` (`value?: type!`) | shape of `order_field` names `order` (link) | sound |
| `record_field { type: point  value: { x: 0 } }` (`value?: type!`) | `value` → `type` sibling's value (`point`) → shape of `point` | sound |
| `a => b`, `b => a` | shape of `a` → shape of `b` → shape of `a` | cycle: refused |
| `c1 => !c2 { … }`, `c2 => !c1 { … }` | shape of `c1` → shape of `c2` → shape of `c1` | cycle: refused |
| `bad => { a: b!  b: a! }` | field `a` → field `b` → field `a` | intra-record cycle: refused |

**What the model guarantees:**
- **Termination.** Shapes are finite and acyclic apart from the kernel, intra-record `sibling!` graphs are finite DAGs,
  values are leaves, and nothing computes. Template expansion is the one place a bound must be stated (open below).
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
   fields (`sibling!`), annotation values. Within a record with dependent fields, independent siblings are read first,
   then the `sibling!` fields that depend on them. Then kind slots are judged for membership, and references resolved.
   Nothing here can wait on a shape, because all shapes are closed.

**"Partially where required"** is step 2 doing only what is needed, on shapes alone. A kind's membership needs every
entry's kind, and an entry's kind comes from its head (`!operation`), which is known once the AST is read. So
`kind<operation>` can be judged without any operation's body having been processed, and judgement never waits on
itself.

**This is the same algorithm wherever it runs.** A schema's own source, its resolved form (below) and an agent plan are
all read this way. The only difference is what is pre-loaded: the kernel for a schema and a resolved form, the
kernel plus the interface for a plan.

**Why the dependent part stays manageable:**
- **The phase distinction holds between entries.** The dependency is on *declarations* (between entries) and on
  *already-read sibling values* (inside one record instance), and the only reduction is substitution.
- **No term-level computation.** `Type : Type` is dangerous where types contain functions: Girard's paradox builds a
  term that never normalises from λ, Π and `Type : Type` alone, with no recursion needed. Cardelli's polymorphic
  λ-calculus with `Type : Type` is a sound programming language but inconsistent as a logic, and its type checking is
  undecidable, because deciding type equality may need evaluation that doesn't terminate. TSON has no term-level
  functions, so self-description (the kernel's `record` described by a `!record`) can't loop through evaluation.
- **Termination narrows to template expansion,** the one type-level reduction: a template is a function over types,
  and expanding it is beta-reduction. It has to be bounded with or without levels, and is, by §5.10.1's
  regular-recursion rule.

### Prior art

The design corresponds to well-studied work in programming languages and type theory. These are analogies, naming what
the design borrows and where it stops, not foundations it rests on:

- **One reflexive loop instead of a regress (ObjVLisp).** Smalltalk-80's metaclasses stack a class on a metaclass on
  `Metaclass`; ObjVLisp (Cointe 1987) ends the regress with one loop, `Class` an instance of itself. The kernel is that
  loop: `record` is described by a `!record`, the one cycle closed by hand.
- **Strict metamodelling and the clabject (Atkinson and Kühne).** The ladder is strict metamodelling, as in OMG MOF's
  four layers, where every element is an instance of exactly one element one level up. Atkinson and Kühne's deep
  instantiation drops the fixed levels, and their *clabject*, an element that is at once an instance and a type, is what
  `integer` is: an instance of `integer_type`, and the type of integer values. One level is that move, inside one
  namespace.
- **Prototypes and lattices (Self, CUE).** Self (Ungar and Smith) has no classes: an object is a clone of a prototype
  with slots changed, which is how a refinement reads (`SLOTS.md`, which also takes Newspeak's module model, not its
  class-based object model). CUE (van Lohuizen) puts types and values in one lattice, unification being meet. TSON's
  `&` and `^` act like meet among types (`integer` to `uint8` narrows facets), but TSON keeps two lines CUE erases: a
  schema exports a namespace and data doesn't, and applying a constructor isn't narrowing. `!integer_type {}` transfers
  kind, not supertypes (§4.1), so `integer_type`'s instances are types and `integer`'s are values, which is the
  boundary the kind rules stand on.
- **A value carrying its type (Abadi, Cardelli, Pierce and Plotkin).** `scoped` (`dynamic`, `declared`, `extern`,
  §7.8) is their `Dynamic` (1991): a dependent pair `Σ (T : Type). T` whose type component is visible, so the value is
  checked in full against the `!T` it names, and `scoped` narrows `T` to the types of the namespaces it admits. It is
  not an existential package (Mitchell and Plotkin), which hides `T`, and not gradual typing's `?` (Siek and Taha),
  which defers checks to casts: nothing at a scoped slot is admitted unchecked.
- **Dependent records and universes (de Bruijn, Martin-Löf).** A record with `sibling!` (`{ type: type_kind  value?:
  type! }`) is a dependent record type: a telescope `(x1 : A1, x2 : A2(x1), …)` (de Bruijn), read as an iterated Σ.
  `type_kind` is a universe à la Tarski: its values are names, codes for types, and `type!` is the decoding `El`. That
  is why a type-position `sibling!` waits on a name, a link, and never on a value that would need reading first.
- **Per-value bounds are dependent contracts (Findler and Felleisen).** `max_items: !non_negative_integer ^ { min:
  min_items! }` is checked once `min_items` is known, as a dependent contract (Racket's `->i`) checks a result against
  the arguments it was called with. Nothing is proved: the bound is a concrete value when the check runs.
- **The phase distinction (Harper, Mitchell and Moggi).** Their phase distinction keeps a module's static part from
  depending on its dynamic part, and "a shape never waits on a value" is that rule between entries. Inside a record it
  is crossed on purpose, a `sibling!` facet being a type that depends on a value, and the crossing is confined to one
  record instance and checked value by value, never at declaration.
- **Checking, not inference (Pierce and Turner).** Bidirectional typing separates checking a term against a known type
  from synthesising one. Values here have only the checking direction: a value is read against a closed shape, and
  nothing about a shape is inferred from a value.
- **Total by design (Dhall).** Dhall, roughly System Fω without general recursion, terminates by construction rather
  than by a check. TSON has no term-level functions at all, and bounds its one type-level reduction by a rule.
- **Type parameters and manifest type members (Leroy, Scala's DOT).** A template parameter fixed at application
  (`box => <T> { v: T }`, closed by `box<order>`) corresponds to a dependent field pinned to one type (`{ type?:
  type_kind = order  value?: type! }`), as a type parameter corresponds to a type member, and a pinned member is
  Leroy's manifest type (1994), the encoding Scala's DOT calculus builds on. A correspondence, not an identity:
  "Templates and dependent fields" lists what has to hold before the two are one mechanism.

### Dependent fields (`type!`) and the elimination of `value_type`

#### The `field!` syntax

In a record definition, a postfix `!` on a field name, written **`sibling!`**, dereferences the value of the sibling
field `sibling` in the **same record instance**. Postfix `!` is the dual of prefix `!`: prefix `!type` applies a type
to a following value; postfix `field!` projects a sibling field's value into a type or constraint slot.

Because `!` is already a special token in the lexer ([TSON-DATA] §7.2.5), `type!` lexes as `unquoted-token("type")`
followed by an adjacent `special-token("!")` with zero lexer changes. Adjacency decides which `!` is meant:
- Prefix `!` MUST be adjacent to the token after it ([TSON-DATA] §7.5, [TSON-SCHEMA] §12.3), so a `!` adjacent to the
  token before it and followed by whitespace or a delimiter is unambiguously postfix: `{ min: min!  max: 3 }` can't be
  read as a tag on `max`.
- A `!` adjacent on both sides (`a!b`) is the one ambiguous spelling, and is refused: postfix `!` MUST NOT be followed
  by an adjacent token.
- A voidable dependent field is `value?: type!?`, the voidable `?` adjacent to the `!`.

Why `type!` beats an unmarked bare name (`value?: type`):
- **Zero shadowing:** A record with a field named `text`, `type`, or `size` never silently captures the schema-level
  type of the same name. `value: type` means the schema entry `type`; `value: type!` means the sibling field `type`.
- **Self-documenting dependency:** A reader (and the parser) knows at the token that the slot is an intra-record
  dependent projection, without consulting the enclosing record's field list first.

`sibling!` is legal in two positions inside a record definition:

1. **At a type-ref position (`value?: type!`):** The sibling `type` MUST be typed by a type reference (`type_kind`, or
   a `kind<C>`). For each instance of the record, the expected type of `value` is the type named by `type`.
2. **At a facet value inside an atom refinement (`max_items: !non_negative_integer ^ { min: min_items! }`):** The
   sibling MUST be a scalar field of the same record whose type is compatible with the facet slot. When the sibling is
   present in the record instance, the facet binds to its value; when it is optional and omitted, the dependent facet
   binding is omitted with it.

**The second position is a refinement that varies per value, and two rules stand in its way.** §5.2 forbids an inline
atom refinement at a field-type position, and §5.10 says a parameterised atom refinement (`<N> !integer ^ { min: N }`)
"is no form at all". Lifting it to a synthetic entry doesn't help: an open synthetic captures the declaration's
parameters (§5.3), one entry per application, and `min_items!` differs in every value. So it is validated in place,
and both rules change for it.

#### 1. `record_field.value` gets its real type

In Revision 38, `record_field.value` is typed `value` (`!value_type {}`) and restricted to scalar tokens because the
kernel cannot express "the type named by this record's `type` field" (§5.2, §5.10). With `type!`:

```
record_field => {
  type:      type_kind
  optional?: boolean ~ false
  voidable?: boolean ~ false
  role?:     field_role ~ FREE
  value?:    type!
}
```

- **No prose conformance rule needed:** `{ type: int32  role: DEFAULT  value: "nope" }` fails ordinary field validation
  against `type!` (`int32`).
- **Compound defaults and pins work naturally:** Because `value` is read against `type!` after `type`'s shape is
  resolved, a field typed by a record, array, map, or tuple (`origin?: point ~ { x: 0  y: 0 }`, `tags?: [text] ~ [a b]`)
  is read by that shape's reader. The v1 restriction of `~` and `=` to scalar tokens (§5.2) can be lifted whenever
  desired.
- **It lands under the ladder (Revision 39).** The type `type` names is a type in the holding schema's closure, as a
  kind slot's name already is, so reading `value` is a value waiting on a shape: Pass 2 work, with the intra-record
  `sibling!` order and no cycle check over constructors.

#### 2. Retiring `value` from `meta.tn`'s facets (needs one level)

In Revision 38, `meta.tn` declares atom constructors (`decimal_type`, `float_type`, `rational_type`, `date_type`,
`time_type`, `datetime_type`, `duration_type`, `period_type`, `ipv4_type`, `ipv6_type`, `cidr4_type`, `cidr6_type`)
while their instances (`number`, `float64`, `date`, `ipv4`, `cidr4`, …) live one rung down in `core.tn` and `net.tn`.
Because `meta.tn` cannot reference `core.tn`, every bound and network list in `meta.tn` is typed `value`, requiring the
bespoke eager-conversion prose of §7.4. `type!` doesn't help: no sibling names "the atom this constructor builds".
Nor can `meta.tn` declare `number => !decimal_type {}` itself under the ladder, because its `!` targets resolve in the
kernel's entries, not its own (§3.3.1).

Under one level, each atom constructor and its canonical instance sit together (or mutually resolve in the same pass, as
`integer_type` and `integer` already do in `meta-kernel.tn`):

```
decimal_type => atom & {
  ( min: number | exclusive_min: number )?
  ( max: number | exclusive_max: number )?
  multiple_of?:     number
  total_digits?:    non_negative_integer
  fraction_digits?: non_negative_integer
  members?:         !set_type { element_type: number  min_items: 1 }
}
number => !decimal_type {}

ipv4_type => atom & atom_specification & {
  spec?:      = "https://www.rfc-editor.org/rfc/rfc3986"
  within?:    [cidr4]
  excluding?: [cidr4]
}
ipv4 => !ipv4_type {}
```

**`value` then types one slot, `type_argument.value`.** Its type is the parameter's, and reaching it from a
`type_argument` means following the enclosing application's template name to that template's parameter list: a
projection through a reference, which `sibling!` doesn't do ("Two limits on `field!`"). So `value_type` survives at
that slot unless chained projections are ever added.

#### 3. Per-value facet checks (facet coherence as types)

Revision 38 scatters value-coherence checks across prose: `min <= max` on numeric and temporal types,
`min_length <= max_length` on text and bytes, `min_items <= max_items` on arrays and maps, `min_prefix <= max_prefix` on
CIDRs (§5.3, §5.5, §7.4). With `sibling!` inside a refinement, the simple ones are stated directly in the constructor's
fields:

```
array => product & {
  access_pattern?: product_access_type = INDEX
  size_type?:      product_size_type = VARIABLE
  element_type:    type_kind
  voidable?:       boolean ~ false
  ordered?:        boolean ~ true
  unique_items?:   boolean ~ false
  min_items?:      non_negative_integer
  max_items?:      !non_negative_integer ^ { min: min_items! }
}
```

When an author writes `[text; 5..2]`, the binding record `{ min_items: 5  max_items: 2 }` validates `max_items` against
`!non_negative_integer ^ { min: 5 }`, which fails with the standard range error (`'2' is less than the minimum 5`).
Schema-load coherence and data validation become the same code path. It is a dependent contract, not a proof: by the
time `max_items` is checked, `min_items!` is already a known integer (`5`), so `!non_negative_integer ^ { min: 5 }` is
checked by the ordinary range validator, with no symbolic reasoning ("Prior art").

**A bound reached through a field group doesn't fit.** `integer_type` carries `( min | exclusive_min )?` and
`( max | exclusive_max )?`:

```
integer_type => atom & {
  size?: integer_size
  ( min: integer | exclusive_min: integer )?
  ( max: !integer ^ { min: min!  exclusive_min: exclusive_min! } | exclusive_max: integer )?
  multiple_of?: non_negative_integer
  members?:     integer_member_set
}
```

- `max` can be written, but its refinement names both `min` and `exclusive_min`, which breaks `integer_type`'s own
  group. It is sound value by value only because the group admits at most one and an omitted sibling drops its binding,
  so the refinement can't be checked at its declaration.
- `exclusive_max` can't be written. Its lower bound is `min!` or `exclusive_min!`, whichever is present, and both land
  in the one facet `exclusive_min`. Stating it needs a choice between siblings (`exclusive_min: ( min! | exclusive_min! )`)
  or it stays prose.

So per-value checks retire three prose rules, `min_items <= max_items`, `min_length <= max_length` and
`min_prefix <= max_prefix`, and leave the numeric and temporal bounds, the common ones, in prose unless sibling choice
is added. A modest win, and a separable one: these land under the ladder with `record_field.value`.

#### 4. Typed template bodies: open, and in neither revision

`template => top & { parameters: [template_param]  template: text }` holds a body as text, and §5.10 says why: a
parameter stands wherever a token stands, so `min_items: N` is not a value of any constructor's record shape until it
closes. A typed body would need:
- **every slot of every constructor widened** to accept a concrete value of its type or a parameter reference. That
  changes every constructor's reader, and the one-spelling rule and open-synthetic identity (§5.10, §8.2) would be
  restated over the typed form;
- **for a declaration-time guarantee, reasoning over parameter ranges.** Proving
  `<N: int8> !integer_type { min: N  max: 200 }` sound for every argument before application means proving that the
  value set of `int8` (`[-128, 127]`) lies at or below `200`: refinement subtyping, which Liquid Types and F\*
  discharge with an SMT solver. Interval containment like this one is cheap, but it is still computation over
  parameter types, which the dependency model excludes.

The payoff is real (resolved-output consumers would no longer need the schema parser for a held body, §1.3), but it is
a feature of its own. It's listed under "Open", and neither revision includes it.

#### 5. Namespaces stay a constructor

In Revision 38 `namespace` is a constructor under `top` whose instances are maps (§4.1), so an entry's value is written
and read *as the map*, with no special case. One level doesn't change that, because `namespace` under `top` works
unchanged in one store. The alternative, a namespace kind as an ordinary record whose one field is typed by a pinned
sibling (`ns => abstract { of: kind<namespace>  entries: of! }`), brings back the path `orders.entries.get` and needs a
second, reading-side §5.6 rule to walk `orders.get`. Measured on Revision 38
([`NamespaceTemplateProbe`](java/io/ltr8/tson/http/experiment/onelevel/NamespaceTemplateProbe.java)):

| case | outcome |
|---|---|
| `iface => namespace & { key_type: = identifier  value_type: = meth }`, written `orders => !iface { get => { … } }` | the entry is the map: a `Namespace` of `iface`, holding each value under its key |
| `request: no_such` inside | refused at `/get/request` |
| `ref<meth>` to `orders.get` | resolves; `orders.nope` refused: "'orders' has no key 'nope' -- it holds get" |
| a record field holding a plain map `{identifier => meth}` | not entered: "a map that is no namespace, so it is not entered by name" |
| `ref<meth>` bound to a `String` host component, naming `nobody.get` | refused: "'nobody' is no entry of this schema or its imports" |

**What `field!` is for is variation per value.** A template fixes a slot once per application; `field!` is needed
where each instance of the record differs:
- `record_field.value` (`value?: type!`): every field in `record.fields` has its own `type`;
- sibling-indexed bounds (`max_items: !non_negative_integer ^ { min: min_items! }`);
- an annotation attachment whose value is typed by the annotation's target type.

`type_argument` would want `{ param: ref<template_param>  value: param.type! }`, but that is a projection through a
reference, under the second limit below, so `type_argument.value` keeps `value`.

§7.8's scoped slot is the existing per-value case. There the type travels inside the value (`!T`); a dependent field
puts it in a sibling.

**Two limits on `field!`:**
- **Streaming, and it is a Part 1 rule.** In a telescope `(x : A, y : B(x))`, context extension is sequential: `y`
  cannot be checked until `x` is in scope. In a data document, a free (unpinned) sibling `S` must therefore either
  arrive before any field `S!` that depends on it, or the reader buffers the record's tokens until `S` is read.
  Requiring `S` first in a streamed data document matches how a `!tag` is read first and avoids unbounded buffering; in
  a schema document (read to an AST in Pass 1 anyway) and for a pinned sibling, field order is unrestricted. But
  [TSON-DATA] holds that a record's field order is insignificant, so this is an exception to a Part 1 rule and is
  written there, not implied by Part 2; and [TSON-JSON] needs the same sentence, since a JSON object's member order is
  insignificant too and a streamed JSON reader meets the same sibling.
- **Single segment only.** `sibling!` reads one field of the same record instance. A chained projection
  (`method.request.fields!`) follows a reference into another entry and then into the definition of the type it names,
  which is a different operation spelled with the same `!`, and an entry-level value → value edge the dependency model
  doesn't have. It is in neither revision ("API descriptions and HTTP routes over schema records", below, says what it
  would and wouldn't reach).

### What it costs

Churn isn't counted (see "Verdict"). What is left are properties of the design, and each needs an answer:
- **A document's role is no longer visible from its header,** unless a header opt-in keeps it. Whether a schema
  defines vocabulary becomes visible per entry: an entry composing `top` is a constructor. The spec currently prefers
  the document-level statement ("the ladder keeps that role explicit rather than emergent"), and §11.2 and §11.3 treat
  `!!meta` and `!!import` as the sensitive channel because they select vocabulary. Decided in step 1 ("Decisions before
  the ladder goes").
- **A schema can extend the language it is written in.** Its meaning then depends on itself, so tooling and readers
  have more to hold in their heads.
- **More work per untrusted schema.** A schema named by a request body can declare constructors and use them. §9.1
  would want a bound on read-dependency depth beside nesting depth.
- **Less push toward sharing.** Today one meta layer is *the* `operation`. With one level, any schema can declare its
  own; sharing still works through imports, but nothing pushes toward it.
- **Every schema reserves meta's names.** With `core.tn` importing `meta.tn` and imports transitive, each name
  `meta.tn` declares (`record`, `array`, `map`, `set`, `template`, `choice`, every `*_type` constructor) is reserved in
  each schema importing `core.tn` (§2.2.3). That reverses an argument Revision 38 just made: it moved five network
  names out of core into `net.tn` because `mac` and `hostname` are plausible schema type names, and `record`, `template`
  and `map` are far more plausible ones. Decided in step 4, before the ladder goes ("Decisions before the ladder
  goes").
- **`field!` widens two grammars, not one.** A facet value inside an atom refinement is [TSON-DATA]'s value grammar
  ([TSON-SCHEMA] §12.1 imports `record` for that body), so postfix `!` enters the data grammar there; and a pin
  referring to a sibling (`= S!`) is not a bare token, which §5.2 requires of a field-modifier value.

### Decisions before the ladder goes

Three questions this note used to leave open have correctness answers of their own, and each has to be settled on
paper in Revision 39, so that Revision 40 is deletion.

**1. Who may extend the kernel (decided in step 1).** With placement gone, a schema named by a request body can
declare constructors, every `!!schema` target is potentially a meta extension, and a reviewer loses the header-level
fact that a document *cannot* redefine what `record` means. It can't stay open, because step 1's depth bound for
untrusted schemas depends on it. The proposal: **a header-level opt-in.** A document that composes `top` says so once,
in its header; explicitness at document grain survives where the ladder doesn't, and a deployment policy has
something to refuse. Two ways to spell it:
- a directive of its own beside the kernel-revision directive ("How a schema identifies itself", below);
- a direct `!!import` of the kernel, if decision 2 makes imports non-transitive for visibility: then `top` is in a
  schema's namespace only when its header imports the kernel, and the opt-in is that import.

**2. Which names a schema may use (decided in step 4, before step 5 removes the ladder).** The cost is above under
"Every schema reserves meta's names". **Partitioning the namespace by role doesn't answer it under one level.**
Revision 38 lets a schema's `record` and the kernel's coexist because no position consults both (§3.3.5): a `!` head
wants a constructor and a type-ref wants a type. One level removes exactly that. A `!` head takes either role
(`!decimal_type { … }` makes a type, `!operation { … }` a value of an ordinary type), and so does a supertype position
(`atom & { … }` builds a constructor, `point & { … }` a record). In a prototype system (`SLOTS.md`), constructing a
type from a constructor and constructing a value entry from an ordinary record type are the same slot-closing operation
at two points in the order, so the grammar position no longer partitions the store. Partitioning by role is the two
stores again, which "The reference atom" calls the ladder. The options are about imports instead:
- **non-transitive imports for reservation:** `core.tn` imports `meta.tn` to declare its instances, and a schema
  importing `core.tn` sees core's names and not meta's. This pairs with decision 1's second spelling;
- **local shadowing:** a schema's own declaration shadows an imported name it doesn't otherwise use, with a diagnostic,
  which weakens §2.2.3's reservation.

**3. `namespace` stays a constructor.** Settled by Revision 38 (§4.1) and kept ("Namespaces stay a constructor", above): no
`of!` record, no reading-side §5.6 rule, no parent slots in its place.

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

| case | outcome on Revision 38 |
|---|---|
| a governed schema's resolved entries (`order`, `s => !steps { … }`) read as data under the layer that declares `steps` | reads |
| the same entries plus `steps`'s own resolved body, read as data under the kernel alone | `steps`'s body reads as an ordinary `!record`; `s` is refused: "'steps' is not admissible at a 'top' position" |

**Under one level the source's meta is gone, and its own constructors are in the fixture.** `!steps { … }` sits beside
`steps => !record { … }`, and the kernel alone has no reader for it. Reading the fixture with the read-defer-resolve
algorithm is what closes the gap:

- `!!schema` names the kernel, always.
- Each entry is read to its AST. `s => !steps { … }` waits on `steps`; `steps`'s own entry is a `!record` the kernel
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

### Entries are types or values

In Revision 38 an entry is a type exactly when its value IS-A `type` (§4.1). The only values a governed schema's
entries hold are namespace maps, since a namespace type is the one constructor whose instances are not types and only a
meta-schema may declare one; a kind slot refuses such an entry as "a value and not a type". Under one level an entry's
value is either built by a constructor or is an instance of an ordinary type:

| entry | its value | IS-A | a type? |
|---|---|---|---|
| `order => { sku: text }` | an instance of the constructor `record` | `record`, `type`, `top` | yes |
| `create_order => !operation { … }`, `operation` an ordinary record | an instance of `operation` | `operation` | no |
| `uuid_type => atom & { … }`, a kernel extension | a constructor | `atom`, `type`, `top` | builds types |

- **An entry is a type exactly when its value is built by a constructor in the `type` family.** `type_kind`'s members
  are computed that way already, so a value entry is excluded without being marked, as a namespace value is today.
- **The `top` family is entered only to build types and namespaces.** Extending the kernel (a new kind of atom, a new
  constructor) still composes `top`, `atom`, `product`, `sum` or `namespace`. `operation` doesn't: it is an ordinary
  record type, and its instances are value entries.
- **What placement guarded moves to the kernel import.** Today only a kernel-governed document may declare a
  constructor. Under one level, any schema importing the kernel can extend it, and the guard is a header-level opt-in
  (decision 1 under "Decisions before the ladder goes").
 **The cost is the split between the two binders.** Today `metaNameBinder` binds a meta layer's vocabulary, the records
its namespace types hold, and the data binder (`bindings`) binds document types, and `CLAUDE.md` calls the separation
deliberate ("a meta layer's vocabulary is a separate namespace on purpose"). Once a value entry may be an instance of an
ordinary type, the type no longer says which binder it belongs to. The natural line is between types whose instances are
entries and types whose instances are in documents. A type used both ways has to bind once for both, or the line has to
be drawn
by the use instead of by the type.

### What `kind<T>` means

Today `kind<T>` is "the name of a type built with the constructor `T`", and `ref<T>` "the name of a value whose type
IS-A `T`", refused for a constructor (`ref<record>` is `kind<record>`). With value entries in the schema map, both read
as one rule:

> `kind<T>` is the name of an entry whose value IS-A `T`, for any `T`.

- **For a constructor `T`, nothing changes.** A type entry's value is an instance of its constructor, so `kind<record>`
  and `type_kind` (`kind<type>`) have today's members.
- **For an ordinary type it is new.** `kind<operation>` names the operation entries: today's one-segment
  `ref<operation>`.
- **`ref<T>` is the qualified form,** reaching through namespace values (`orders.place_order`). The one-segment
  cases of the two merge.
- **The slot's meaning comes from `T`, not from the spelling.** If `T` is in the `type` family the name is a type
  reference: it takes part in structural identity and is delivered as a `TypeRef`. Otherwise it names a value.
- **In a data document** the probe already shows `type_kind` judged for membership. The same rule extends to value
  entries, which is how an agent plan's `method` could be checked (see the agent section).

### The reference atom: `kind`, `ref` and `type_ref` are one

The section above was an argument from what one level would allow. This one is an argument from what the kernel
already does, measured on Revision 38: the three ways the kernel holds a name in a slot are one atom, and the only thing
keeping them apart is the ladder.

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
   template bodies and in an alias's `reference.target`", and a record template's resolved body is one (a record
   template is a `record` with `parameters`). The bundled outputs show none only because no bundled schema has one. So
   `type_ref` is two things: a bare name, which is `kind<type>`, and the open-application form, which nothing else
   expresses.
3. **`kind<C>` is `ref<C>` read at the meta level.** A schema map's entry is its value (Revision 38), so
   `order => { sku: text }` is a value of the kernel type `record`. `kind<record>`, "the name of an entry whose value is
   built with `record`", is then "the name of an entry whose value IS-A `record`", which is `ref<record>`'s predicate
   word for word, minus the kind's exclusions: not a constructor whose instances are types (a namespace constructor, whose
   instances are maps, is a member), and not a template over another constructor (a record template is a type and a
   member). The spec refuses `ref<record>` and makes `kind<record>` the spelling for one reason only: `of` resolves in
   two stores, the structure namespace for a kind and the type-name namespace for a reference. Two stores is the ladder.
   The exclusions are facts about `top`'s family, not about stores, and survive as one predicate on the referent.
4. **The ladder makes half the vocabulary dead and the index wrong.** A reference's first segment names an entry
   holding a value, and under the import-level rule (§2.2.3) a namespace type, the only thing that puts a value in an
   entry, exists only in a kernel-governed schema. So `ref<T>` in a schema governed by meta.tn, which is where data
   lives, would admit no value at all: an empty atom, which is why core.tn declares no `ref`. And
   `kind_type.members`, the derived index in resolved output, is computed over the *declaring* schema's closure, while
   the names at the slot resolve in the *holding* document's. For a kind declared at level 2 and held at level 1 the
   index lists level-2 types, and the linker rightly ignores it; it is honest only when declaring and judging closure
   coincide, a level-1 kind on level-0 data. Neither is a bug in the atoms. Both are the two stores showing through
   them.

**Is `kind_type` an atom or a sum?** An atom. A sum's values are values of a variant; a kind's values are names. The
kind's own example decides it: `name_type: kind<identifier_type>` admits `identifier` and refuses `NewYork`. `choice` is
the sum, and its `variants: [type_kind]` is built over the kind; `enum_type` is an atom with a `members` field on the
same terms. What `members` shows is only fact 4, that the set is closure-relative.

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
- The slot's meaning still comes from `of`, not the spelling: an `of` in the `type` family makes the slot a type
  reference (structural identity, delivered as a `TypeRef`); any other `of` names a value. **`namespace` is where that
  rule is not yet clean.** Revision 38 lists `myapi`, a value, under `kind<api>` and `kind<namespace>` beside the
  namespace types, and under no kind of types (§4.2), which supports the merge: a kind reaches values today. But under
  one level a namespace type and its values share a closure, which the ladder prevents (a meta layer cannot apply its
  own constructor), so `!reference { of: namespace }` admits both; and `namespace` sits under `top`, not `type`, so the
  rule above reads it as naming values and drops the namespace types the kind and namespace rules put in `type_kind`.
  The atom has to say which before it is written.
- **`kind` and `ref` cannot differ by spelling alone.** Two templates expanding to the same `!reference { of: T }` make
  `kind<X>` and `ref<X>` one application with one canonical content (§8.2), so a slot cannot know which was written. A
  one-segment restriction is then a field of `reference` (a segment bound, say), or it goes and the two spellings are
  one name.

**Why this is a second proof.** The verdict's first argument was that one checked rule replaces five that leaked. This
one is from the other end: the kernel's own vocabulary, designed under the ladder, converges on one reference atom at
exactly the point where the two stores become one, and the cost of not collapsing shows up inside the atoms as a dead
spelling and a lying index. A design that is right tends to show the same simplification from both directions.

**Not under the ladder.** Writing `reference` while `of` resolves in two stores would put the store choice in a field
name (`{ name | of | kind }`) and carry the ladder inside the atom. Under the ladder the honest kernel is Revision 38's,
with `ref` confined to kernel-governed schemas.

### Templates and dependent fields (`type!`): one mechanism

A pinned dependent field *is* a template parameter: `{ type?: type_kind = order  value?: type! }` and
`<T> { value?: T }` applied to `order` say the same thing. A base meant to last shouldn't carry two mechanisms for one
idea, because two mechanisms drift: each gains rules the other lacks, and authors have to know which to reach for.

**The direction: one parameterised record, with the slot fixed at one of two times.**
- **Fixed per application** is a template: `box => <T> { v: T }`, closed by `box<order>`. `T` is a slot fixed at the
  application site, and stays spelled bare: `<…>` scopes it at the declaration's head, so it can't be confused with a
  sibling, and `!` stays reserved for siblings. The argument is part of the application's identity (§8.2) and is never
  written in a data value.
- **Fixed per value** is a dependent field: `record_field => { type: type_kind  value?: type! }`. The argument is a
  field of each value, written (or injected by a pin), and is not part of the record type's identity.

This carries Revision 38's record template to completion. There a record template is a `record` with `parameters`; here
a record's parameters and its dependent-target fields are one mechanism, each entry saying whether it is bound by `<…>`
at the type site or by `field: val` in the value. `<T>` stays as the surface spelling for "fixed per application", and
`field!` is the spelling for "fixed per value".

**What has to be checked before committing:**
- **The wire.** A parameter fixed per application is never written; a dependent field is, unless pinned and injected. A
  pinned dependent field and a template parameter must therefore produce the same wire form, which an injected pin
  (`type?: = order`) does.
- **Binding.** An injected field has no component in a bound class today unless one is declared. A parameter fixed per
  application has none either, so the two agree if an injected kind-typed pin needs no component.
- **Identity.** Two applications with equal arguments are one entry (§8.2). Two values with equal dependent fields are
  two values. The unified model must keep both.
- **Value parameters.** `status: int32 = S` is a template's value parameter. Written as a pinned value-typed field
  (`S?: int32 = 200`, `status: int32 = S!`), `S` joins the record's field list and so the wire, which fails the first
  check. The unified model needs a slot that is not on the wire, or value parameters stay template-only.

### Grammar and ABNF updates for `field!`

Adding `field!` requires no new lexer tokens ([TSON-DATA] §7.2.5). It adds two productions to [TSON-SCHEMA] §12.1,
and reaches [TSON-DATA]'s value grammar and §5.2's modifier rule as well (below):

```abnf
; ── Dependent Sibling Reference (§5.2, §5.7) ──────────────

dependent-ref  = field-name "!"
               ; "!" MUST be adjacent to the preceding field-name.
               ; Resolves to the value of the sibling field in the
               ; enclosing record.

type-ref       = paren-type
               / bracket-type
               / map-type
               / dependent-ref
               / type-name "<" type-args ">"
               / type-name
               ; dependent-ref ("type!") is added before bare type-name;
               ; one token of lookahead ("!" after the name) disambiguates it
               ; from a generic application ("<") or a bare type-name.
```

**Outside §12.1:**
- **An atom-refinement body** (`max_items: !non_negative_integer ^ { min: min_items! }`) is [TSON-DATA]'s braced
  `record`, imported by §12.1, so a facet value admitting `dependent-ref` is a change to the data value grammar at that
  point. It's confined there: a data document never admits postfix `!`.
- **A field modifier** (`= S!`, `~ S!`) is restricted to a bare token by §5.2, so admitting `dependent-ref` there is a
  change to that rule.

**Adjacency rule addition (§12.3):**

| Operator | Type | Context | Rule |
|---|---|---|---|
| `!` | suffix | dependent sibling reference (`field!`) | MUST be adjacent to the preceding `field-name` and MUST NOT be followed by an adjacent token, the voidable suffix `?` (`type!?`) excepted |

## Pulling the thread: does the data/schema line blur too?

This project's rule is "data can name a schema; only a schema can name a type" (`CLAUDE.md`). The probe says the
strong form of that is already untrue:

| case | outcome |
|---|---|
| user schema imports core, declares `pick => { t: kind<type> }`; data writes `!pick { t: order }` | admitted |
| the same, `t: no_such` | refused: "'no_such' is not a member of this kind" |
| the same, `t: record`, a constructor | refused: not a member |
| the same schema adds `rpick => { m: ref<order> }` | refused: "has an unresolved reference 'ref'" -- core declares no `ref`, the closure holding no value entry to name |
| data governed by `meta.tn`: `!record { fields: { sku => { type: no_such } } }` | admitted: grammar and shape judged, membership not |

**A data document can hold a checked name for a type today.** Membership is judged over the governing schema's
closure. This is the "value type for naming a type in data" that `CLAUDE.md` records as weighed and set aside. It
exists through core's own `kind`, which keeps it in an ordinary schema's reach, since an ordinary schema cannot import
the kernel (§2.2.3). A reference to a value does not, under the ladder: it names an entry holding a value, and only a
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

**Membership, as Revision 38 states it.** Two behaviours the probe shows are the spec's rules (§4.2), not gaps:
- **Membership depends on the governing schema.** At a kind slot under a user schema, membership is judged. Under a
  meta-schema such as `meta.tn`, a data document describes a schema whose namespace it does not carry, so the slot
  checks the grammar and membership is judged at ingest.
- **`members` is the governing closure's.** It is computed at link over the governing schema's closure; the list a
  declaring schema's resolved output carries is a derived index, discarded and recomputed on ingest (fact 4 under "The
  reference atom" is why it cannot be trusted as written).

One finding is the library's: tson-java's not-a-member message lists every member, including resolver-minted names like
`array_text_1_3600e7d5` that no author wrote.

## API descriptions and HTTP routes over schema records

**Today:**
- `orders-api-1.tn` is governed by `meta-service.tn` and imports `order-1.tn`.
- A method's `request`, `response` and `errors` are kind slots naming records in that ordinary schema, and a binding's
  `method` is a `ref<method>`; the resolver judges them all.
- What the resolver can't judge is checked in Java when the description is read (`TsonApiDescription`): placement
  against the request record's fields (`RequestPlacement`), the `implements` claim, and a verb or status against the
  method.

**Under one level:**

```
!!id:"https://schemas.example.com/2026/38/app/orders-api-1.tn"
!!import:"https://tson.io/2026/38/io/ltr8/http/http.tn"       -- operation, response, http_verb, path_template
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
  entry, and an operation body is a value body that lifts and mints nothing, as a namespace value is today (§4.1).
- `list_orders` waits on `paged_get`, which waits on `operation` from the import. Nothing is asked of the order the
  entries are written in.

**What stays dependent, and what `field!` can't reach.** Some checks relate two fields *of one value*:
- a `{segment}` in the path template must be a field of the request record;
- a placement's `query: [status]` must name a field of the record at `request`.

`request!` denotes *a value of the type named at `request`*, which is not what these need: they need the type's field
names. Reaching them would take a chained projection through the sibling's target, roughly `ref<request.fields!>`,
and it doesn't fit `meta-service.tn` cleanly:
- **It changes what `!` means.** `type!` gives a sibling's value. `request.fields!` walks into the definition of the
  type that value names, and through `method`, into another entry first. That is an entry-level value → value edge,
  and the dependency model's invariant has values as leaves.
- **One expression can't serve both endpoint kinds.** `query` sits in `placement`, under `endpoint`, shared by
  `operation` (where `request` is a sibling) and `binding` (where it is reached through `method`).
- **It would move membership only.** Each field placed exactly once over the path, query, headers and body, path
  template variables binding fields, the scalar-only rule and the GET remainder are relational, and `RequestPlacement`
  keeps them whatever the type system gains.

So these checks stay in Java, the tier `CLAUDE.md` gives them: "an unsound description has no model".

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

Today the surface "reads schemaless, method names as preserved unknown type refs", the agent experiment's decision
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
  judged on read. Kinds in data work today (measured); a ref has nothing to name there, `ref` being declared only in
  kernel-governed schemas (measured).
- **The namespace they refer into is the interface's, not the plan's own schema.** Today a kind or a ref resolves in
  the *governing* schema's closure, and a plan names its interface in a field, `interface: uri`. A field whose value
  sets the scope for later fields is the dependent pair again (`interface!`): a sibling naming the scope, not a type.
- The answer TSON already has is to make the scope visible instead of computed. Either:
    - the plan is governed by a derived schema that imports both `agent.tn` and the interface, the pattern
      `orders-wire-1.tn` uses for RPC packets; or
    - `steps` carries a §7.8 scope push naming the interface.

  Both fit decision 1 of the agent experiment's README (one interface per plan). Both retire `interface: uri` as a
  field.

## Order of work

Two revisions. Each step is checked against the `-resolved.tn` fixtures before the next starts, so a step that moves
a fixture is caught at that step, and no revision lands two decisions that could move the same fixture.

**Revision 39: the model, the separable half of `type!`, and the decisions on paper.**

1. **Specify the dependency model:** shape and value, the edges, the invariant that a shape never waits on a value,
   intra-record `sibling!` edges, what a cycle is, and the limits (dependency depth for untrusted schemas, a
   template-expansion bound). The kernel-extension guard is decided here, since the depth bound depends on it
   ("Decisions before the ladder goes", 1). The streaming rule for a free `sibling!` field goes into [TSON-DATA] and
   [TSON-JSON], as an exception to field order being insignificant.
2. **Prove it on the kernel.** `meta-kernel-resolved.tn` comes out as a fixed point under read-defer-resolve over the
   pre-loaded kernel shapes. Until it does, nothing below starts.
3. **Add `sibling!` under the ladder.**
    - `record_field.value` becomes `value?: type!`, so defaults and pins are read by their field's type.
    - Sibling-indexed facets (`max_items: !non_negative_integer ^ { min: min_items! }`) retire the three prose
      coherence rules they can state; bounds reached through a field group stay prose.
    - The grammar changes in "Grammar and ABNF updates for `field!`".
4. **Settle the other two decisions on paper:** which names a schema may use, and `namespace` kept as a constructor.

**Revision 40: the ladder goes, and the reference atom with one store in hand.**

5. **Remove the ladder:** `!!meta` becomes the kernel-revision directive (or `!!import` classifies), and placement,
   direction of service, and the import-level rule go. `core.tn` imports `meta.tn` instead of redeclaring siblings,
   under the answer to decision 2. Value entries become instances of ordinary types, carrying Revision 38's namespace
   values to every ordinary type, and the binder split is redrawn.
6. **Retire `value` from `meta.tn`'s facets:** atom constructors and their instances sit together or mutually resolve
   (`decimal_type` uses `number`, `ipv4_type` uses `cidr4`). `value_type` is left at `type_argument.value`.
7. **Replace `kind_type`, `ref_type` and the name-only `type_ref` with one reference atom,** `kind<T>` and `ref<T>` its
   spellings and `members` gone ("The reference atom"). It has the most measured evidence of any step and has to come
   last, because writing it while `of` resolves in two stores puts the store in a field name.
8. **Unify templates with dependent fields,** under the four checks in "Templates and dependent fields". Held bodies
   stay text.

Steps 1 and 2 are the correctness work. Steps 3 and 4 change little and are testable on their own; steps 5 to 8 are
mostly deletion once the decisions hold.

## What this would change in the project's rules

Not edited; recorded here until the direction is decided.
- **`CLAUDE.md`, "Data can name a schema; only a schema can name a type":** measured to be untrue in its strong form.
  The restatement above ("exports a namespace" versus "refers into one") is the candidate, and it holds under the
  ladder already (kinds in data), so it can be adopted independently of either revision.
- **The import-level rule** (§2.2.3) would be withdrawn under one level: it is a ladder rule. A data document's kinds
  reach it through core today and through the one namespace then.
- **The reference rule** (core.tn declares no `ref`; a reference is declared only in a kernel-governed schema) is
  withdrawn with it: under one level a schema that governs data has value entries, so `ref<T>` is valid everywhere and
  core.tn needs no sibling of anything.
- **The "Describing a service" section's** argument that "the meta layer owns the vocabulary its constructors use" is a
  consequence of levels. Under one level, `http.tn` returns as an ordinary library.
- **"Describing a service"'s** wiring note that `metaNameBinder`, not `bindings`, binds the description's vocabulary, and
  that "a meta layer's vocabulary is a separate namespace on purpose", goes with the ladder.
- **The "Deployment descriptors" section's** argument that a descriptor is data because "it references no types"
  moves to the reasons above: self-certification, immutability, and that nothing may name one.

## Open

- **The shape/value boundary**, entry by entry: the table under "The dependency model" is the proposal, and every
  kernel constructor's facets need assigning to one side or the other before step 1 is done.
- **Unifying templates and `type!` dependent fields:** the four checks under "Templates and dependent fields".
- **Sibling choice in a facet** (`exclusive_min: ( min! | exclusive_min! )`): whether to add it, so bounds reached
  through a field group can retire their prose too, or leave those checks in prose.
- **Typed template bodies:** whether to widen every constructor slot to admit a parameter reference so a held body is
  typed, and whether a declaration-time guarantee over parameter ranges is worth the interval reasoning it needs
  ("Typed template bodies", above).
- **Chained projections (`method.request.fields!`):** whether an entry-level value → value edge is ever worth adding.
  Not for `RequestPlacement`, which keeps its relational checks either way.
- **Self-identification.** Keep `!!meta` as the kernel revision (renamed or not), or classify by `!!import`; whichever
  it is, it has to leave room for decision 1's header opt-in.
- **The bootstrap.** Does the kernel keep a directive naming itself, or is pre-loading stated as the one closed cycle?
- **Re-resolving a resolved form to itself** as a second fixture check, once its readers come from the fixture.
- **The cycle message**, and whether a circular dependency through an import is the same error as one within a
  document (today a cycle through `!!import` is refused by [TSON-DATA] §2.2.3).
- **§9.1:** a bound on read-dependency depth, and where it sits beside nesting depth.
- **The binder split** once value entries are instances of ordinary types (step 5): by the type, by the use, or one
  binder.
- **The kind's exclusions under one reference atom:** "not a constructor whose instances are types, not a template over
  another constructor" as one predicate on the referent (has instances). An abstract type stays a member, as §4.2's
  membership by construction already makes it.
- **`namespace` under one reference atom:** whether `!reference { of: namespace }` names the namespace types, their
  values, or both, once one closure holds both ("The reference atom").
