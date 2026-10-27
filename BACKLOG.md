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
  carry the spec revision it is built against (`tson.version` in `gradle.properties`, every identity under `/2026/38/`),
  since the revision series changes without compatibility guarantees. Constraint: signing and a release decision in the
  sibling, recorded in `build.gradle.kts`.
- [ ] **Dereferenceable meta identities** — `meta-service.tn`, `problem.tn` and `deployment.tn` carry `!!id`s under
  `https://tson.io/2026/38/io/ltr8/http/` that nothing serves. Publish them at their identity so a `!!meta` or
  `!!import` naming them resolves over HTTP without a bundled copy. Constraint: `design/schema-authoring.md` says a
  schema is edited in place until it is published; publishing freezes it under [TSON-JSON] §3.5.

## Integration surface

- [ ] **A startup facade in `tson-http`** — Each demo's `start` repeats the same page of ceremony, in an order that matters:
  the schema map, `TsonBindings.of`, `TsonApiSchema.metaNameBinder()`, resolving the application schema and then the
  description, `TsonApiSchema.describedBy`, `prepareToWrite` over `boundClasses`, `TsonApiCoverage.of`. One builder taking
  the description source, the application schemas and the bindings, and handing back the codec, the description, the coverage
  and the catalog, makes the startup rule (`CLAUDE.md`, "The startup rule") impossible to get wrong and reduces an adapter
  demo to its handlers. The facade spans the split ("The module split", below): the service side (schemas, bindings, the
  description, the join) and the HTTP side (the codec, the catalog), so a projection other than HTTP reuses the first half.
  The catalog helper and the resource-loading helper each demo carries belong in it. The facade must not require an
  `!interface`; an `!api` of operations alone is complete.
- [ ] **Read a request by its route** — A handler chooses between `readObject` and `readObjectAs(schema, type)` by
  sniffing `Content-Type` for JSON, with the route's declared request type as the JSON binding. The route already knows
  its request type and its encodings, so an exchange-level `read(route, Class)` makes that one call and removes the
  branch from every handler. The same for the response: `respondDescribed` takes a status, a schema identity and a type
  name the route already holds.
- [ ] **Register routes from the description** — A handler claims a declared endpoint with `TsonApiCoverage.serving(verb,
  path)` and registers its own path. The adapter should instead register every declared endpoint itself, so coverage is
  structural rather than claimed and `requireComplete()` has nothing left to catch. A handler attaches two ways: directly by
  verb and template for an `!operation`, and through the service's join ("The module split", below) for a `!binding`, so a
  server with no `!interface` is a first-class case. Constraint: the per-framework path translation that claim-by-template
  avoids (`design/service-description.md`, "An endpoint has no name") becomes the adapter's job, once, where a wrong entry is
  a route that silently never matches; each adapter's suite must prove every declared template is reachable.

## The module split

The four entries are in build order.

- [ ] **`tson-invoke`, the language side** — A new Gradle sub-project and JPMS module (`io.ltr8.tson.invoke`) depending on
  Java alone, as `ltr8-bind` in the sibling depends on nothing but the language. It holds a descriptor for a Java interface
  built by analysis: one method descriptor per Java method with its parameter and return classes, an invoker `MethodHandle`
  taking a target and a request and returning the outcome, and a `java.lang.reflect.Proxy` for the other direction, turning a
  Java call into a request and the outcome back. It holds `Class` and `MethodHandle` and nothing else, knows no schema and so
  checks nothing: the check is a join, and the join is the layer above. Constraints: dispatch through `java.lang.invoke`,
  never reflective invoke; Java 25, a real `module-info.java` named for its root package, no external runtime dependency. The
  name is `invoke` because `interface` is a Java keyword and a module is named for its package.
- [ ] **`tson-service`, the description** — A new sub-project (`io.ltr8.tson.service`) depending on `tson-invoke` and
  tson-java. `meta-service.tn` moves here whole, so `!interface` and `!api` stay one layer and no reference crosses a meta
  import. So does the `io.ltr8.tson.http.api` package (`TsonApiSchema`, `TsonApiDescription`, `TsonApiCoverage`, `Route`,
  `InterfaceMethod`, the bound records), with `OrdersExamples`, its fixtures and their tests, and `TsonBindings`, which binds
  schema types to classes with startup semantics and is not HTTP's. The join lives here: a resolved `!interface` held against
  an invoke descriptor at startup, by naming convention (`place_order` to `placeOrder`, as `TsonApiSchema.metaNameBinder()`
  maps type names). Startup fails on a Java method with no schema method, a schema method with no Java method, a parameter or
  return bound to a record other than the method's request or response, or an outcome that is not the response or a declared
  error, as `prepareToRead` fails a bound-class mismatch. The outcome is a union of the response and the errors, so whether a
  projection throws or writes an error is the projection's choice and the interface layer makes none. The other direction
  lives here too: an `!interface` document written from an invoke descriptor, so an `!api` can be built over an interface or
  object that already exists; `@safe` and `@idempotent` have no Java counterpart and must come from somewhere the writer
  reads. The rpc wire form (`call` and `return` documents addressed by `InterfaceMethod`, and the code taking a call to an
  invocation and the outcome to a return) is a capability of the service, not a module of its own; `experiments/rpc/` is its
  draft. One HTTP fact stays in this reader on purpose: a business error's pinned `status`, read by field name from the error
  type, is the `!api` projection's rule and needs no import of `problem.tn`. Constraint: this layer resembles the sibling's
  `tson-schema`, a meta layer plus a reader over what resolves, and may one day move there in part or whole; so it takes no
  dependency on `tson-http`, mirrors `tson-schema`'s shapes where one fits, and keeps its vocabulary free of anything HTTP
  beyond the `!api` projection itself.
- [ ] **`tson-http` becomes the HTTP projection** — What remains is the codec, the media types and headers,
  `TsonHttpException`, `problem.tn`, the catalog, the deployment descriptor, and the code taking an HTTP call to a route and
  through the service to an invocation. It depends on `tson-service`; the three adapters are unchanged above it. One new
  endpoint kind in `meta-service.tn`: a record composing `endpoint` that names an interface rather than a method, so one POST
  route accepts call documents for a whole interface. It is the simplest agent-facing surface beside REST, since an LLM tool
  call is a method name and arguments and nothing else. `UpstreamGapsTest` pins gaps for both layers; it stays in
  `tson-http`, which sees everything, until a pin concerns `tson-service` alone.
- [ ] **A gRPC projection as a peer of `tson-http`** — Its own module depending on `tson-service` alone, so neither
  projection knows the other exists. gRPC's marshaller is pluggable per method, so a `ServerServiceDefinition` is built at
  startup from the `!interface` with no protobuf: one `MethodDescriptor` per `InterfaceMethod`, its full name in gRPC's
  `package.Service/Method` form, and the service's wire serialisation for the request and outcome. The first external
  dependency on the service side, stopping at that module.

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

## Client

- [ ] **A client built from the description** — For an agent or any LLM-driven caller the client surface is REST, which is
  the published description, the self-describing responses and the deployment descriptor; a Java client built from the
  description is therefore a conformance and testing tool. It reads a description, reverses `RequestPlacement` to build a
  request from a record (path variables, query, headers, body), sends it with the right `Content-Type` and
  `TSON-Accept-Schema`, and validates the reply by the schema it names, which is the other half of the contract, proven from
  the server side only. `TsonApiConformanceTest` holds that logic against the demo; extract it into a reusable client and
  make the conformance test its consumer.
- [ ] **A tool manifest derived from an `!api`** — An LLM tool or function definition per endpoint, derived from the
  description's routes, request records and summaries, as a projection of the description like the gRPC front-end is of the
  interface. Constraint: `experiments/agent/` is future work the layer is not built toward (`design/service-description.md`),
  and this item derives from the description only; it decides nothing for the agent.

## The description

- [ ] **A security scheme** — `design/service-description.md` lists `security` as the one deliberate absence that would
  disqualify the layer for a real service. Even a declared scheme name per endpoint or per api, with the reader checking
  it names a declared scheme, closes it. Constraint: an edit in place until `meta-service.tn` is published, a new
  identity after; and `resource` keeps its one bare required field.
- [ ] **Response headers an endpoint sets** — A `Location` on a 201, a cache directive on a safe GET: declared on the
  endpoint the way `headers` places request fields, so a conformance test can hold the server to them. Same constraints
  as the security scheme.

## Documentation

- [ ] **A consumer guide per adapter** — The `design/` notes and the module `CLAUDE.md`s are for whoever maintains this;
  a consumer needs a short path from an empty project to a TSON endpoint on each framework, the headers and problem
  types in one place (`README.md`'s problem-type table is the start), and published Javadoc. `ReadmeTest` executes the
  README against the demo; a per-framework guide should be executed the same way.
- [ ] **How to write an adapter** — One page stating the error boundary, the committed-response rule, the negotiation
  contract and which suites to run, so an adapter for a framework this repo does not ship can be written and conformed
  without reading the three existing ones. Depends on the test kit above to be more than prose.
