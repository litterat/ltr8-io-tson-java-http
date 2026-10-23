# Review: tson-java PR #725 (a schema map's entry is its value)

Drafted here and not posted. One round per head, newest first. Each round has the consumer trial (tson-java-http
against the PR), what changed since the round before, and the findings it leaves.

## Round 2: `17d0ee77`

Reviewed against `c3defd77`, round 1's head. This round adds `type_chain` and `chained` (#14, rewritten) and makes
the base kinds abstract (#15).

**Verdict.** Ready from the consumer side. Every round-1 finding is fixed and reproduces as fixed. The chain record
landed as a facet, which is the shape namespaces need. One gap is left, and it falls on this project's own case: a
templated `data` entry's application is still not written (item 1 below).

**Consumer result.** Same mechanical port as round 1. The one change is that an application is read with
`TsonSchema.applicationOf(name)` rather than `source()`. After the port, the full build and suite pass.

### Round 1's findings, rerun on this head

| Round 1 | Now |
|---|---|
| 1. a template composing or refining a FINAL record | refused at `/t` for `&`, for `^`, and with only a use site |
| 2. a templated constructor outside a kernel-governed schema | refused at `/op`, with the §2.2.2 message |
| 3. a constructor field taking a chain field's name | `chain` refused where declared, naming `chained`; `subtypes` is free |
| 4. application is identity, not provenance | written on every chained instantiation; not for `data` or a hop (item 1) |
| 5. the chain incomplete after the applied-parent fold | `great` reaches `rt`, and `!h { r: !great {…} }` reads |
| `tightenedBy` through an alias | reads the recorded `refines` fact; no inference from field sets |
| `TypeKind.ofChain` keyed on spelling | judged by origin: a schema's own `data`/`top` record is a `PRODUCT`, and composes |

### 1. A templated `data` entry's application is never written

`application` lives in `type_chain`, and `data` doesn't compose `chained`. So for

```
fetch    => <T> !operation { … responses: [ { status: 200  body: T … } ] }
getOrder => fetch<order>
```

`getOrder`'s value is an `Operation` with no chain. Its application, `fetch<order>`, is only in
`EntryFacts.application` (measured: `Chained.chainOf(getOrder)` is empty, and `factsOf("getOrder").application()` is
`fetch<order>`). #14's status says this for a non-record template's `!reference` hop, but not for a `data` entry. A
templated operation is exactly the case §4.1 names `data` for, and the one this project pins. Two consequences:

- **The docs overclaim.** The kernel's `type_chain` doc and `TypeChain`'s Javadoc say the application is "written
  wherever a declaration or a materialised entry closes one, so an ingested schema's applications unify with a later
  schema's". That holds for chained values only.
- **There are two carriers.** `TsonSchema.applicationOf(name)` merges them, and is the accessor a consumer should
  use. `Chained.applicationOf(value)` looks just as public and silently answers empty for `getOrder`. I hit that
  porting this repo. One sentence in `Chained.applicationOf`'s Javadoc pointing to `TsonSchema.applicationOf` would
  prevent it.

Giving `data` the chain isn't the fix. Every meta-layer `data` constructor would flatten a `chain` field, so every
host class (`Operation` here) would need a component for it under strict binding. The underlying point is that an
application isn't an IS-A fact. It is identity, and every entry that closes one has it: types, `data` entries and
hops. So either:

- **move it out of `type_chain`** to a carrier that doesn't depend on the value's shape. A key annotation in resolved
  output (a kernel annotation type, written on the schema-map key the way `@doc` is) reaches all three kinds, and
  `AnnotatedMap` already keeps key annotations. `type_chain` would then be purely IS-A, and `EntryFacts.application`
  would go;
- **or keep it where it is**, and state the limit in both docs: written for chained values; in memory only for
  `data` entries and hops.

The first is better for namespaces too. A member's identity is (schema, entry, key path), which is also not an IS-A
fact, and it will want the same entry-level home.

### Smaller

- **`#15`'s refusal for `!data {}` reads `Name the member this value is, one of (), or omit the tag`.** #15's status
  says "a `data` with no subtypes in the closure says so". It doesn't yet: the list is printed empty. Say "no
  constructor in scope composes `data`" instead.
- **`!top {}` lists `type | atom | product | sum | data` among the members to name.** Each of those would itself be
  refused as abstract. List only the instantiable members.
- **The same message has a doubled clause:** "the body is not valid data for 'top', the constructor's own constraint
  vocabulary -- a tag naming 'top' selects nothing -- it is abstract …". The first clause is the generic
  applicability message, and here it contradicts the second. For an abstract target, the abstract sentence alone is
  enough.
- **`EntryFacts` grew a fifth fact, `refines`.** That's fine as an in-memory fact for one reader, and it is
  documented. The round-1 note stands: facts are keyed by root entry name, and namespace members will need a path.

### From the namespace side

The suggestion landed as asked. `chained` is a facet, `type` and `template` compose it, and every IS-A question goes
through `Chained`. A future `namespace => top & chained & { … }` gets composition-through-parents with no special
case. The round-1 regressions were the symptom of not having it, and neither can recur by that route. What remains
for namespaces is item 1's question, where an entry's identity lives when its value can't carry it.

## Round 1: `c3defd77`

Reviewed on `c3defd77` against base `6d67e2e2`, from the consumer side (tson-java-http) and as code. Each finding
marked reproduced was run on both commits.

**Verdict.** The direction is right, and for a consumer the API reads better: `entries.get(name) instanceof
Operation` replaces `.body()`. Two regressions should be fixed before merging. Both come from one cause: a template
now carries no chain, and two checks still ask the chain. Beyond those, I suggest one structural change, the chain as
a record of its own, because it fixes that cause and is the shape namespaces will need.

**Consumer result.** tson-java-http needs a mechanical port, and its full build and suite then pass:
- `entries.get(x).body()` becomes `entries.get(x)`;
- `TypeDefinition` becomes `Top`;
- `source()` becomes `factsOf(name).application()`;
- a value's annotations come from `factsOf(name).annotations()`;
- `supertypes()` becomes `Type.supertypesOf(value)`.

### Fix before merging

#### 1. A template composing or refining a FINAL record is no longer refused (reproduced)

`RecordExtension.checkNothingComposesOntoFinal` loops over `Type.supertypesOf(def)`, and that is empty for a
`TemplateBody`. `familyBodyOf` still routes record templates into the check, but the check now sees nothing.

```
c => final { x: int32 }
t => <T> c & { v: T }          -- also t => <T> c ^ { x: T }
```

- **Base:** `'t' names 'c' as a supertype, but 'c' is final…` at `/t`.
- **This PR, alone:** no diagnostic.
- **With `tt => t<text>` added:** reported only at `/tt`.
- **With only a use site, `h => { f: t<text> }`:** the FINAL refusal is gone entirely, and only "undeclared member"
  remains.

So a library exporting such a template loads clean, and every importer gets the error instead. The fix is for a
template to read its written parents (`WireForm.writtenParents`, as `Ancestry.ofTemplate` does).

#### 2. A templated constructor outside a kernel-governed schema is accepted (reproduced)

`TsonSchemaLinker` (line 638) decides "this entry is a constructor" by `Type.supertypesOf(def).contains(TOP)`, which a
template never satisfies. So the §2.2.2 check skips templates. Repro: a schema with `!!meta` and `!!import` both set
to `meta.tn`, declaring `op => <T> data & { path: T }`.

- **Base:** `'op' declares a type constructor … not governed directly by the meta-kernel`.
- **This PR:** no diagnostic. Only an application (`opt => op<text>`) is caught, and it is reported against `opt`.

Not reproduced, but plausibly the same defect: the same predicate is used at `TsonCompiledMetaSchema.java:123`,
`TsonSchemaCompiler.java:109` and `DefinitionResolver.java:622`. A templated meta-layer constructor now never matches
any of them.

#### 3. A constructor's own field can take a chain field's name (reproduced)

`type`'s two fields flatten into every constructor that composes a type kind. So a meta layer can declare:

```
widget => atom & { subtypes?: [text] }
```

This resolves with no diagnostic: `widget`'s `subtypes` field silently becomes `[text]`. Then every instance that
uses the field is refused:

```
w => !widget { subtypes: [a b] }
-- '!widget' states 'subtypes', which a declaration never writes -- a type's supertypes and subtypes are its IS-A chain …
```

The constructor should be refused where it is declared: a constructor composing a type kind may not declare
`supertypes` or `subtypes`. Today both names are reserved and nothing says so.

### Worth settling in this PR

#### 4. The application is identity, not provenance

#14 says no rule reads a template application after resolution, so dropping `source` loses only provenance. The PR's
own decisions section says the application is what §8.2's identity and §5.8's applied-parent edges are keyed on,
imports included, and `EntryFacts.application` exists for exactly that. Inside one process this works. Across the
interchange format it doesn't:
- resolved output no longer writes the application;
- so a processor that ingests an imported schema as resolved output can't resolve `box<int32>` to the importer's
  declared `int_box`, and mints a second entry for one application.

It is also visible to consumers. Neither another implementation nor a code generator can tell from the output that
`getOrder` is `fetch<order>`. tson-java-http documented and pinned that fact, and now reads it from in-memory facts.

Two ways to settle it:
- write the application in resolved output (see the suggestion below for one place to put it on a type);
- or keep #14's claim, and rewrite §8.2 so cross-schema unification no longer needs the application.

As it stands, #14's text and the PR disagree.

#### 5. "`supertypes` is the complete chain" isn't true after the applied-parent fold (reproduced; not new)

`SchemaResolver.withAppliedParents` folds only records whose own chain still holds an application. A record that
composes an entry whose chain grows during the fold never inherits the new edge:

```
result => <T> { v: T }
rt     => result<text>
ok     => <T> result<T> & { x: int32 }
okt    => ok<text>                       -- chain [rt, ok]
great  => okt & { y: int32 }             -- chain [okt]: no rt
h      => { r: rt }
```

`!h { r: !great {…} }` is a `TYPE_MISMATCH` on both commits, and the same gap reaches a parameter bound `<T: rt>`.
It isn't a regression. But the kernel's `type` doc and `Type`'s Javadoc now state completeness as the contract, so
either fold to a fixed point or soften the sentence.

#### Smaller

- **`ValueReaderContext.tightenedBy`** (line 153; plausible, low impact). For `slug => al ^ {…}` with
  `al => identifier_type`, the chain `[al, identifier_type, …]` has two "nearest" members, because `al` is a
  reference. So the method returns null and binding loses the `al` candidate the old `source` walk found.
  Conversely, a single-parent `&` adding no fields now counts as a tightening.
- **`tson-schema/CLAUDE.md`'s stale `TypeDefinition.kind` line** is a one-line fix and better made in this PR than
  left behind.
- **`TypeKind.ofChain` keys on the literal names `atom`, `product`, `sum`, `data`.** This is unchanged behaviour (the
  resolver did the same before), but it is now a public derivation. An ordinary schema's `data => { x: text }` makes
  `payload => data & { y: text }` report kind `DATA`. Matching by declaring schema (`originOf`) rather than by spelling
  would close it.

### Suggestion: the chain as one record, and as a facet rather than a part of `type`

**In Java.** Every type body record carries `List<TypeRef> supertypes, List<String> subtypes` first. Measured on this
branch, that costs:
- 36 records, each with two components, a `Chains.copy` normalisation, a `withChain` implementation and a chain-free
  convenience constructor;
- 33 explicit `List.of(), List.of()` arguments in main code;
- seven chain helpers on `Type`.

Also, the one slot has three states, told apart by `parent.arguments().isEmpty()` filters in `Ancestry` and
`SchemaResolver`:
- parents as written, in held text;
- mid-resolution, closed names mixed with applications awaiting the fold;
- closed, the complete chain.

A record would fix all of this:

```java
public record TypeChain(List<TypeRef> parents, List<String> supertypes, List<String> subtypes) {
    public static final TypeChain EMPTY = new TypeChain(List.of(), List.of(), List.of());
}
```

- **One component instead of two.** `withChain(TypeChain)`, with `withoutChain` becoming `withChain(EMPTY)`.
- **Explicit states.** `parents` holds what was written (applications included, and it is the only list that ever
  needs `TypeRef`). `supertypes` goes back to bare names, which is all a closed chain ever holds.
- **Later additions touch one record, not 36.** The application from item 4 is one candidate.

**In the kernel.** ltr8-bind has no way to spread one component's fields into its parent (`@Transparent` wraps a
single component; it doesn't flatten). So a Java `TypeChain` over today's flat kernel needs a new binder feature.
Nesting it in the kernel instead is the smaller change, and better on its own terms:

```
type_chain => {
  parents?:    [type_ref]      -- held text: the parents as written
  supertypes?: [type_name]     -- closed: every type this one IS-A
  subtypes?:   [type_name]     -- the inverse, over this map only
}
type => top & { chain?: type_chain }
```

- **One reserved name** in every type constructor instead of two (item 3), and one name for the "a declaration
  never writes it" refusal.
- **"What a body is" versus "where it sits" becomes one field.** The PR keeps needing that comparison (a core type
  and its kernel original are one body in two maps), and here it means ignoring `chain`. `subtypes` in particular is
  a fact about the map, not the value. The record makes that cheap to strip, and leaves room to ask later whether it
  belongs on the value at all.
- **Resolved output** gains one level of nesting per type body. That's the cost.

**From the namespace side, make it a facet.** The PR puts the chain on `type`: "a value, a hop and an open body
carry no IS-A". Everything composing `top` directly therefore has none:
- **Templates are already paying for it.** Items 1 and 2 above, `Type.subtypesOf(name, value, entries)` scanning the
  whole map to find a family base's members, and `Ancestry` reading parents out of held text.
- **A namespace is next.** It composes `top` (it isn't a type). Composing one, `orders => reads & { … }`, merges
  member sets, and an inherited member is reached through the parent rather than copied. That is a chain, on a
  non-type.

So declare the chain once and compose it where it's wanted:

```
chained   => { chain?: type_chain }
type      => top & chained & {}
namespace => top & chained & { … }     -- later; a template's held text holds `parents` only
```

In Java this is a `Chained` interface (`TypeChain chain()`, `withChain`), extended by `Type` now and by a namespace
kind later. `Type.supertypesOf(Top)` then becomes `value instanceof Chained`, and items 1 and 2 can't recur. One more
reason for a single component: `Data` is the one non-sealed kind, so its host classes are user classes. A namespace
kind's host classes will probably be user classes too, and a user writes one `chain` component more easily than two
correctly ordered lists.

**`EntryFacts` meets the same question one level down.** It is keyed by root entry name. A namespace's members are
nested entries with positions and value annotations of their own, so the facts map will need a member path for a key
when namespaces land. That's not for this PR, but the key type is cheap to widen now and harder later.
