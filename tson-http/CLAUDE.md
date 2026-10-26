# tson-http — the server-agnostic core (`io.ltr8.tson.http`)

Everything that is about *TSON over HTTP* rather than about a particular server. Start with the note for the type you
are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonHttpCodec`, `TsonMediaType`, `TsonAcceptHeader`, `TsonSchemaHeader`, `TsonAcceptSchemaHeader`, `TsonSchemaCatalog` | `codec.md`, then `json-encoding.md` for the `acceptingJson` side |
| `TsonHttpException`, `TsonProblem*`, `problem.tn` | `status-classification.md`, `schema-authoring.md` |
| `TsonSchemaVersions`, `TsonDocumentPeek` use | `schema-versions.md` |
| `TsonBindings` | `binding.md` |
| `api/` (`TsonApiSchema`, `TsonApiDescription`, `TsonApiCoverage`, the bound records), `meta-service.tn` | `service-description.md` |
| `TsonDeployment`, `deployment.tn` | `deployment-descriptor.md` |
| `probe/`, `UpstreamGapsTest` | `tson-api-surface.md`; `experiments/CLAUDE.md` for the probes that live there |

Rules that bite here:

- `TsonMediaType` and `TsonAcceptHeader` are value types with no status policy; malformed input is an
  `IllegalArgumentException` and the codec decides what it is worth.
- `TsonHttpException.from(RuntimeException)` is the entire status policy. Put nothing status-shaped anywhere else, and
  key nothing on `Diagnostic.Code.verdict()` alone: it answers whether the document was judged, not who must act.
- Problem `type` URIs live under `https://ltr8.io/2026/38/http/problems/`; `ltr8.io` is the implementation, `tson.io`
  the specification. `ReadmeTest` holds the README's problem-type table to the types minted in `src/main`.
- `problem.tn` is this project's own schema and diverges from tson-cli's `diagnostics.tn` on purpose. Its
  `diagnostic_code` is a hand copy of `Diagnostic.Code`; `TsonProblemSchemaTest` keeps it current.
- `HttpSchemaSource` is upstream's. What stays here is the mapping of its failures to statuses and
  `TsonHttpSchemaSourceIntegrationTest`; do not retest upstream's policy.
- A public class in `io.ltr8.tson.http.api` named like a `meta-service.tn` record binds to it whether meant to or
  not, which is why the computed placement is `RequestPlacement` and not `Placement`.
- Experiments' probe tests join this module's test source set from `experiments/*/java`; a probe failing after a
  library change is information, not a regression.
