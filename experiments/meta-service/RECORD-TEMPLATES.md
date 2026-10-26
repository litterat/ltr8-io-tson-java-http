# A record template is a record

**Status: proposal for a follow-on to tson-java PR #725.** Measured on that PR's head (`17d0ee77`). Nothing here
resolves today in the proposed form.

## The question

PR #725 gives `template` a chain, `template => top & chained & { … }`, because a record-bodied template sits in the
IS-A tree. It is the base its declared applications are members of, and IS-A what its parents reach. Does that make
it a type? If so, why is it built by a constructor that doesn't compose `type`?

## What a template is today

[TSON-SCHEMA] §5.10 answers by *elimination*. A position typed by a template would read a value against arguments
nothing has fixed, which is ill-defined. A **record-bodied** template is the exception: a value at its position is a
value of a *declared member*, picked by tag or by discriminators, and every member is closed. So a record template is
a type, an ABSTRACT family base, and every other template isn't.

Measured on the PR head:

| entry | `extension` | chain | at a type position |
|---|---|---|---|
| `box => <T> { v: T }` | ABSTRACT | `subtypes: [bt]` | admitted, as a family base |
| `sbox => <T> shape & { v: T }` | ABSTRACT | `parents`, `supertypes`: `[shape]` | admitted; `shape.subtypes` names it |
| `arr => <T> [T]` | absent | empty | refused: "not a type until it is applied" |
| `vec => <T> !array { element_type: T  min_items: 1 }` | absent | empty | refused |
| `alias => <T> box<T>` | absent | empty | refused |

So the language gives two answers to "is it a type?":

1. **The kernel says no, for every template.** #14 makes an entry's kind its value's kind, and `template` doesn't
   compose `type`.
2. **§5.10 says yes, when `extension` is present.** The kernel's own doc agrees: "absent means this template is no
   type".

The implementation carries both answers:
- `TypeKind` has a `TEMPLATE` kind of its own.
- A check wanting "a type" asks "a `Type`, or a template with `extension`".
- Every `TemplateBody` carries a chain that only record templates ever fill.

`template` also holds the base's facts, `extension` and `discriminators`, as optional fields whose presence decides
the kind. **A record template is hiding inside `template`.**

## Two ways out

Both make "is it a type?" the test #14 uses for every other entry, IS-A `type`. Both leave `template` for what is
genuinely not a type.

### A. Split the constructor

```
template        => top & { parameters: [template_param]  template: text }     -- a type constructor; no chain
record_template => template & product & {
  access_pattern?: = NAMED   size_type?: = VARIABLE
  extension?:      = ABSTRACT
  discriminators?: [text; 1..]
}
```

The smallest change:
- The held text stays.
- The resolver already decides record-bodiedness, to derive `extension`, so the same decision picks the tag.
- `record_template` reaches `chained` through `product`, and plain `template` drops it.

It leaves one record described twice: once as the base's facts on `record_template`, and once as held text whose
own `abstract` mark is the *instantiation's* extension. §5.10 calls these "two levels of `extension` … read from
different places".

### B. Collapse it into `record`: no template text, just optional parameters

```
record => product & {
  access_pattern?: product_access_type = NAMED
  size_type?:      product_size_type = FIXED
  parameters?:     [template_param; 1..]   -- present: an open record, the base its declared applications are members of
  fields:          field_map
  groups?:         [field_group]
  extension?:      record_extension_type ~ OPEN
  discriminators?: [text; 1..]
  name_type?:      type_name
}

template => top & { parameters: [template_param]  template: text }            -- every other open body; no chain
```

The resolved output for `box => <T> { v: T }` changes:

```
-- today
box => !template { parameters: [ { name: T  type: type_ref } ]  template: "!record { … }"  extension: ABSTRACT
                   chain: { subtypes: [bt] } }
-- proposed
box => !record { parameters: [ { name: T  type: type_ref } ]  fields: { v => !record_field { type: T } }
                 chain: { subtypes: [bt] } }
```

**What it settles:**

- **A record template is a record.** Kind `PRODUCT`, IS-A `type`, chain from `type`, with no new constructor. Whether
  it is open is `parameters` being present. `TypeKind.TEMPLATE` then means only a non-record template, which is never
  a type.
- **`extension` has one meaning.** It is the author's mark, stated for the applications. That a record *with
  parameters* has no direct instances is a rule about parameters, not a field. This removes the "two levels of
  `extension`" sentence from §5.10.
  - A consequence to decide: `final <T> { … }` stops being a resolver error and means each application is FINAL.
    The base still has its members. §5.10 refuses `final` on a template today only because the template-level mark
    is the base's.
- **Erasure is visible.** §5.10 says a base carries "what survives erasure … a field whose type mentions a type
  parameter omitted". With parameters on the record, those are just the fields whose type names no parameter. A
  resolved-output consumer reads them, instead of being told never to read the held body (§1.3).
- **`discriminators` stays the base's.** It is derived as it is today (a field written `=?`, or one pinned to a value
  parameter on an unmarked name), and closing drops it from a member, as today.
- **Templates lose the chain.** `chain.parents` becomes "the parents of an open record": all of them while it is open,
  an applied parent like `result<T> &` included. A closed record keeps none, as on the PR head. The chain is again
  `type`'s alone, until namespaces compose `chained` as the one non-type that carries it.
- **No held text for records.** An open record's identity (§8.2) comes from its parsed form, as an open synthetic's
  does today from its held binding record. That form is now a value instead of text.

**What it needs**, measured against what the PR head accepts in a record template:

| a parameter stands at | accepted today | in an open record |
|---|---|---|
| a field's type, `v: T` | ✓ | `record_field.type`: a `type_ref` already names "a parameter of either kind" |
| an argument, `b: box<T>`; an applied parent, `r<T> &` | ✓ | the same `type_ref`, with arguments |
| a sugar form, `vs: [T]`, `vs: [text; N..]` | ✓ | lifts to an open `!template`, as today; the field applies it |
| a refinement, `<T> c ^ { x: T }`; a composition | ✓ | the closed parent's fields, flattened; the parent in `parents` |
| a pin or default, `status: int32 = S`, `~ S` | ✓ | **needs a structural split** (below) |
| a record facet: `optional: B`, `extension: E`, `name_type: N` | ✓ (typed by the slot) | **refused** (below) |
| an annotation, `@doc:A` | refused: "never references it" | unchanged |

- **A value parameter in a pin needs a split.** `record_field.value` is a bare `value` token, so `status: int32 = S`
  can't say structurally that `S` is a parameter. `type_argument` already solved this: `( name: type_ref | value:
  value )`, "so value-typed tokens … are never ambiguous with parameter names". `record_field` needs the same split, a
  literal or a parameter reference. This also lets the resolver check the parameter's declared type against the
  field's type at declaration, which held text can't do while it is unread.
- **A parameter at one of the record's own facets is refused.** Held text admits a parameter wherever a token stands,
  so `<B: boolean> !record { fields: { v => { type: text  optional: B } } }` resolves today, and so do `extension: E`
  and `name_type: N` when their parameters are typed to fit. An open record holds parameters only where its shape
  can name one: type positions, and field values once they are split. This narrows the language. Check the corpus
  for any vector that depends on it before committing.
  - **The alternative is a fallback:** such a template stays `!template`. But that would make whether a template is a
    family base depend on where its parameters stand. Refusing is cleaner.
- **One lexical scope.** `v: T` resolves `T` among the record's parameters before the schema root. Held text hides
  this exception inside a string. An open record makes it a rule about values that every reader of
  `record_field.type` can see.

**The Java cost:**

- **`RecordBody` gains `parameters`.** An open record then matches `case RecordBody` exactly as a closed one does.
  Today a sealed switch over `Top` forces `TemplateBody` to be handled separately, and that safety net goes. The
  binder maps one tag to one class, so a separate Java class for open records isn't available.
  - **The replacement:** one "closed entries" view on `TsonLinkedSchema`, which the linker and compiler iterate. They
    already skip `TemplateBody` entries, so the skip moves into one place.
- **Shrinks:**
  - `TemplateBody` loses `extension`, `discriminators` and `Chained`;
  - the family-base special cases go (`Type.subtypesOf(name, …)`'s template branch, the "a `Type`, or a template with
    `extension`" checks);
  - a family base is read through the same `RecordBody` accessors as any abstract record.
- **The materialiser** closes an open record by substituting into a value rather than re-reading text. For records
  that replaces `WireForm.heldRecord` and its round trip through text.

**Spec sections touched:**
- §5.10: the family-base paragraph, the held-bodies paragraph for records, and the two levels of `extension`;
- §1.3: the resolved-output consumer reads an open record;
- §8.1: `record.parameters`, the `record_field` split, and `template` without the family facts;
- §8.2: an open record's identity;
- §12.1: the abstract rows.

Every record template's resolved output changes, so every fixture and corpus vector holding one changes too. No data
document's verdict changes.

## Recommendation

**B, as the follow-on PR.** A fixes the question asked, and B fixes the cause: the record template inside `template`.
B also removes, for records:
- held text;
- the two-level `extension`;
- a family base whose fields a consumer is told not to read.

A is the fallback if refusing parameters at a record's own facets turns out to cost something real.

Sequencing: after #725, which introduced `chained` and the chain record that B relies on. It belongs before the
namespace kind, because both have to say what is looked up lexically, and B makes the first case of it visible.

## Open

- **`final <T> { … }`:** adopt "each application is FINAL", or keep refusing it.
- **The `record_field` split's spelling**, and whether a default (`~ S`) and a pin (`= S`) both take a parameter. The
  probe above accepts both today.
- **Whether `template_param` moves** to sit beside `record` in the kernel, now that two constructors use it.
- **A namespace's parameters.** The namespace table in `README.md` lists template parameters as a lexically scoped
  namespace. An open record then holds two keyed sets, `parameters` and `fields`, so a members facet, if one is ever
  proposed, may want to cover both.
