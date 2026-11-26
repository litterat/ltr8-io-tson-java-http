# tson-service — a service's description of itself (`io.ltr8.tson.service`)

What a service says about itself, independent of any transport: `service.tn` (in `src/main/resources`) and three packages.
`io.ltr8.tson.service.protocol` holds `Protocol`, `Exchange`, `Signature` and `ExchangeName`. `io.ltr8.tson.service.http`
holds the HTTP records, `Route`, placement, `TsonServiceSchema`, `TsonServiceDescription`, `TsonServiceCoverage` and the
type-resolution helpers `Landed`, `ChosenTypes` and `PayloadType`. The root `io.ltr8.tson.service` holds `TsonBindings`
alone. `tson-http` depends on this module; this module depends on tson-java and `ltr8-bind` directly and on nothing of this
repo. Start with the note for the type you are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonBindings` (`of`, `bySchema`) | `binding.md` |
| `service.tn`'s protocol side, `ExchangeName`, the bound records | `service-description.md` |
| `TsonServiceSchema`, `TsonServiceDescription`, `TsonServiceCoverage`, `Landed`, `PayloadType` (all in `http`) | `service-reader.md` |
| The `service.http` side: endpoints, placement, `Route`, security schemes | `http-projection.md` |
| The join, the protocol layer (backlog) | `invoke.md` |

Rules that bite here:

- Nothing here names `tson-http`'s types: not `TsonMediaType`, not `TsonProblemSchema`, not the codec. The three media
  type names the reader compares against are the package-private `MediaTypeNames`.
- No external runtime dependency; tson-java's own modules (`io.ltr8:tson`, `io.ltr8:ltr8-bind`, `io.ltr8:ltr8-annotation`)
  are not external.
- `protocol` imports nothing from `http`, `http` imports `protocol`, and the root imports neither. A `protocol` class that
  imports `http` breaks the separation the layout exists for. The schema class and the reader are in `http` because
  `service.tn` is one schema and its bindings map names records of both sides.
- A class in `protocol` or `http` binds to a `service.tn` record only by being named in
  `TsonServiceSchema.bindings()`, an explicit map keyed by qualified name (`service.binding`), as its `@Typename` is; a
  record added to the layer binds nothing until a line is added there, and an application binding one of those names
  is refused by `bindingsWith`.
- A payload type a route names through an extern is read as the entry it lands on in the declaring schema
  (`PayloadType`), so a binding keyed by the extern's local name serves nothing; `TsonServiceDescription` refuses it at
  startup, and two schemas declaring one name bind through `TsonBindings.bySchema`.
- This module has no test sources of its own. Its tests and fixtures (`OrdersExamples`, `ExternExamples`,
  `TsonServiceDescriptionTest`) are in `tson-http`'s test tree, in package `io.ltr8.tson.service.http`: the fixtures import
  `problem.tn` and one test uses `TsonHttpCodec`, both `tson-http`'s. They reach the package-private members here
  because `tson-http`'s tests run on the class path. `MediaTypeNamesTest` there holds `MediaTypeNames` to
  `TsonMediaType`.
