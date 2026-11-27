# Backlog

The actively-tracked engineering backlog for this repo, grouped by theme rather than priority. The `design/` notes
carry the technical detail behind several of these items, and `UPSTREAM.md` holds the changes wanted in tson-java.

**This file is a clean list of outstanding work and nothing else.** Every entry must name something someone could pick
up and do. Three things are therefore not entries, however true they are:

- **What was done.** An item that ships comes out entirely. Git history is the log.
- **What was decided against.** A won't-do is not work. It comes out too.
- **What might become work later.** A standing note to revisit something if conditions change is not an outstanding
  item; nobody can act on it today.

Where any of those has to survive its entry, it belongs in the `design/` note, the Javadoc, or the test that owns the
area, where the person who trips over it will actually be looking. Not here.

Prose inside a live entry follows the same rule: say what is left to do and what constrains it.

---

## Distribution

- [ ] **Publish resolvable artifacts** — `tson-http`, `tson-http-jdk`, `tson-http-javalin` and `tson-http-helidon`
  publish to mavenLocal only, behind the sibling's own mavenLocal-only publication, so a consumer needs a checkout of
  both. Publish tson-java to Maven Central (or an interim package registry) first, then this repo. The version should
  carry the spec revision it is built against (`tson.version` in `gradle.properties`, every identity under `/2026/40/`),
  since the revision series changes without compatibility guarantees. Constraint: signing and a release decision in the
  sibling, recorded in `build.gradle.kts`.
- [ ] **Dereferenceable meta identities** — `service.tn`, `http.tn`, `problem.tn` and `deployment.tn` carry `!!id`s under
  `https://tson.io/2026/40/io/ltr8/http/` that nothing serves. Publish them at their identity so an `!!import`
  naming them resolves over HTTP without a bundled copy. Constraint: `design/schema-authoring.md` says a
  schema is edited in place until it is published; publishing freezes it under [TSON-JSON] §3.5.

## Integration surface

- [ ] **A startup facade in `tson-http`** — Each demo's `start` repeats the same page of ceremony, in an order that matters:
  the schema map, `TsonBindings.of` over `TsonHttpSchema.bindingsWith`, resolving the application schema and then
  the description, `TsonHttpSchema.describedBy`, `prepareToWrite` over `boundClasses`, `TsonServiceCoverage.of`.
  One builder taking the description source, the application schemas and the bindings, and handing back the codec, the
  description, the coverage and the catalog, makes the startup rule (`CLAUDE.md`, "The startup rule") impossible to get
  wrong and reduces an adapter demo to its handlers. The facade spans the split ("The module split", below): the
  service side (schemas, bindings, the description, the join) and the HTTP side (the codec, the catalog), so a projection
  other than HTTP reuses the first half. The catalog helper and the resource-loading helper each demo carries belong in
  it. The facade must not require a `!service.protocol`; a `!http.projection` of operations alone is complete.
- [ ] **Read a request by its route** — A handler chooses between `readObject` and `readObjectAs(schema, type)` by
  sniffing `Content-Type` for JSON, with the route's declared request type as the JSON binding. The route already knows
  its request type and its media types, so an exchange-level `read(route, Class)` makes that one call and removes the
  branch from every handler. The same for the response: `respondDescribed` takes a status, a schema identity and a type
  name the route already holds.
- [ ] **Register routes from the description** — A handler claims a declared endpoint with `TsonServiceCoverage.serving(verb,
  path)` and registers its own path. The adapter should instead register every declared endpoint itself, so coverage is
  structural rather than claimed and `requireComplete()` has nothing left to catch. A handler attaches two ways: directly by
  verb and template for a `!http.operation`, and through the service's join ("The module split", below) for a
  `!http.binding`, so a server with no `!service.protocol` is a first-class case. Constraint: the per-framework path
  translation that claim-by-template avoids (`design/service-reader.md`, "An endpoint has no name") becomes the
  adapter's job, once, where a wrong entry is a route that silently never matches; each adapter's suite must prove every
  declared template is reachable.

## The module split

The entries are in build order.

- [ ] **Keep the description layer free of HTTP** — The description layer resembles the sibling's `tson-schema`, a meta layer
  plus a reader over what resolves, and may one day move there in part or whole; so `tson-service` takes no dependency on
  `tson-http` and keeps its vocabulary free of anything HTTP. One HTTP fact stays in the reader on purpose: a business
  error's pinned `status`, read by field name from the error type, is the `http.projection` rule and needs no import of
  `problem.tn`.
- [ ] **After the join, in `tson-service`** — The join is built (`TsonServiceProtocol`, `design/invoke.md`, "The join").
  What remains of the protocol layer: the code taking a call document to an invocation and the outcome to a return, the
  rpc wire form (`call` and `return` documents addressed by `ExchangeName`), a capability of the service and not a module
  of its own, of which `experiments/rpc/` is the draft; a `!service.protocol` document written from a `DataProtocol`, so a
  `!http.projection` can be built over a Java interface that already exists, where the Java-to-data name mapping is this
  layer's to own, since bind maps a data name to a Java name and never the reverse, and `@service.safe`,
  `@service.idempotent` and `@service.destructive` have no Java counterpart and must come from somewhere the writer
  reads; the proxy over a `DataExchangeHandler`, since bind ships none; and a lookup from a `DataExchange` back to its
  `ExchangeName`. The join asks the bind context for an entry past every alias hop, as a TSON read does, where the HTTP
  reader asks a plain alias as written (the plain-alias item under "The description"); one of the two moves when that
  item is done. The join judges no exchange facts: one marked both safe and destructive is refused only when an http
  projection binding its protocol is read, so a protocol no projection binds is not checked on them.
- [ ] **`tson-http` becomes the HTTP projection** — `tson-http` depends on `tson-service`. What it still holds is the codec,
  the media types and headers, `TsonHttpException`, `problem.tn`, the catalog, the deployment descriptor, and the code taking
  an HTTP call to a route and through the service to an invocation; the three adapters are unchanged above it. That code is
  written over the built join. One new endpoint kind is wanted in `http.tn`: a record composing
  `http.endpoint` that names a protocol rather than an exchange, so one POST route accepts call documents for a whole
  protocol. It is the simplest agent-facing surface beside REST, since an LLM tool call is a name and arguments and nothing
  else. `UpstreamGapsTest` pins gaps for both layers; it stays in `tson-http`, which sees everything, until a pin concerns
  `tson-service` alone.
- [ ] **A gRPC projection as a peer of `tson-http`** — Its own module depending on `tson-service` alone, so neither
  projection knows the other exists. gRPC's marshaller is pluggable per method, so a `ServerServiceDefinition` is built at
  startup from the `!service.protocol` with no protobuf: one `MethodDescriptor` per `ExchangeName`, its full name in gRPC's
  `package.Service/Method` form, and the service's wire serialisation for the request and outcome. The first external
  dependency on the service side, stopping at that module.

## Projections

- [ ] **An LLM-facing projection of a protocol** — A projection of a `!service.protocol` beside `http.projection`, for a
  caller that is a model: service capabilities a model integrates with better than today's conventions allow. The Model
  Context Protocol and RFC 9880 are design input, not targets; nothing here emits JSON Schema or implements the MCP
  specification as it stands, and the work is an alternative that builds on what MCP gets right and goes beyond what it can
  do. Take from MCP the shape that fits: discovery of the server and the versions it serves; the protocol's exchanges
  offered as callable tools, `@doc` and `@service.summary` what the model selects on and `@service.safe`,
  `@service.idempotent` and `@service.destructive` the facts a client acts on (retry, reorder, confirm); the catalog's
  schemas and the description offered as resources at their identities; one POST route taking call documents for the whole
  protocol, the interface endpoint kind "`tson-http` becomes the HTTP projection" adds; and change subscriptions, where the
  `!http.stream` endpoint kind `proposal/affordances.md` proposes lands. Go beyond it where TSON does: a tool's request
  and response are the description's own TSON types, self-describing on the wire and judged, with no JSON Schema rendering;
  a declared business error is an outcome a model can act on, typed and composing `problem.details`, rather than text; a
  reference is a judged name; and a security scheme is declared in the description, not negotiated. Constraints: builds on
  the join (`tson-service`) and depends on the interface endpoint kind; `experiments/agent/` is its draft, and this
  item is what those experiments are built toward.

## Adapters

- [ ] **An adapter test kit** — `TsonJdkAdapterTest`, `TsonJavalinAdapterTest` and `TsonHelidonAdapterTest`, the three
  schema-handler suites and the three `OrderServerConcurrencyTest`s are near-copies by rule (`CLAUDE.md`,
  "Conventions"). Publish them as test fixtures (Gradle `java-test-fixtures`) that run against any server given a start
  function on an ephemeral port, so they become the contract a third-party adapter is held to and the in-repo suites
  become three invocations of it. `design/adapters.md` already states the contract in prose.
- [ ] **A Spring adapter** — An `HttpMessageConverter` for `application/tson` and the JSON encoding, and a controller
  advice carrying the error boundary (`TsonHttpException.from` and nothing more, as `design/adapters.md` requires).
  Constraint: the framework dependency stops at the adapter and the module is a real JPMS module.
- [ ] **A JAX-RS adapter** — `MessageBodyReader`/`MessageBodyWriter` and an `ExceptionMapper`, for Quarkus, Helidon MP
  and Jersey. Same constraints as the Spring adapter.
- [ ] **Enforce declared security at the error boundary** — `Route.security()` reads the schemes a description declares
  and nothing enforces them. Answer a route with a scheme and no credential for any of them with a `problem.details` at
  401 (and `WWW-Authenticate` for an `http.auth` scheme, RFC 9110 §11.6.1). Needs a problem type under the problems
  prefix and a place for the credential check, given the route, in each adapter's boundary; verifying a credential stays
  the application's. Constraint: the status policy lives in `TsonHttpException.from` only, so the check raises what that
  method classifies, and no adapter decides a status.

## Client

- [ ] **A client built from the description** — For a caller that is not a model the client surface is REST, which is the
  published description, the self-describing responses and the deployment descriptor (a model has its own projection,
  "Projections" above); a Java client built from the description is therefore a conformance and testing tool. It reads a
  description, reverses `RequestPlacement` to build a request from a record (path variables, query, headers, body), sends it
  with the right `Content-Type` and `TSON-Accept-Schema`, and validates the reply by the schema it names, which is the other
  half of the contract, proven from the server side only. `TsonServiceConformanceTest` holds that logic against the demo;
  extract it into a reusable client and make the conformance test its consumer.

## The description

- [ ] **A description's own refinement of `http.operation`** — Decide whether `http.tn` and `TsonServiceDescription`
  admit an endpoint shape declared in the description itself (`paged_get => http.operation & { … }`, fixing what every
  paged list shares, its endpoints then written `!paged_get { … }`). Without it a CRUD family is written out operation
  by operation. Measure what the resolver does with such an entry, then decide whether the reader accepts an endpoint
  whose type composes `http.operation` and what `TsonHttpSchema.bindings()` binds it to. Constraint: an edit in place
  until `http.tn` is published, a new identity after; and `http.resource` keeps its one bare required field.
  The measurement is `proposal/operation-refinement.md` (measured, its verdict for review), and the alternative aimed
  at Revision 41 is `proposal/value-references.md`.
- [ ] **A bound request record's dependent field is a pinned sibling or a discriminator over a family** — Once tson-java
  refuses at load a bound schema whose free sibling admits an unbound type (work upstream's `BACKLOG.md` holds; the spec
  gains a [TSON-SCHEMA] §11 paragraph and §5.2 is unchanged), make that the rule here and delete `ChosenTypes` with its
  closure walk and the remainder-body rule, rather than keep a narrower copy refusing the kernel's and the layer's own
  records. A family declares the admitted set in the published description, where a client can read it.
- [ ] **A plain-alias payload type is named as what a read asks** — `PayloadType`, `payloadTypes()` and both
  `boundClasses` forms, and `ChosenTypes`, name a plain alias as written. A TSON read of a root type that is a plain
  alias, or an alias of an alias, asks the bind context for the entry the alias reaches. Measured: with the target bound
  the read succeeds, and with only the alias name bound it is a 500 (`MissingBindingException: no bound Java class for
  'receipt'`). So for such a type `boundClasses` warms nothing when the target is bound and a class no read uses when
  the alias is, and `ChosenTypes` skips a bound request that is a plain alias of a record holding a dependent field:
  `request: typed_note` is refused and `note_alias => typed_note` loads. Not measured, from reading the code:
  `requireNoBindingByExternName` skips an alias of an extern a route names, since the entry is not an `Extern`. To do:
  `payloadType`, `payloadTypes()`, `boundClasses` and `ChosenTypes` land past plain alias hops, naming the entry the
  alias reaches in the schema declaring it, as an extern's landing already is; a class bound by the alias's own name
  serves no TSON read and is refused at startup as a binding by an extern's local name is; the join
  (`design/invoke.md`) already asks past every alias hop; measure the alias-of-an-extern
  case in `requireNoBindingByExternName`; flip `TsonServiceDescriptionTest.aPayloadTypeThatIsAPlainAliasIsNamedAsWritten`.

## Documentation

- [ ] **A consumer guide per adapter** — The `design/` notes and the module `CLAUDE.md`s are for whoever maintains this;
  a consumer needs a short path from an empty project to a TSON endpoint on each framework, the headers and problem
  types in one place (`README.md`'s problem-type table is the start), and published Javadoc. `ReadmeTest` executes the
  README against the demo; a per-framework guide should be executed the same way.
- [ ] **How to write an adapter** — One page stating the error boundary, the committed-response rule, the negotiation
  contract and which suites to run, so an adapter for a framework this repo does not ship can be written and conformed
  without reading the three existing ones. Depends on the test kit above to be more than prose.
