# Slots: the one-level design through Self and Newspeak

**Status: a lens on [`README.md`](README.md)'s design, not a proposal of its own.** TSON has held back from being a
programming language, and this note keeps it held back. Looking at the one-level design the way a language designer
would shows two things: which ideas from languages are already in it under other names, and which would cross the line
that keeps a schema system decidable. A few proposals fall out, and they are marked as such.

**Terms.** In this note a *slot* is Self's: a named member of an object. What TSON calls a "kind-typed slot", a typed
position in a record, is called a *position* here.

## The two languages, briefly

**Self** has no classes and no separate namespace construct. Everything is an object made of slots.
- **A slot is a constant** (`x = 3`), **assignable data** (`x <- 3`, which also creates an `x:` setter), or **a parent**
  (`p* = obj`).
- **Lookup follows parents.** A name an object doesn't hold is looked up in its parents, transitively. Two parents
  supplying *different* slots for one name is an ambiguity error; the same slot reached by two routes is not.
- **New objects are clones of prototypes,** with some slots changed. Shared behaviour sits in a *traits* object that
  instances reach through a parent slot.
- **The global namespace is an object,** the *lobby*, whose parents reach the globals.
- **Reflection goes through mirrors.** A program doesn't ask an object about its own slots; it asks `reflect: obj`.
  The object level never carries facts about itself.
- **Slots carry annotations,** which the environment uses to group them into categories and modules.

**Newspeak** takes Self's object model and removes everything global.
- **There is no global namespace.** Every name is either lexically enclosing or a message to some object.
- **A module is a class, instantiated with what it needs.** A top-level class takes its dependencies as constructor
  arguments, typically a *platform* object, and binds them to its own slots. It has no imports; whoever instantiates it
  decides what it can reach.
- **That makes it object-capability secure.** A module can reach only what it was handed, with no ambient authority.
- **Classes nest, and nested classes are virtual.** A subclass of a module can override one of its nested classes, so a
  whole family of classes is extended together.
- **Classes are mixins,** applied to a superclass that is itself an expression.

## The overlay

| one-level TSON | Self | Newspeak |
|---|---|---|
| a schema | an object | a module instance |
| an entry, `name => value` | a constant slot | a slot |
| `!!import` | a parent slot of the schema object | a constructor argument |
| imports are transitive | lookup follows parents transitively | (none: no ambient names) |
| the import collision rule: judged by the declaring schema | the ambiguity rule: same slot by two routes is fine | — |
| a refinement, `api => ns ^ { of?: = method_map }` | clone the prototype, set a constant slot | subclass, override a slot |
| a composition, `a & b` | copy-down of slots from both | mixin application |
| a namespace value, walked by `ref<T>` | an object answering its slot names | an object answering messages |
| `ref<T>`'s qualified path, `orders.methods.place_order` | a chain of sends | a chain of sends |
| key annotations (`@doc` before a key) | slot annotations | — |
| a resolved form, read as data under the kernel | a mirror | a mirror |
| §8.2: no document may select its own policy | — | object capabilities: authority comes from the caller |
| an agent plan's steps | a method activation's local slots | a method's locals |

Three things stand out.
- **TSON is already Self-shaped, not class-shaped.** A refinement is a clone with slots changed, which is how Self makes
  objects and how TSON already makes `api` from `ns`. Nothing in TSON behaves like a class hierarchy that instances are
  stamped from.
- **Every TSON slot is constant.** §3.5 makes a published schema immutable, and the dependency model has no assignment
  anywhere. That is the first place TSON parts from both languages, and it should.
- **The data/schema split is the mirror split.** A schema is the base level. Its resolved form is the reflective
  description, a data document under the kernel. A data document can't declare, so it can't reflect on itself; it can
  only hold names (`kind<T>`, `ref<T>`), the way a program holds a mirror without becoming one.

## What the overlay proposes

### Proposal: parent slots on namespaces

**The one-level note has a wrinkle and a workaround that are the same thing.**
- The wrinkle: a namespace kind written as a record template is walked as `orders.entries.get`, and the note proposes a
  reading-side counterpart of §5.6 so `orders.get` walks too ("Dependent fields and the namespace kind").
- The workaround: an interface's `extends` names other interfaces whose methods it also has, and the meta-service
  experiment walks it in Java (`Routes`), refusing a cycle.

Both are delegation. In Self's terms, `entries` is a parent slot of the namespace record, and `extends` is a list of
parents. Saying so directly replaces two special cases with one rule:

```
ns        => abstract { of: kind<namespace>  entries: of }       -- entries is a parent: lookup falls through to it
method_ns => ns ^ { of?: = method_map }
interface => method_ns & { extends?: [ref<interface>] }         -- each extended interface is a parent too
```

The source spelling of "parent" is open. Self writes `p*`; TSON has no such token, and adding lexer syntax is a cost of
its own. In resolved form it is a facet, `!record_field { type: of  parent: true }`.

**The lookup rule, taken whole from Self:**
- A name not held by a namespace value is looked up in its parents, transitively.
- Two parents supplying *different* values for one name is an error, naming both routes.
- The same value reached by two routes is not. This is TSON's import collision rule already (a collision is judged by
  the declaring schema's identity, and the probe's `void` case shows two different declarations refused).

**What it adds to the dependency model.** Lookup edges are a third kind, beside shape → shape and value → shape. A
parent edge orders nothing while shapes resolve; it is followed when a reference is judged, in the values step. Its
one rule is that **the parent graph is acyclic**, refused naming the cycle, since a cycle would make lookup loop. That
is the check `Routes` makes for `extends` today, moved into the resolver.

**What it retires:**
- the reading-side §5.6 walk rule, which was the same idea stated for one field;
- the Java walk of `extends` and its cycle check;
- the open question of how a namespace kind is entered by name.

**And it gives import transitivity a reading.** A schema's imports are its parents, so "imports are transitive" is the
lookup rule applied at the top level. The one-level design has one namespace per schema; with parents, that namespace
is the schema's own entries with its imports as parents, and the ambiguity rule is the collision rule.

### Proposal: name refinement as cloning

Not a change, a reading to adopt in the spec's prose. `c ^ { … }` is "clone `c`, with these slots changed", and a pin
(`= v`) is setting a constant slot in the clone. It explains two existing rules better than the class reading does:
- why a refinement can't move a field from required to omissible: a clone keeps its prototype's slots and only changes
  their values;
- why `field_map` and `api` are both refinements of `ns`: they are two clones of one prototype.

### Reading: the resolved form is the schema's mirror

This holds already and is worth stating as a principle: **a schema is reflected on only through its resolved form**,
read as data under the kernel. No document inspects itself at the base level. `TsonSchema.factsOf` and `applicationOf`
are mirror accessors on the Java side. The one-level note's strengthened fixture check (readers built from the fixture's
own entries, trusting only the kernel) is a mirror that can be checked without trusting the object it reflects.

### Reading: §8.2 is object capability

§8.2's rule that no document may name, import or otherwise select the policy it is judged under is Newspeak's
principle: authority comes from whoever instantiates, never from the module. A deployment hands a processor its policy,
as a Newspeak caller hands a module its platform.

**Where Newspeak would go further, and TSON shouldn't yet.** A Newspeak module names no dependencies; it is
instantiated with them. The TSON equivalent is a schema parameterised over what it imports: `http.tn` over the
`problem` type it uses, closed by whoever imports it. That fits TSON better than it first looks, since an applied
schema would have structural identity exactly as an applied template does (§8.2). It also gives up something TSON
values: today an `!!import` names a fixed identity, hash-pinnable, so a schema means one thing everywhere. Held, as the
point past which TSON's modules become a programming language's.

## Where it stops: the line between a schema system and a language

The dependency model's invariant, **a shape never waits on a value**, is the line. Each language feature either keeps
it or breaks it:

| feature | in Self / Newspeak | in TSON | why |
|---|---|---|---|
| constant slots | yes | yes: entries | nothing computes |
| parents, delegation | yes | proposed, static | lookup edges are acyclic and order no shapes |
| clone and modify | yes | yes: refinement | a clone's shape is its prototype's, substituted |
| mixins | yes (Newspeak) | yes: composition, flattened | static copy-down |
| mirrors | yes | yes: resolved forms | reflection never feeds back into a shape |
| assignable slots | yes | **no** | a published schema is immutable (§3.5) |
| methods, computed slots | yes | **no** | a shape computed by code waits on values, and decidability goes |
| late binding of names | yes | **no** | names bind at link, which structural identity (§8.2) and hash pins rely on |
| virtual nested types | yes (Newspeak) | **no** | overriding a type a sibling already linked to is late binding by another route |
| ambient authority | Self: yes; Newspeak: no | **no** | §8.2 |
| branching | yes | **no**, even in plans | a branch makes a later step's type depend on an earlier value |

**Template substitution is the only computation**, and it is the one place a bound has to be stated (README, "Open").
Everything in the "no" rows would make a shape depend on a value, which is exactly what the model forbids. So the lens
confirms the model's invariant is not an arbitrary restriction: it is the line itself.

## The agent: where the line is crossed on purpose

Through a language designer's eyes the agent plan is a program, and that's the one place TSON is one.
- **A plan's steps are a method activation's local slots.** In Self, an activation is an object whose slots are the
  arguments and locals, with the receiver as parent. A plan is that: step names are locals, the interface is the
  receiver, `return` is the result.
- **The VM is bytecode for it.** Write-once slots, a value stack, no jumps (`agent-vm.tn`).

What keeps it on the schema side of the line is the set of restrictions the sketches already make:
- **Write-once slots:** no assignment.
- **No jumps and no branching:** "plans cannot branch on values", so each step's type depends on earlier declarations,
  never on earlier values. That is the shape/value invariant at the level of a program.
- **One forward pass verifies it,** because straight-line code has no merge points.
- **It is data:** a local scope that exports nothing.

So a plan is a program in the narrowest sense that can still be typed by the schema system without evaluation. Lifting
any of those restrictions moves it into the "no" rows above, and needs a different checker.

## What this would change in the one-level design

Recorded in `README.md`'s "Open" until decided:
- **Parent slots replace the reading-side §5.6 rule,** and absorb the `extends` walk.
- **The dependency model gains lookup edges,** with the parent graph required to be acyclic.
- **The spec's prose reads refinement as cloning** and states the resolved form as the only mirror.

## Open

- **The source spelling of a parent field**, given TSON has no `*` token for it.
- **Whether imports are restated as parents** in the spec, or only explained that way.
- **Parameterised schemas** (Newspeak's modules), held as the point past which TSON becomes a programming language.
