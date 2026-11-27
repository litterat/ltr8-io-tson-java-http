# tson-service — a service's description of itself (`io.ltr8.tson.service`)

What a service says about itself, independent of any transport: `service.tn` (in `src/main/resources`) and four packages.
`io.ltr8.tson.service.protocol` holds `Protocol`, `Exchange`, `Signature`, `ExchangeName` and the join
`TsonServiceProtocol`. `io.ltr8.tson.service.http` holds the HTTP records, `Route`, placement, `TsonServiceSchema`,
`TsonServiceDescription`, `TsonServiceCoverage`, and the type-resolution helpers `ChosenTypes` and `PayloadType`.
`io.ltr8.tson.service.internal` is unexported and kept out of the generated Javadoc; it holds `Landed`, which `protocol`
and `http` share. The root `io.ltr8.tson.service` holds `TsonBindings` alone. `tson-http` depends on this module; this
module depends on tson-java, `ltr8-bind`, `ltr8-annotation` and `tson-atom` directly and on nothing of this repo. Start
with the note for the type you are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonBindings` (`of`, `bySchema`) | `binding.md` |
| `service.tn`'s protocol side, `ExchangeName`, the bound records | `service-description.md` |
| `TsonServiceSchema`, `TsonServiceDescription`, `TsonServiceCoverage`, `PayloadType`, `Landed` (`internal`) | `service-reader.md` |
| The `service.http` side: endpoints, placement, `Route`, security schemes | `http-projection.md` |
| `TsonServiceProtocol`, the join, the protocol layer | `invoke.md` |

Rules that bite here:

- Nothing here names `tson-http`'s types: not `TsonMediaType`, not `TsonProblemSchema`, not the codec. The three media
  type names the reader compares against are the package-private `MediaTypeNames`.
- No external runtime dependency; tson-java's own modules (`io.ltr8:tson`, `io.ltr8:ltr8-bind`, `io.ltr8:ltr8-annotation`,
  `io.ltr8:tson-atom`) are not external.
- `protocol` imports nothing from `http`, `http` imports `protocol`, `internal` imports neither, and the root imports
  none of the three. A `protocol` class that imports `http` breaks the separation the layout exists for. The schema class
  and the reader are in `http` because `service.tn` is one schema and its bindings map names records of both sides.
- A `{@link}` to `Landed`, or any `internal` type, from an exported package breaks the Javadoc build: the package is
  excluded from it. Name it in `{@code}`.
- The join's atom check copies one name-keyed rule for the kernel's `boolean` from a package-private part of the
  compiler, and its test for a closed union is its own; each is pinned in `UpstreamGapsTest` and flips when upstream
  answers. A protocol the join cannot judge (a namespace type, a tuple, a record written in place) is refused, not
  skipped. It does not check a record's fields, facets or a bridge's conversion (`design/invoke.md`, "The join").
- A class in `protocol` or `http` binds to a `service.tn` record only by being named in
  `TsonServiceSchema.bindings()`, an explicit map keyed by qualified name (`service.binding`), as its `@Typename` is; a
  record added to the layer binds nothing until a line is added there, and an application binding one of those names
  is refused by `bindingsWith`.
- A payload type a route names through an extern is read as the entry it lands on in the declaring schema
  (`PayloadType`), so a binding keyed by the extern's local name serves nothing; `TsonServiceDescription` refuses it at
  startup, and two schemas declaring one name bind through `TsonBindings.bySchema`.
- The join's tests are this module's own (`TsonServiceProtocolTest` over the protocol-only shop fixtures), which need
  nothing of `tson-http`. The description's tests and fixtures (`OrdersExamples`, `ExternExamples`,
  `TsonServiceDescriptionTest`) are in `tson-http`'s test tree, in package `io.ltr8.tson.service.http`: the fixtures
  import `problem.tn` and one test uses `TsonHttpCodec`, both `tson-http`'s. They reach the package-private members here
  because `tson-http`'s tests run on the class path. `MediaTypeNamesTest` there holds `MediaTypeNames` to
  `TsonMediaType`.
