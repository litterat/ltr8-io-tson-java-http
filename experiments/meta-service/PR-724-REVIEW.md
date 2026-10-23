# PR #724 review

Drafted from tson-java-http and not posted. Each round stands alone and reviews the head named in its heading; earlier
rounds are kept below for the findings later ones refer to.

## Round 3: `6a0f18ec`

Ready from the consumer side; nothing below blocks.

**Consumer result.** tson-java-http's full build passes against `6a0f18ec` unchanged, and adopting the feature is now
one line. Retyping `meta-http.tn`'s `request?: type_ref` to `record`, with `Operation` untouched (the request still
returned from `references()`), passes the whole suite and judges every case round 2's table lists: `order` and
`problem` accepted; `text`, `user_id`, `nothing_here`, `order.sku`, the operation itself, `42`, `"order"` and an inline
`{ sku: text }` refused with the same messages. Round 2's finding 1 is closed by construction: there is no longer a
list for a host to file a reference in wrongly.

**Also checked:**
- A template application's body is judged: under `t => <T> !interface { methods: { m => { request: text
  response: T } } }`, `a => t<order>` is refused at `a`. Keying the collected names by body survives materialisation.
- The bare-token writer keeps a reference's annotations: an `@alias`-annotated `TypeRef` writes as
  `@alias:"field_name" order`.
- The atom-slot message and both import nits are fixed in the diff.

### On the three decisions

1. **`TsonSchema.valuesByName` rather than a `TypeDefinition` component.** Agreed for an interim that `ref<T>`
   removes. One question: resolved output doesn't carry it, so a schema linked from a re-read resolved document would
   have an empty list, and its by-name references would go unjudged. If no path links from resolved output, a
   sentence in the Javadoc saying so would settle it; if one does, that path needs the read to collect.
2. **The read records the path only, and the linker reads the declared type along it.** Right: the type comes from
   the schema, never the host. The choice gap is thrown out of the linker as an `UnsupportedOperationException`,
   even to a collecting caller. That is consistent with the existing gap for a host body that isn't a Java record,
   and with the rule for gaps outside a read, but no test pins it.
3. **An imported entry's list is read from the declaring schema through the loader.** Agreed; it matches decision 8.

### Smaller

- **Keying by body identity assumes each read returns a fresh object.** A host with a canonicalising factory that
  hands two entries one shared instance would have the second read's list replace the first's, leaving the first
  entry's names unjudged. That's contrived, but the assumption is worth a comment where the map is built.
- **The annotation hook collects into the same map**, keyed by the annotation value, which is no entry's body, so a
  by-name reference inside an annotation value would be collected and never judged. If an annotation's type can't
  hold one, a comment saying so; if it can, it needs judging or refusing.

### For the `ref<T>` backlog entry

Worth adding: **a template parameter has to be admitted at a reference slot.** Today `t => <T> !interface { methods:
{ m => { request: T } } }` is refused ("parameter 'T' stands where 'record' is declared, which is neither a type
reference nor a scalar"), which is §5.10 applied correctly. The consequence is that a family templated on its request
type (`create => <T> !operation { request: T … }`) can't be written once `request` is record-typed. Under `ref<T>`
the parameter stands at a reference slot, and the slot's bound applies at application, so `create<text>` is refused
and `<T>` itself stays unbounded. That is the one cost of tson-java-http adopting `request?: record` before `ref<T>`
lands; its demos template `T` only at response bodies, which stay `type_ref`.

## Round 2: `e6dd78b1`

Reviewed from the consumer side against `e6dd78b1`. Collapsing `QualifiedName` into `TypeRef` is right, and it makes
this consumer's change smaller: `Operation.request` is already a `TypeRef`, so retyping `meta-http.tn`'s
`request?: type_ref` to `record` is one schema line, plus moving the field from `references()` to `names()`.
tson-java-http's full build passes against the branch unchanged, and its suite passes with that change made. The
slot then refuses what it should:

| `request:` | Result |
|---|---|
| `order`, `problem` | accepted |
| `text` | "wants a 'record', and 'text' is an entry whose body is built by 'text_type', which is no 'record'" |
| `user_id` (`user_id => uuid`) | the same, naming the alias |
| `nothing_here` | "is no entry of this schema or its imports" |
| `order.sku` | "'order' is a type: a type entry admits no further segment" |
| `create_order` (the operation itself) | "built by 'operation', which is no 'record'" |
| `42` | "is no qualified name: … '42' is not -- … an identifier never begins with a digit or a sign" |
| `"order"` | "found the quoted token \"order\", which is text rather than a name" |
| `{ sku: text }` | "expected the name of a 'record' value, found a record" |

Round 1's two findings are now recorded as interim, closed by `ref<T>` and the namespace kind, and the data-opacity
paragraph states the rule they rest on. I agree with both closures. The collapse does change the shape of the second
finding, though, which is finding 1 here.

### 1. With one Java type, the list a host returns a reference from decides how it is judged

A `type_ref` slot and a by-name slot now bind the same `TypeRef`, so the only thing deciding which judgement a
reference gets is whether the host returns it from `references()` or `names()`. Both misfiles compile:

| The host returns | from `references()` | from `names()` |
|---|---|---|
| a `record` slot's reference | `request: text` and `request: user_id` **accepted**: resolved as a type, never judged against `record` | judged correctly |
| a `type_ref` slot's reference | judged correctly | every value refused: "wants a 'type_ref', and 'order' is an entry whose body is built by 'record', which is no 'type_ref'" |

The first row's misfile is the one that matters. It is the natural mistake when migrating an existing slot: retype
the schema to `record`, leave `references()` alone, and the slot checks nothing a `type_ref` didn't. Under
`QualifiedName` that mistake didn't compile, because a `QualifiedName` couldn't go into `references()`.

There are two interim fixes short of `ref<T>`:
- **The reader collects the names.** `BindTargets` already decides which positions get a `ReferenceNameReader`, so
  the read can record each `(slot, TypeRef)` it produces and hand the linker every by-name reference, with no
  `names()` at all. That closes the silent misfile, and round 1's finding 2 with it, now rather than at the
  namespace kind.
- **At least, refuse the loud one accurately.** The linker can refuse a `Named` whose slot is declared `type_ref`
  as a `BindMismatchException` ("a type reference; return it from `references()`") rather than a schema error that
  blames every valid type. This doesn't touch the silent misfile.

### 2. A `TypeRef` at an atom slot gets the wrong advice

With `binding`'s `route: text` bound by a `TypeRef route` component, the refusal is still a `BindMismatchException`,
as `design/meta-layer-data-kind.md` says, but it is now the generic structural one: "component 'route' binds
io.ltr8.tson.schema.meta.TypeRef structurally -- register it as an atom (DataBindContext.Builder.registerAtom, or
@Transparent on a single-component record)". Registering `TypeRef` as an atom is the wrong fix. Round 1's
`byName` had a specific message ("a value stands by name only at a record-typed position"); `heldByName`'s
`default -> false` now falls through to `shaped` instead. Worth keeping the specific message.

### 3. Nits

- `ResolverBindContext` imports `io.ltr8.tson.schema.meta.Token` twice.
- `ValuesByName`'s new `io.ltr8.tson.atom.IdentifierGrammar` import sits among the `schema.meta` imports.

### On the follow-ups in the summary

- **The writer.** Agreed: `TsonObjectWriter` should write the bare token wherever `arguments` is absent, as §8.1
  already requires, not the reader accept the record form. A fourth commit is the right size.
- **`42` refused at link rather than at read.** Fine. It is still a resolver error, and the message names the
  segment.
- **`BACKLOG.md` entries for `ref<T>` and the namespace kind.** Agreed.

### For `ref<T>` and the namespace kind

Two rules on this branch that those entries will have to revisit:

- **Decision 6 and `nameIn` don't survive walkable routes.** If an api's routes are a namespace (keyed by
  `path_template`, then by `http_verb`), a segment such as `/orders/{id}` is no §7.7 identifier. Decision 6 judges
  every segment by §7.7, and `ReferenceNameReader.nameIn` makes a quoted token text, never a name. The namespace
  kind will need each segment read by the key type of the namespace it lands in. Decision 6 set that aside because
  "the grammar would depend on what the walk has reached so far", but once only namespaces are walkable, the walk
  is over declared namespaces whose key types the linker knows. It also needs a spelling:
  `orders_api."/orders/{id}".GET` is not one token under §7.2.
- **The reference Java receives should carry identity, not spelling.** The `TypeRef` holds the name as written
  (`orders.methods.place_order`), and nothing public turns it into what it reached. A coverage check counted per
  declaration (every method of `orders` bound, once each) has to re-walk the name itself, which is the reader
  reimplementing the resolver. When the channels merge, the value handed to Java should be the resolved reference:
  the declaring schema's identity, the entry and the member path, which is `originOf` plus the path.

## Round 1: `aa883b0b`, `677e0d00`

Reviewed from the consumer side (tson-java-http, built against this branch). The lookup, the judgement and the
grammar are right, and I'd take them as they are: the name survives into resolved output, nothing keys on spelling,
atom-typed slots keep their literals, the rule stays out of data documents, every hop is written, and each refusal
names its step. Two findings, both reproduced against `677e0d00`, and one cost.

**Consumer result.** tson-java-http's full build and suite pass against this branch unchanged. Retyping
`meta-http.tn`'s `operation.request` from `type_ref` to `record`, with the component as `QualifiedName` and declared
through `names()`, also passes the whole suite, and gains what it was for: `request: text`, `request: user_id`
(`user_id => uuid`) and `request: nothing_here` are refused at load with the step named.

#### 1. The host class, not the schema, decides whether a slot takes a name or a braced value

Two constructors in one meta, with the identical slot:

```
method  => { request?: record  response?: type_ref  errors?: [record] }
binding => data & { method: method  verb: http_verb }
carried => data & { method: method  verb: http_verb }
```

bound by `Binding(QualifiedName method, String verb)` and `Carried(Method method, String verb)`:

| Written | `!binding` | `!carried` |
|---|---|---|
| `method: orders.methods.place_order` | accepted | refused: "expected a record for 'method', found the name …" |
| `method: { request: order_ref }` | refused: "expected the name of a 'method' value, found a record" | accepted |

So one schema admits different documents depending on the Java component type, and a second implementation cannot
tell from the resolved meta which spelling is valid. It also departs from the entry's own text — "a slot typed by
a record the meta declares is unaffected: `method: { response: order }` is plain data of that record, inline or by
name". The choice being made in Java is exactly the substitution / identity / either question the entry leaves open,
and it should be visible in the schema whichever way it is settled: either every record-typed slot in a schema
document admits both spellings (and a host must accept both — a sealed interface over the record and
`QualifiedName`, say), or the slot's type says which (`ref<method>` for by name only, `method` for inline only,
`( method | ref<method> )` for either).

#### 2. A name is judged only if the host class declares it

A `Data` class that does not override `names()` accepts `request: user_id` (a uuid) and an unresolved
`request: nothing_here` with no diagnostic:

```
forgetful => data & { methods: {method_name => method} }      -- Forgetful(Map<String, Method> methods), no names()
f => !forgetful { methods: { m => { request: nothing_here } } }   -- []
```

This is not new — a body that forgets `references()` hides an unresolved `type_ref` the same way, which I confirmed —
but this PR widens it from type references to every record-typed slot, and the verdict on a schema now depends on
host-class cooperation. The reader already knows each position's declared type and is what turns the token into a
`QualifiedName`, so it could collect `(slot, name)` pairs during the replay and hand them to the linker, leaving
`names()` (and eventually `references()`) as nothing a host can get wrong.

#### Cost: the field name is part of every reference

Without a namespace kind, a binding writes `orders.methods.place_order`, so the constructor's field name `methods` is
in every reference, and renaming it breaks every binding in every governed schema. Fine for this PR; worth recording
in #13 as what a namespace kind (`orders.place_order`) would remove.
