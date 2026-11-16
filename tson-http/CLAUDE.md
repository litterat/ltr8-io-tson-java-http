# tson-http — the server-agnostic core (`io.ltr8.tson.http`)

Everything that is about *TSON over HTTP* rather than about a particular server. Start with the note for the type you
are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonHttpCodec`, `TsonMediaType`, `TsonAcceptHeader`, `TsonSchemaHeader`, `TsonAcceptSchemaHeader`, `TsonSchemaCatalog` | `codec.md`, then `json-encoding.md` for the `acceptingJson` side |
| `TsonHttpException`, `TsonProblem*`, `problem.tn` | `status-classification.md`, `name-policy.md`, `schema-authoring.md` |
| `TsonSchemaVersions`, `TsonDocumentPeek` use | `schema-versions.md` |
| `TsonBindings` (`of`, `bySchema`) | `binding.md` |
| `api/` (`TsonApiSchema`, `TsonApiDescription`, `TsonApiCoverage`, `Landed`, `PayloadType`, the bound records), `service.tn`, the `OrdersExamples` and `ExternExamples` fixtures | `service-description.md` for the layer, `service-reader.md` for the reader and the wiring, `http-projection.md` for `service.http`, endpoints and `Route` |
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
- A class in `io.ltr8.tson.http.api` binds to a `service.tn` record only by being named in
  `TsonApiSchema.bindings()`, an explicit map keyed by qualified name (`service.binding`), as its `@Typename` is; a
  record added to the layer binds nothing until a line is added there, and an application binding one of those names
  is refused by `bindingsWith`.
- A payload type a route names through an extern is read as the entry it lands on in the declaring schema
  (`PayloadType`), so a binding keyed by the extern's local name serves nothing; `TsonApiDescription` refuses it at
  startup, and two schemas declaring one name bind through `TsonBindings.bySchema`.
- Experiments' probe tests join this module's test source set from `experiments/*/java`; a probe failing after a
  library change is information, not a regression.
