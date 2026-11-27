# tson-http — the server-agnostic core (`io.ltr8.tson.http`)

Everything that is about *TSON over HTTP* rather than about a particular server, and the HTTP half of the service
description. The layout rule, shared with `tson-service`: **the root package is the API; `schema` holds only the classes
that mirror a `.tn` type, each carrying `@Typename`; `internal` is unexported machinery.** The schemas
(`src/main/resources`) are `problem.tn`, `deployment.tn` and `http.tn`. The root `io.ltr8.tson.http` holds the codec,
the rest of the core, the holder of each schema (`TsonHttpSchema`, `TsonProblemSchema`, `TsonDeploymentSchema`),
`TsonServiceDescription`, `TsonServiceCoverage`, `TsonRoute`, `TsonPayloadType` and `TsonRequestPlacement`, with the
package-private
`ChosenTypes` and `HeaderTemplate`. The mirrors of `http.tn`, whose root map is the module's own, sit in
`io.ltr8.tson.http.schema` (`HttpProjection`, `Endpoint`, `Operation`, `Binding`, `Resource`, `SecurityScheme` and the
rest); those of each other schema the module owns sit in `schema.<root map>`: `schema.problem` (`TsonProblem`,
`TsonProblemDiagnostic`) and `schema.deployment` (`TsonDeployment`). A `schema` package names nothing of the root, and all
three are exported. `TsonBindings` and the protocol description are `tson-service`, which this module depends on and
re-exports (`requires transitive`); `io.ltr8.tson.http.schema` imports `io.ltr8.tson.service.schema` for `Exchange` and
`Protocol`, as `http.tn` imports `service.tn`. Start with the note for the type you are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonHttpCodec`, `TsonMediaType`, `TsonAcceptHeader`, `TsonSchemaHeader`, `TsonAcceptSchemaHeader`, `TsonSchemaCatalog` | `codec.md`, then `json-encoding.md` for the `acceptingJson` side |
| `TsonHttpException`, `TsonProblem*`, `problem.tn` | `status-classification.md`, `name-policy.md`, `schema-authoring.md` |
| `TsonSchemaVersions`, `TsonDocumentPeek` use | `schema-versions.md` |
| `TsonServiceDescription`, `TsonServiceCoverage`, `TsonPayloadType`, `TsonHttpSchema` | `service-reader.md` |
| `http.tn`'s records, endpoints, placement, `TsonRoute`, security schemes | `http-projection.md` |
| `TsonDeployment`, `TsonDeploymentSchema`, `deployment.tn` | `deployment-descriptor.md` |
| `probe/` (a proposal's probe, its bound records in a sub-package named for it), `UpstreamGapsTest` | `tson-api-surface.md` and the proposal under `proposal/`; `experiments/CLAUDE.md` for the probes that live there |

Rules that bite here:

- `TsonMediaType` and `TsonAcceptHeader` are value types with no status policy; malformed input is an
  `IllegalArgumentException` and the codec decides what it is worth.
- `TsonHttpException.from(RuntimeException)` is the entire status policy. Put nothing status-shaped anywhere else, and
  key nothing on `Diagnostic.Code.verdict()` alone: it answers whether the document was judged, not who must act.
- Problem `type` URIs live under `https://ltr8.io/2026/40/http/problems/`; `ltr8.io` is the implementation, `tson.io`
  the specification. `ReadmeTest` holds the README's problem-type table to the types minted in `src/main`.
- `problem.tn` is this project's own schema and diverges from tson-cli's `diagnostics.tn` on purpose. Its
  `problem.diagnostic_code` is a hand copy of `Diagnostic.Code`; `TsonProblemSchemaTest` keeps it current. Every type
  it declares sits under its one root map, `problem` (a body is `problem.details`), as `deployment.tn`'s sit under
  `deployment` (a descriptor is `deployment.descriptor`); a bound class's `@Typename` is the qualified name. Each root
  is reserved only against a root that is no schema map; another schema's `problem => !schema { … }` is open to it.
- `HttpSchemaSource` is upstream's. What stays here is the mapping of its failures to statuses and
  `TsonHttpSchemaSourceIntegrationTest`; do not retest upstream's policy.
- A class in `schema` binds only by being named in its schema's holder (`TsonHttpSchema`, `TsonProblemSchema` or
  `TsonDeploymentSchema`), an explicit map keyed by qualified name; `TsonHttpSchema.bindingsWith` merges an application's
  map and refuses a name both bind.
- The description's tests and fixtures are in this module's own package: `OrdersExamples`, `ExternExamples`,
  `TsonServiceDescriptionTest`, `TsonServiceDescriptionExternTest`, `TsonHttpSchemaTest` and the rest, with fixtures under
  `src/test/resources/io/ltr8/tson/http/orders/`. The fixtures import `problem.tn` and one test uses `TsonHttpCodec`.
- The reader compares media types against `TsonMediaType`'s constants; there is no second list of names.
- Experiments' probe tests join this module's test source set from `experiments/*/java`; a probe failing after a
  library change is information, not a regression.
