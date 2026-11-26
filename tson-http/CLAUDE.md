# tson-http — the server-agnostic core (`io.ltr8.tson.http`)

Everything that is about *TSON over HTTP* rather than about a particular server. The service description and
`TsonBindings` are `tson-service`, which this module depends on and re-exports (`requires transitive`). Start with the
note for the type you are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonHttpCodec`, `TsonMediaType`, `TsonAcceptHeader`, `TsonSchemaHeader`, `TsonAcceptSchemaHeader`, `TsonSchemaCatalog` | `codec.md`, then `json-encoding.md` for the `acceptingJson` side |
| `TsonHttpException`, `TsonProblem*`, `problem.tn` | `status-classification.md`, `name-policy.md`, `schema-authoring.md` |
| `TsonSchemaVersions`, `TsonDocumentPeek` use | `schema-versions.md` |
| The description's tests and fixtures (`OrdersExamples`, `ExternExamples`, `TsonServiceDescriptionTest`), under `src/test/…/io/ltr8/tson/service/http/`; the code they test is `tson-service`'s | `tson-service/CLAUDE.md` |
| `TsonDeployment`, `deployment.tn` | `deployment-descriptor.md` |
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
  `deployment` (a descriptor is `deployment.descriptor`); a bound class's `@Typename` is the qualified name.
- `HttpSchemaSource` is upstream's. What stays here is the mapping of its failures to statuses and
  `TsonHttpSchemaSourceIntegrationTest`; do not retest upstream's policy.
- The description's tests and fixtures are in this module's test tree, though the code is `tson-service`'s: the fixtures
  import `problem.tn` and one test uses `TsonHttpCodec`, neither of which `tson-service` may name. They reach the
  package-private members of `io.ltr8.tson.service.http` because this module's tests run on the class path.
- Experiments' probe tests join this module's test source set from `experiments/*/java`; a probe failing after a
  library change is information, not a regression.
