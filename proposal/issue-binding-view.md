tson-compiler: a public binding view of a resolved schema
The relationship between a schema entry and the Java side `ltr8-bind` describes is computed while readers are compiled, and
cannot be asked. A caller that needs it before any document is read — to check at startup that an interface, a route or a
dependent field binds — rebuilds it from parts, and its copy can drift from the read.

Three questions make up that relationship. Each is answered inside `tson-compiler`, in a private class or an unexported
package.

## Where each answer lives today

**1. Which entry is the binder asked for a written type name?**

- An alias collapses to its target in `TsonSchemaCompiler.Compilation.build`, so a factory, and its bind lookup, runs for the
  terminal entry only.
- An extern crosses in `Compilation.boundary` / `crossing`: the foreign schema compiles whole and asks the binder with its
  own linked schema.
- The lookup key is `ValueReaderContext.namespaceOf` (the canonical identity of the declaring schema) and the candidate names
  are `ValueReaderContext.bindingNamesFor` (an alias's own name is a candidate only when the target has no source position).

Public today: `TsonLinkedSchema.landing`, `declaringIdentity`, `composedOver`, and `ExternAdmission.of`, which composes the
key for an extern position only. No exported package has the alias-terminal walk: `compiler.resolver.AliasChain` is
unexported, and `tson-json` keeps a second one (`json.reader.AliasChain.landed`).

**2. Which class does a read use for that entry?**

Each factory answers for itself (`RecordBindReader.Factory.descriptorFor`, `TupleBindReader.Factory.descriptorFor`,
`ExternTags.bound` on the write side). The outcome is visible only as a `MissingBindingException` deferred to the first read.
`DataBindContext.getDescriptor(namespace, name)` is public, but the caller must already know the right key and candidates,
and it answers by exception.

**3. Do a schema type and a `DataClass` agree?**

`BindTargets` (package-private) judges every typed position: atom, array, map, tuple, record. The `RecordBindReader`
constructor judges a record's fields against its components. The verdict exists only as a `BindMismatchException` thrown from
compile.

- Atoms are nearly public: `AtomParsers.forRead` with `AtomType.boundTo`. They omit the rule in
  `AtomTypeReader.ENUM_OBJECT_MODE` that reads an entry named `boolean` through `BuiltinTypeVocabulary`.
- A choice is never judged against a `DataClassUnion` at compile. `ChoiceReader.FACTORY` does not consult the bind context,
  and nothing in `tson-compiler` or `tson-json` reads `DataClassUnion.isSealed()`.

## The rule for question 1 has more than one implementation

Read from the code, not measured:

- Compile path (`build`, `bindingNamesFor`): the terminal entry; the alias's own name only when the target has no source
  position.
- Root pre-check (`TsonObjectReader.boundClass`, `JsonObjectReader.boundClass`): the written name, not followed to the
  terminal or the landing.
- Natural element host of an array or map (`ArrayBindReader`, `MapBindReader`): the written name, falling back to
  `String`.
- JSON candidates (`BindRecordBuilder.bindingNames`): every alias whose chain lands on the entry, whatever its source
  position.

One public view would give these one implementation as well.

## What a consumer builds today

`ltr8-io-tson-java-http` needs all three at startup and has a copy of each:

- `Landed`: its own alias walk, the extern landing, `declaringIdentity`, and `nameBinder().resolve` with a catch for
  "unbound".
- The protocol join (`TsonServiceProtocol`): an agreement check that follows `BindTargets`' rules position by position, with
  the `boolean` rule copied by name.
- `ChosenTypes`: the types a dependent field's sender may name, assembled by hand. `BACKLOG.md` here already notes that one.

Two of its own call sites ended up answering question 1 differently for a plain alias, because there is no authority to defer
to.

## Proposed

A direction, not a design. One public type in `io.ltr8.tson.compiler`, which already sees both the schema model and
`ltr8-bind`, reached from a compiled schema, with the reader factories built on it so its answers are the read's by
construction.

```java
// from a compiled schema: TsonCompiledSchema.binding(), or on the registry
public final class SchemaBinding {
    /** The entry a bind-mode read asks the binder for, for a name written in this schema's namespace. */
    public BoundEntry entry(String writtenName);

    /** The compiler's verdict at a typed position, as a value. */
    public Agreement agreement(TypeExpr type, DataClass target);
}

public record BoundEntry(String declaringSchema, String name, Top definition) {
    public BoundEntry shape();                  // past the remaining alias hops: what the type is
    public Optional<DataClass> descriptor();    // what the context binds it to; empty where nothing does
    public Optional<AtomType> atom();           // the atom type a bind-mode read uses, boolean included
}

public sealed interface Agreement {             // agrees | disagrees(reasons) | not judged
}
```

## Rules

- A lookup is by entry identity: the canonical identity of the declaring schema and the entry's name, as tson-java's
  `class2-compilation.md` note states it.
- The alias rule is stated once and every site in the table above uses it, or the sites that differ say why.
- Answers are values. Unbound, a class that cannot be analysed, and a mismatch are told apart without catching an exception.
- `agreement` covers every position `BindTargets` judges, and says "not judged" where the compiler does not judge, which
  today includes a choice.
- A union says whether its member set is closed. A Java sealed hierarchy whose leaves are all final is built with
  `isSealed()` false (`DefaultUnionBinder.resolveUnion`), since the flag means "refuses `addMemberType`".
- The types a dependent field admits, with a pin and a discriminator applied, are an accessor on the same view.
- Not in it: any change to what a read does, or to which failures compile defers.

## Open

- The host and the name: `TsonCompiledSchema.binding()`, the registry, or `Tson`.
- Whether a choice should be judged against a `DataClassUnion` at compile at all.
- Whether the differing lookups in the table are intended. The root pre-check asking the written name is the one most likely
  to be observable.
- `ExternTarget.Landing.declaringSchema()` returns `originOf`, where the binder is asked with `declaringIdentity`. They
  differ only when a foreign schema's `!!id` is not already canonical; not tested.

---

*This draft is the text of an issue for tson-java's tracker: its first line is the title and the rest is the body. The
entry that registers the gap in this repository is `UPSTREAM.md`, "The entry and class a bind-mode read uses for a type
are not a public question". Strip this note when filing.*
