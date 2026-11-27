# The http projection (`http.projection`, endpoints, `Route`)

In `tson-http`, declared by `http.tn` (one root map, `http`), which imports `service.tn`. How a protocol
is projected onto HTTP: the projection and its claims, endpoints and their kinds, placement, statuses, the verb checks,
media types, security schemes, response headers, and the `Route` a server is handed. The protocol layer is
`design/service-description.md`, whose example shows both schemas; the resolver-versus-reader split, the wiring,
`TsonServiceCoverage` and business errors are `design/service-reader.md`.

**Invariants**

- `http.projection` is a record whose one required field is the plain map `resources`; its claims, `binds` and
  `not_bound`, are fields beside it, references the resolver judges at load and delivers their referents.
  `TsonServiceDescription` finds every projection by what the entry is, a value of `http.projection` bound to
  `HttpProjection`, never by its name.
- `http.resource`, `service.protocol` and `http.projection` each have the positional form, one bare required field;
  adding a second withdraws it and breaks every document written in it. A document writes a protocol and a projection
  in full.
- `http.endpoint` and `http.security_scheme` are `abstract`: an untagged endpoint, and a value tagged with either
  base, is refused by the resolver naming the subtypes. The tag selects `operation`, `binding` or `document`. A
  projection whose value did not bind is a startup failure (`design/service-reader.md`, "Wiring").
- A path key is an `http.path_template`, an RFC 6570 template at levels 1 and 2 and a text family whose `pattern`
  is ASCII-exact and admits `.`; it is no name, so no name hygiene reaches it and a key is held as written. No
  namespace could hold it, and nothing references a resource or an endpoint.
- Each request record's fields are placed exactly once over the path, the query, the headers and the body; a field in
  the path, the query or a header is a scalar. A request or response named through an extern is the foreign record it
  lands on, and a plain alias of a record is that record; each field's type is resolved from the landing of the entry
  that writes it, at every hop, and an alias of a record or array is no scalar.
- The verb agrees with the exchange's facts: GET safe, PUT and DELETE idempotent, nothing `@service.destructive` at GET
  or HEAD. A verb never sets a fact.
- An operation or binding with no response type writes `status: 204`, `205` or `304`; the default 200 is for an
  endpoint with a response.
- A media type is listed, never implied, except `application/tson` on every endpoint but a `!http.document`; a
  document listing none, and a non-document listing a type no codec here speaks, are startup failures.
- An endpoint's `security`, when written, replaces its projection's, and `security: []` is open; absent inherits, and
  both absent is open. The bound records keep absent (`Optional.empty()`) apart from empty, and `Route.security()` is
  the resolved list. Nothing enforces a scheme.
- A response header's template variable names a scalar field of the endpoint's response type, checked at startup; an
  endpoint with no response type, a document among them, declares literals only.
- A header name and an authentication scheme fold with `ASCII_CASEFOLD`; an api key carried in a header is folded the
  same way by `ApiKey` and held to the token grammar by the reader.
- No two endpoints share a verb and a path, across every projection a description holds.

## A projection, its resources and its endpoints

`http.projection` is `{ binds?, not_bound?, security?, resources }`, a record, so a fact about the projection as a whole is a
field beside its map, as a protocol's is beside its namespace (`design/service-description.md`).
`binds?: [meta.ref<service.protocol>]` names the protocols it binds, and is a claim the reader holds it to;
`not_bound?: [http.exemption]` gives each exchange it does not bind with a required `reason`, written
`not_bound: [{ exchange: orders_v2.exchanges.refund  reason: "…" }]`. An exemption is a list of records rather than a
map keyed by exchange, since a map key is a value of the map's own key family, never a reference. How the claim is
checked is `design/service-reader.md`, "What the reader checks". A projection with no `binds` is a web service on
its own terms, its endpoints `!http.operation`s. `binds` takes the word `!http.binding` and `not_bound` use.
`http` names the transport, as `grpc` would; it is reserved only against a root that is no schema map, as `service` is.

`resources` is an `http.resources`, `{http.path_template => http.resource}`, a plain map whose values are
`http.resource`, `{ endpoints: {http.verb => http.endpoint} }`; `HttpProjection` binds it to an `AnnotatedMap`,
which keeps the written order routes are read in. `http.verb` is RFC 9110's request methods as a closed enum, called
verbs so that `method` names nothing in the layer.

**A path is not a name.** It is where the schema stops and HTTP begins: a server matches it against a request's
target, and nothing in a description references it, so `resources` is a plain map rather than a namespace and its key
a text family with a `pattern`, as `http.header_name` is. A namespace could not hold these paths in any case: its
`key_type` may not admit `.` ([TSON-SCHEMA] §4.2, §5.12), and a path holds one (`/.well-known/tson-deployment`, a
schema's identity path). A map that is no namespace is not entered by a reference ([TSON-SCHEMA] §5.12). The
`pattern` is ASCII-exact: `/`, then any run of RFC 3986's unreserved characters `[A-Za-z0-9._~-]`, its
sub-delimiters `[!$&'()*+,;=]`, `[:@/]`, a percent-encoded octet `%[0-9A-Fa-f]{2}` and an expression
`\{[+]?[A-Za-z0-9_]+\}`. Anything else, a bare `%`, `{` or `}` and every non-ASCII character included, is refused at
the key, so percent-encoding is the one way a path carries another character. No normalization is stated, so a key is
held as written, and no name hygiene applies (`UpstreamGapsTest.anHttpPathKeyIsJudgedByItsPatternAlone`).
`TsonHttpSchemaTest.aPathIsAUriTemplateCheckedAtSchemaLoad` lists what the pattern admits and refuses.

**An endpoint is one of three kinds, and its tag says which.** `http.endpoint` is the `abstract` base, placement
plus `status`, `media_types`, `security` and `response_headers`, bound to the sealed interface `Endpoint`. A value at an
endpoint slot is tagged with its subtype and admitted as that subtype ([TSON-SCHEMA] §7.2); the tag is read before
anything else, and is the whole difference between the one-declaration and the two-declaration forms:

- `!http.operation` is `http.endpoint & service.signature`, its signature inline, for a projection with no
  protocol behind it;
- `!http.binding` adds `exchange: meta.ref<service.exchange>`, its signature an exchange's, whose qualified name
  says which protocol declares it, so no binding is ambiguous between two protocols declaring one name;
- `!http.document` is an endpoint whose body is not a TSON value (below). It restates `request?` and `errors?`
  rather than composing `service.signature`, since a document has no response type to judge.

**A binding refers to an exchange rather than being one.** An exchange could be a type under plain `meta.tn`, with an
operation refining it, but a schema fact stated as a field is per-instance: `verb: = POST` pins an unmarked field, which
[TSON-SCHEMA] §5.2 makes a *marker* each value must write (`TsonServiceDescriptionTest`).

**A path is a URI template**: RFC 6570 levels 1 and 2, absolute, checked at schema load. The demos declare
`{+schemaPath}`, the reserved expansion an identity path with slashes needs, where `{schemaPath}` would percent-encode
them. The fragment operator and levels 3 and 4 describe expansion rather than a route a server can match, and are not
admitted. A variable is RFC 6570's varname, ASCII letters, digits and `_`, so a field named `order-id` cannot be bound
from the path.

## Placement

`http.placement` is the bridge from HTTP's constructs to the exchange's request record, and the place where a
protocol and the projection over it may look different. The reader checks it (`RequestPlacement`):

- each request record's fields are placed exactly once over the path, the query, the headers and the body: a
  `{segment}` binds the field of that name, `query` and `headers` name fields, `body` names one field carried
  unwrapped, and the remainder is the body, or the query for a GET or HEAD or when `body` named a field;
- a field in the path, query or a header is a scalar, an array of scalars in a query or header, which may repeat;
- a dependent field (`payload: kind!`, a `TypeInline` holding a `DependentBody` in `RecordField.type()`, its sibling's
  name in `RecordField.sibling()`) travels in the remainder body with its sibling, the one place a record instance holds
  both;
- a bound request record holding a dependent field loads only if every type a sender may name in its sibling binds
  (`ChosenTypes`): a free `meta.type_ref` sibling admits the kernel's records and never does, while a pinned sibling or
  a discriminator over bound subtypes does.

**A request named through an extern is placed as the foreign record it lands on**: its fields are that record's, and
each field's type is looked up where its declaration wrote it, so `id: span` in `keys-1.tn` is a record in the path
though the description declares no `span`. At every hop, so an array declared in a third schema, or held by import, has
its element type looked up there; a map is refused (`TsonServiceDescriptionExternTest`).

A plain alias is followed past its hops before a shape is judged: a request that is a plain alias of a record is placed
as that record (`TsonServiceDescriptionTest.aRequestThatIsAPlainAliasOfARecordIsPlacedAsTheRecord`), a field typed by an
alias of a container is refused in the path, and an array of an alias is judged by what it reaches. A refusal names the
type as written.

**There is no parameter list.** A parameter-only endpoint declares a one-field record (`schema_ref => { schemaPath:
text }`) placed from the path. An endpoint with no request admits no placement, so a `{segment}` on it is refused.

## Status

`status` is the success response's status; there is no list of responses, since an exchange has one response type.
The default 200 is for an endpoint with a response: an operation or binding with no response type writes a status that
carries no body, 204, 205 or 304 (`TsonServiceDescriptionTest.aResponselessEndpointCannotAnswer200`). Each business error
fixes its own status (`design/service-reader.md`, "Business errors").

## Verb checks

The verb agrees with what the exchange says about itself, the markers on its key (`design/service-description.md`,
"An exchange's facts"): GET is safe, PUT and DELETE idempotent, and nothing `@service.destructive` sits at GET or
HEAD, as nothing unsafe does. The check runs one way: no verb implies a fact. `Route.safe()`, `idempotent()` (safe
implies it) and `destructive()` carry the three resolved, from the exchange's key for a binding and the verb key for
an operation or a document.

## Media types

`http.endpoint` carries `media_types?: [media_type]`, net.tn's `media_type`. **Every endpoint but a
`!http.document` speaks `application/tson`**, and the list says what it speaks beside it; absent is
`application/tson` alone. **A label is listed, never implied**: an endpoint answering JSON lists `application/json`,
`application/tson+json` or both, and Accept chooses among what is listed. Media types rather than an enum of encodings:
HTTP already negotiates them, so a new encoding is a registered name and never an edit to the layer. One set serves both
directions, as the codec does; a split between what an endpoint accepts and what it produces is a later field.

`Route.mediaTypes()` is the resolved set, folded to lower case: `application/tson` first for a non-document, then what
is listed. `Route.speaksJson()` is membership of either JSON label, and a demo builds the route's codec from it
(`codec.acceptingJson()`), keeping the description the one statement.
`TsonServiceConformanceTest.everyEndpointSpeaksTheMediaTypesItLists` holds the server to it, each listed JSON label sent
and asked for, and JSON refused where none is listed.

**The closed world is checked at startup, the shape of `prepareToRead`** (`design/binding.md`): `TsonServiceDescription`
refuses a media type no codec it serves can speak, so a description naming an encoding the server lacks fails to start
and never answers 500. "Can speak" is `application/tson`, `application/json` and `application/tson+json` for an endpoint
writing a TSON value; a document's body is bytes, so any type labels it. And it refuses a `!http.document` listing
none: `application/tson` is not implied for a document, and the base record declares the field optional, so only the
reader can require it.

**An endpoint whose body is not a TSON value is a `!http.document`**: the schema route (schema documents served as
published bytes, `application/tson`) and the validator's page (`text/html`). Its media types are the whole contract of
its body; it may still take a request record to place and name business errors, and its success is labelled, not
judged.

## Security schemes

A description declares a scheme as a **value entry at its root**, tagged with its subtype, and names it by reference:
`bearer => !http.auth { scheme: Bearer }`, `partner_key => !http.api_key { in: header  name: "X-Api-Key" }`.
The base `http.security_scheme` is an empty `abstract` record, bound to the sealed interface `SecurityScheme`, so a
value tagged with the base is refused by the resolver naming the subtypes, as an untagged endpoint is. An `http_auth`
names an RFC 9110 §11 scheme, an `http.auth_scheme` (`bearer`, folded); `api_key_in` is an enum whose Java constants
are lower case, since an enum binds by name. An `api_key`'s `name` is `text`, a query parameter's name being no header
name; one carried in a header is folded as a header name by `ApiKey`'s constructor (`x-api-key`) and refused at
startup unless it is an RFC 9110 token (`TsonServiceDescriptionTest.aHeaderBorneApiKeyIsHeldAsAHeaderName`).

`security?: [meta.ref<http.security_scheme>]` sits on `http.projection`, the default, and on `http.endpoint`. The list
is **alternatives**: a credential for any one scheme will do. The resolver judges every name at load, so a type or a
protocol there is refused (`TsonServiceDescriptionTest.aSecurityNamingNoSchemeIsRefusedAtLoad`), and each reference arrives
delivered its `HttpAuth` or `ApiKey`.

**The override rule**: an endpoint's `security`, when written, replaces the projection's whole; `security: []` says the
endpoint is open; absent inherits; both absent is open. So an endpoint record types the field `Optional<List<ValueRef<
SecurityScheme>>>`, which the binder delivers `Optional.empty()` when absent and a present empty list for `[]`
(`anAbsentSecurityAndAnEmptyOneArriveApart`). `Route.security()` is the resolved list. **Stated, not enforced**: nothing
here checks a credential; enforcement at the error boundary is backlog, its status `TsonHttpException.from`'s.

## Response headers

`response_headers?: {http.header_name => text}` on `http.endpoint` maps a header the **success** response carries
to an RFC 6570 level-1 template over the endpoint's response type's fields, `Location => "/orders/{id}"`, or to a literal,
`Cache-Control => "max-age=3600"`. Names are folded, as a request's `headers` are; a binding's response type is its
exchange's.

**The template is checked at startup**, as a request's placement is: each variable names a field of the response type
that is one scalar, the rule for a path segment; a form beyond level 1 is refused; and an endpoint with no response type,
a document or one answering no body, declares literals only. A response named through an extern, or a plain alias of
a record, is judged as the record it reaches; an array is refused whatever its element type, and so is a field typed by
an alias of a container (`TsonServiceDescriptionExternTest`,
`TsonServiceDescriptionTest.aResponseHeaderExpandsOverARecordReachedThroughAPlainAlias`). `Route.responseHeaders()` holds
the templates, and `expandResponseHeaders(fields)` expands them through `HeaderTemplate` from a function of field name
to text that the server writes from its own reply, since reading a field of an arbitrary bound record would need
reflection or the tree reader.

The demos' schema route declares `Cache-Control => "public, max-age=31536000, immutable"`, the
`TsonSchemaCatalog.IMMUTABLE` every schema handler already sends (§3.5: a published schema never changes). Each
`OrderServer` sets a route's declared headers on a success only, so a schema 404 is never cached. The demo's `order` has
no identifier, so nothing declares a `Location`.

## Header names and authentication schemes fold with `ASCII_CASEFOLD`

`http.header_name` and `http.auth_scheme` are text families carrying RFC 9110's token `pattern` and
`normalization: ASCII_CASEFOLD`, since RFC 9110 compares both without case (§5.1, §11.1); `Idempotency-Key` is held as
`idempotency-key`, the form HTTP/2 and HTTP/3 put on the wire. [TSON-SCHEMA] §5.5 judges the `pattern` against the
folded value, so a fold reaching beyond ASCII would widen it: `NFKC_CASEFOLD` would bring a fullwidth letter or a long
s into the token grammar, and `ASCII_CASEFOLD` leaves it outside, refused (`design/schema-authoring.md`). A header name
keys a map field rather than a namespace, so it is a text family, not an identifier family, as a path is.

## The positional form, and what it costs

`http.resource`, `service.protocol` and `http.projection` each have exactly one bare required field, a map or a
namespace, so §5.6 lets the map stand in for it: `"/orders" => { POST => … }` for `{ endpoints: { POST => … } }`. The
documents use it for a resource and write a protocol and a projection in full: a reference into a protocol spells
`exchanges` whether the document wrote it or not, and a projection writing `binds` cannot use the form, a brace holding
fields or keys and never both. A second required field on any of the three withdraws the positional form, and every
document written in it stops parsing, so a new field there is optional or a new type.

**A projection is a value of its own, never a parametric record's application.** An endpoint is no entry, so the one
thing a parametric record could hold is a whole `!http.projection` value, and its body is a record body ([TSON-SCHEMA]
§5.10):
`fetching => <T> !http.projection { resources: { "/orders/{id}" => { … response: T … } } }` is refused at its declaration,
the diagnostic naming the remedy, one value per instantiation (`UpstreamGapsTest.aParametricRecordOverAnHttpValueIsRefused`).

## `Route`, and the conformance test

`Route` is one endpoint resolved: its verb and path, the exchange a binding borrows, its request, response and
business errors by status (`errorFor(status)`), its success status, the three exchange facts, whether it is a
document, its media types, its placement, its documentation (`summary()`, `doc()`, the verb key's, else the
exchange's), its security in force and its response headers. `TsonServiceCoverage.serving` hands it to the handler
(`design/service-reader.md`, "Wiring").

`TsonServiceConformanceTest` resolves the description through a schema source fetching from the running server, proving
that every referenced schema is published and every payload type exists, then holds the server to what no compiler can
know: a reply at the success status carries the response type (a document, a media type it lists) and every literal
response header the endpoint declares, with the value declared; one at a business error's fixed status that error; and
any other is a 4xx or 5xx `problem.details`.

**Deliberately absent**, each a decision: links, callbacks, examples, tags, servers, more than one success response,
custom and WebDAV verbs (`http.verb` is a closed enum), and a declaration of the transport's own refusals, which
every endpoint has and the error boundary answers with a `problem.details`. Adding any is an edit in place until this is
published, and a new identity after; a new kind of endpoint is a new record composing `http.endpoint`.
