# Reading a description: `TsonServiceDescription`, `TsonServiceCoverage`, externs and the wiring

The reader, the schema class and the coverage are in `io.ltr8.tson.service.http` (`tson-service`): `service.tn` is one
schema holding the http projection, so its reader is the `!service.http` reader.

What `TsonServiceDescription` checks before a description has a model, how it follows an extern to its landing, how a
server's bindings, catalog and warm-up derive from the description, how `TsonServiceCoverage` and the conformance test hold
a server to it, and how business errors compose `problem.details`. The layer it reads, `service.tn` and its protocol
level, is `design/service-description.md`; everything HTTP, the projection, its endpoints and `Route`, is
`design/http-projection.md`.

**Invariants**

- Naming is the resolver's (references, `type_ref`, `ref<…>`), a projection's claims included; relating is
  `TsonServiceDescription`'s, in one reader.
- A class in `io.ltr8.tson.service.protocol` or `io.ltr8.tson.service.http` binds only by being named in
  `TsonServiceSchema.bindings()`; a `service.http` entry whose value did not bind is a startup failure, since the resolver
  holds one with an unbound subtype in it as a tree.
- The description is the source of truth for what a server publishes (`referencedSchemas()`), warms
  (`boundClasses(bindings)`, `boundClasses()`) and speaks (`Route.mediaTypes()`). `TsonServiceCoverage.requireComplete()`
  holds the server to it, and `notServedHere` with a reason is the only way out.
- A claim names the declared verb and path template, never the path the framework registers.
- A business error composes `problem.details`, fixes its status, and is written by the handler, not thrown.
- A payload type named through an extern, or by a plain alias, is judged as the entry it lands on or reaches, and each
  type expression in it in the namespace of the landing that holds the entry writing it; a refusal names the type as
  written. `payloadTypes()` names an extern by its landing's schema and the entry's own name, what the binder is asked;
  a plain alias is named as written, while a TSON read asks for the entry it reaches. A binding by an extern's local
  name is refused.

## What the reader checks

**Naming is the resolver's; relating is the reader's.** `request`, `response` and `errors` are typed `type_ref`, and
every reference the layer holds is a `meta.ref<…>` (a binding's or an exemption's `exchange`, a name in `binds` or in
`security`), so a type nothing declares, a reference to the wrong kind of thing, and a key a protocol does not hold are
refused at schema load (`TsonServiceDescriptionTest.aBindsNamingNoProtocolIsRefusedAtLoad`). Each
reference arrives delivered its referent (`ValueRef<Protocol>`, `ValueRef<Exchange>`); the reader builds `ExchangeName`
from the name, whose first segment is the protocol's entry. **An unsound description has no model**, a startup failure
rather than a published contract no client can act on:

- a binding's or an exemption's exchange belongs to a protocol its projection binds. There is no inheritance between
  protocols, and a projection binding several names each;
- the `binds` claim holds: every exchange of every bound protocol is bound, or exempted in `not_bound` with a reason,
  held per `ExchangeName`, never per name, since two protocols may both declare `get_order`;
- no exchange is marked both `@service.safe` and `@service.destructive`;
- each business error fixes its status, and two errors of one endpoint pinning one status is refused (below);
- no class is bound by the local name of an extern a route names, where no schema of the closure owns the name
  (below);
- what is HTTP's, each request field placed exactly once, a bound dependent field's chosen types, the verb against the
  exchange's facts, a responseless endpoint's status, the media types, no two endpoints sharing a verb and a path, and
  each response header's template, is `design/http-projection.md`.

**The reader follows an extern** ([TSON-SCHEMA] §5.13), the way one description names two versions of one schema (`order_v1`,
`order_v2`), which an import of both cannot. An extern entry's body is an `Extern`, so every check that looks inside a
payload type reads its landing (`io.ltr8.tson.service.internal.Landed`): the error's pin, a response header's field, a
request's placement and chosen types are the foreign record's. Each type expression is resolved from the landing of the entry
that writes it, hop by hop, so an array of a third schema has its element type looked up where the array is declared. The
landing's namespace is enough even where its schema holds the entry by import: an import merges the declarer's whole
namespace transitively and refuses a redefinition ([TSON-SCHEMA] §2.2.3), so no lookup of the declaring schema is made
(`TsonServiceDescriptionExternTest`). A plain alias hop within a schema is followed the same way for the checks that judge a
shape (`Landed.pastAliases()`): a request, response or error that is a plain alias of a record is that record, and a field
typed by an alias of a record or array is no scalar (`TsonServiceDescriptionTest`). A route keeps the name written;
`payloadTypes()` and `payloadType(ref)` give the `PayloadType`, the declaring schema's `!!id` and the entry's own name, which
for an extern's landing and a plainly named entry is what the binder is asked (by `identity()`) and what the codec reads the
type under in either encoding. So a context binding `order_v1` binds nothing a read asks for, and is refused at startup
(`design/binding.md`). For a plain alias the name is the alias as written, and a TSON read asks for the entry the alias
reaches (`TsonServiceDescriptionTest.aPayloadTypeThatIsAPlainAliasIsNamedAsWritten`).

## Wiring, and what a server derives

**The wiring is two things**: this schema reachable from the `schemaSource` (`TsonServiceSchema.publishedById()`), and
`TsonServiceSchema.bindings()` in the config's one bind context beside the application's own, merged by
`TsonServiceSchema.bindingsWith`. The map is explicit: a class of either package binds only by being named there,
and a name both maps bind is refused, since within one closure a name means one thing. Map keys bind as `String`
whatever the key type, so `{http_verb => endpoint}` is `Map<String, Endpoint>`. A slot typed by an abstract record
needs a Java type of that name, a sealed interface (`Endpoint`, `SecurityScheme`). A subtype nothing binds, written at
that slot in a value entry, leaves the entry's whole value a tree rather than being refused (`UPSTREAM.md`, "A subtype
nothing binds, at an abstract base's slot in a value entry, leaves the value a tree"), so `TsonServiceDescription` refuses a
`service.http` entry whose value is not an `Http`, naming `bindingsWith`, through which a description's own endpoint
subtype binds; skipping it would serve the description with that projection's routes missing.

**What a server publishes and warms comes from the description**, not a document beside it:

- `referencedSchemas()`: itself, the layer, and its dependencies transitively, minus the bundled standard library. A
  dependency is an import or the schema an extern names (`TsonLinkedSchema.dependencies()`), which loads with the
  imports though none of its names enters the namespace. That is the catalog, so a referenced-but-unpublished schema is
  impossible, not merely tested for.
- `boundClasses(bindings)`: what to `prepareToWrite`, from every request, response and error the endpoints name, each
  looked up in a name-only map by its `PayloadType.name()`, the name a read asks for an extern's landing or a plainly
  named entry. `boundClasses()` asks the description's own `Tson` context instead, by the declaring schema and the
  name, so a context keyed by schema (`TsonBindings.bySchema`) warms two versions' `order` as two classes.
  Hand-listing lets a new response type go unwarmed, which costs only latency, so nothing reports it.

**`TsonServiceCoverage` holds a server to that contract at startup.** `serving(verb, path)` claims a declared endpoint and
hands back its `Route`, so the path and media types come from the description wherever the framework can take them; an
undeclared endpoint is refused where the handler is written, and `requireComplete()` fails startup on a declared one
left unclaimed rather than 404ing for a client that believed the contract.

**An endpoint has no name, so a claim names the description's verb and template string**, which keeps it
framework-agnostic: the JDK demo serves `/{+schemaPath}` from a `createContext("/")` prefix, Javalin needs `/<path>`
and Helidon `any()`. Comparing registered paths would need a per-framework translation table, where a wrong entry is a
route that silently never matches.

**"Everything declared is served" has a way out, and the reason is required.** `notServedHere(verb, path, reason)` makes
the exemption a greppable, justified decision where a boolean would be a hole; `not_bound` is the same rule one level
up. Future helpers take this shape: a *named position with a rationale*, never a flag meaning "be sloppy".

**The running server is held to it over HTTP** by `TsonServiceConformanceTest` (`design/http-projection.md`, "`Route`,
and the conformance test").

## Business errors compose `problem.details`

A business error (the request was schema-valid and the domain still said no) is **written by the handler, not
thrown**. It composes `problem.details` (§5.8) so it carries RFC 9457's members and adds its own, which means the
error boundary cannot produce it: the boundary only renders a `problem.details`, and a `sku_not_found` has a field
`problem.details` does not.

**Each business error fixes its status**, `sku_not_found => problem.details & { status: = 404  sku: text }`, which is
how a client tells an error from the response by status alone; the reader refuses an error type an endpoint names that
does not fix it, and two errors of one endpoint pinning one status. `Route.errorFor(status)` is the reverse lookup. A
transport's own refusals, a body failing its schema or an Accept nothing satisfies, are not listed: every exchange has
them, and the error boundary answers each with a `problem.details`.

These types belong in the **application's** schema, importing `problem.tn`: `tson-http` owns the transport envelope,
the service owns "SKU not found".

- **Imports are transitive, but name what you use anyway.** `order-1.tn` would get `text` through `problem.tn`; name
  `core.tn` too. A collision is judged by the *declaring schema's identity*, so naming a dependency twice is no error.
- **`errors` stays data-level.** `problem.details` declares it optional, and a business failure omits it and carries
  its own fields; the boundary writes it on every problem it renders. They never co-occur: validation is a gate. A
  bound business error still has an `errors` component, since a class bound for reading has one per field: the demos'
  `SkuNotFound` types it `Optional<List<TsonProblemDiagnostic>>` and leaves it empty, so the body omits it, and
  `TsonProblem` reads an absent one as an empty list.
