# `meta-service-1.tn` -- interfaces and operations in one meta layer

**Status: experiment.** Nothing here is served, published or depended on. `meta-http-1.tn` remains the meta layer
the demos use.

## The question

`meta-http-1.tn` describes an HTTP API: a schema governed by it declares `!operation` entries whose `request`
and `response` are type references the compiler resolves. Underneath an HTTP API there is usually a
transport-neutral interface -- methods with a request, a response and errors -- and a service may want to
declare just that, or just its web service, or both, with an HTTP operation being one *projection* of a method.
Can one meta layer hold all three, and what does the third actually need?

## The sketch

[`meta-service-1.tn`](meta-service-1.tn), with three uses in [`examples.md`](examples.md). The shape:

- `signature` -- a plain record: `request`, `response`, `errors`, `safe`, `idempotent`, the two stream flags.
  The transport-neutral contract, and deliberately an ordinary record rather than a constructor (below).
- `method => ~data & signature` -- a method is a signature and nothing more.
- `operation => ~data & signature & { verb  path  parameters?  status ~ 200  … }` -- a method with an HTTP
  binding. Composing the *same* record into both is what lets one declaration be read either way: the
  interface view of an `!operation` is the entry with the binding fields dropped.
- `binding => ~data & { method: type_name  verb  path  … }` -- the two-declaration form, for a method declared
  elsewhere. Its `method` slot is a bare identifier the resolver does not check; see "the real problem".
- `interface => @annotation identifier` -- grouping only. An interface *is* a schema document: its `name` is
  the `!!id`, `extends` is `!!import`, and "two interfaces may not declare one method" is §2.2.3's collision
  rule. It cannot list its methods, because that would be a list of references to `data` entries.
- An error type pins the status it inherits from `problem` (`sku_not_found => problem & { status: = 404 … }`),
  which is how an operation's `errors` get one without a `responses` list.

`MetaServiceSketchProbe` resolves it against the real library and pins the constructs it leans on: a `~data`
constructor with a record mixin and no trailing body; a fixed value in the constructor's body
(`request_stream: = false`) that a governed schema cannot lift; `[type_ref]` resolving per element; an
annotation declared in the meta layer read back from the entry; and the rule everything bends around --

> `'x' field 's' names 'm', which is built with 'method' and describes something other than a data value -- it
> is declared by this schema but is not a type, so nothing can be typed by it`

## The real problem, and the two ways it can be modelled today

"Both" in the sense that matters is not one entry carrying method and binding at once. It is `place_order`
declared once on the interface and a *separate* entry -- possibly in another document -- saying "POST /orders is
that method". That second entry has to **refer** to the first, and [TSON-SCHEMA] §4.1 makes a `kind: DATA` entry
something that can be declared and applied but never named: field type, element, variant, argument,
composition operand, refinement source -- all refused. `operation => method & { … }` is refused for the same
reason. `BindingProbe` measures the two shapes that work:

**A. Bind by identifier; the reader checks it.** `create_order => !binding { method: place_order  verb: POST
path: "/orders" }`. Resolves, reads back, and a typo (`method: plaec_order`) loads clean -- the resolver treats a
`type_name` slot as data. The check is one lookup in the merged namespace, at startup, the same tier as
`TsonApiCoverage`. Keeps "an operation is not a type"; becomes a compiler check the day the kernel has a
reference form for data entries.

**B. A method is a type -- the type of its call record.** Under plain `meta.tn`, no meta layer at all:

```
method => <Req, Resp> { request: Req  response: Resp?  safe: boolean ~ false  idempotent: boolean ~ false }
http   => { verb: http_verb  path: text  status: status_code ~ 200 }

place_order  => method<order, order> & { errors: [sku_not_found]? }
create_order => place_order & http & { verb: = POST  path: = "/orders"  status: = 201 }
```

The operation IS-A its method, the binding reads back as `REQUIRED_FIXED` fields, and a plan step is a value
of the method type -- `!create_order { request: { … } }` reads, `verb: GET` on it is refused. Two things it
taught: `place_order => method<order, order>` alone is an alias to an instantiation with no body to compose
with (give it `& { … }`); and schema facts stated as fields are injected into every instance, so each value
carries its own URL. That cost is what the third option removes.

**C. The spec change.** The kernel's own 2×2 -- record (names → data), schema (names → declarations), map
(data → data) -- has an empty cell: a keyed set of declarations whose keys are values, `!api { "/orders" => … }`.
With a namespace as a value, an `!interface { place_order => method<…> }` is a scoped set, an api member is
keyed by route and needs no invented name, `&`/`^`/`-` already mean extends/tighten/subset on it, and templates
over interfaces are the CRUD payoff at the right level. Filed as tson-java `SPEC-FEEDBACK.md` *"a namespace
should be a value -- the kernel's 2×2 has an empty cell"*, with the argument in full.

## Files

- `meta-service-1.tn` -- the sketch, its reasoning in its own `@doc`s.
- `examples.md` -- interface only, web service only, both.
- `java/…/experiment/metaservice/` -- the bound records the `~data` constructors need (`Method`, `Operation`,
  `Binding`, `Parameter`, two enums), and the two probes. Field names match the schema's exactly
  (`request_stream`), because binding does no case conversion.
