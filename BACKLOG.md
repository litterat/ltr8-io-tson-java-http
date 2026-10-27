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

- [ ] **A startup facade in `tson-http`** — Each demo's `start` repeats the same page of ceremony, in an order that
  matters: the schema map, `TsonBindings.of`, `TsonApiSchema.metaNameBinder()`, resolving the application schema and
  then the description, `TsonApiSchema.describedBy`, `prepareToWrite` over `boundClasses`, `TsonApiCoverage.of`. One
  builder taking the description source, the application schemas and the bindings, and handing back the codec, the
  description, the coverage and the catalog, makes the startup rule (`CLAUDE.md`, "The startup rule") impossible to get
  wrong and reduces an adapter demo to its handlers. The catalog helper and the resource-loading helper each demo
  carries belong in it.
- [ ] **Read a request by its route** — A handler chooses between `readObject` and `readObjectAs(schema, type)` by
  sniffing `Content-Type` for JSON, with the route's declared request type as the JSON binding. The route already knows
  its request type and its encodings, so an exchange-level `read(route, Class)` makes that one call and removes the
  branch from every handler. The same for the response: `respondDescribed` takes a status, a schema identity and a type
  name the route already holds.
- [ ] **Register routes from the description** — A handler claims a declared endpoint with
  `TsonApiCoverage.serving(verb, path)` and registers its own path. The adapter should instead take a map from
  `InterfaceMethod` (or verb and template) to handler and register every declared endpoint itself, so coverage is
  structural rather than claimed and `requireComplete()` has nothing left to catch. Constraint: the per-framework path
  translation that claim-by-template avoids (`design/service-description.md`, "An endpoint has no name") becomes the
  adapter's job, once, where a wrong entry is a route that silently never matches; each adapter's suite must prove every
  declared template is reachable.

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

- [ ] **A client built from the description** — A client that reads a description, builds a request from a request
  record by reversing `RequestPlacement` (path variables, query, headers, body), sends it with the right `Content-Type`
  and `TSON-Accept-Schema`, and validates the reply by the schema it names is the other half of the contract, which is
  proven from the server side only. `TsonApiConformanceTest` holds that logic against the demo; extract it into a
  reusable client and make the conformance test a consumer of it.

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
