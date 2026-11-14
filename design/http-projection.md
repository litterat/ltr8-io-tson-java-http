# The http projection (`service.http`, endpoints, `Route`)

How a protocol is projected onto HTTP: the projection and its claims, endpoints and their kinds, placement, statuses,
the verb checks, media types, security schemes, response headers, and the resolved `Route` a server is handed. The
protocol layer, the one root map, the resolver-versus-reader split, the wiring, `TsonApiCoverage` and business errors
are `design/service-description.md`; its example shows a protocol and a projection binding it.

**Invariants**

- `service.http` is a record whose one required field is the namespace `resources`; its claims, `binds` and
  `not_bound`, are fields beside it, references the resolver judges at load and delivers their referents.
  `TsonApiDescription` finds every projection by what the entry is, a value of `service.http` bound to `Http`, never by
  its name.
- `service.resource`, `service.protocol` and `service.http` each have the positional form, one bare required field;
  adding a second withdraws it and breaks every document written in it. A document writes a protocol and a projection
  in full.
- `service.endpoint` and `service.security_scheme` are `abstract`: an untagged endpoint, and a value tagged with either
  base, is refused by the resolver naming the subtypes. The tag selects `operation`, `binding` or `document`. A
  projection whose value did not bind is a startup failure (`design/service-description.md`, "Wiring").
- A path key is a `service.path_template`, an RFC 6570 template at levels 1 and 2 and an `identifier_type`, so §8.2
  judges it as a name. No qualified name walks a path key, and nothing references an endpoint.
- Each request record's fields are placed exactly once over the path, the query, the headers and the body; a field in
  the path, the query or a header is a scalar.
- The verb agrees with the exchange's facts: GET safe, PUT and DELETE idempotent, nothing `@service.destructive` at GET
  or HEAD. A verb never sets a fact.
- An operation or binding with no response type writes `status: 204`, `205` or `304`; the default 200 is for an
  endpoint with a response.
- A media type is listed, never implied, except `application/tson` on every endpoint but a `!service.document`; a
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

`service.http` is `{ binds?, not_bound?, security?, resources }`. A namespace type declares its facets and nothing else,
so a fact about the projection as a whole is a field beside its namespace, as a protocol's is
(`design/service-description.md`). `binds?: [meta.ref<service.protocol>]` names the protocols it binds, and is a claim
the reader holds it to; `not_bound?: [service.exemption]` gives each exchange it does not bind with a required
`reason`, written `not_bound: [{ exchange: orders_v2.exchanges.refund  reason: "…" }]`. An exemption is a list of
records rather than a map keyed by exchange, since a map key is a name in the map's own key family, never a reference.
How the claim is checked is `design/service-description.md`, "What the reader checks". A projection with no `binds` is
a web service on its own terms, its endpoints `!service.operation`s. `binds` takes the word `!service.binding` and
`not_bound` use. `http` is named for the transport, as a gRPC projection of the same protocol would be.

`resources` is a `service.resources`, a namespace keyed by `service.path_template` whose values are `service.resource`,
`{ endpoints: {service.http_verb => service.endpoint} }`. `http_verb` is RFC 9110's request methods as a closed enum,
called verbs so that `method` names nothing in the layer. A path key is a name: an `identifier_type` with `start: NONE`,
`start_add: "/"` and RFC 3986's path characters plus a template's braces and `+` in `continue_add`, and a `pattern`
holding the structure the character set cannot (braces paired around one variable, `%` and two hex digits). A
qualified-name segment is an identifier and a path key starts with `/`, so no qualified name walks a path key; an
endpoint is reached by location.

**An endpoint is one of three kinds, and its tag says which.** `service.endpoint` is the `abstract` base, placement
plus `status`, `media_types`, `security` and `response_headers`, bound to the sealed interface `Endpoint`. A value at an
endpoint slot is tagged with its subtype and admitted as that subtype ([TSON-SCHEMA] §7.2); the tag is read before
anything else, and is the whole difference between the one-declaration and the two-declaration forms:

- `!service.operation` is `service.endpoint & service.signature`, its signature inline, for a projection with no
  protocol behind it;
- `!service.binding` adds `exchange: meta.ref<service.exchange>`, its signature an exchange's, whose qualified name
  says which protocol declares it, so no binding is ambiguous between two protocols declaring one name;
- `!service.document` is an endpoint whose body is not a TSON value (below). It restates `request?` and `errors?`
  rather than composing `service.signature`, since a document has no response type to judge.

**A binding refers to an exchange rather than being one.** An exchange could be a type under plain `meta.tn`, with an
operation refining it, but a schema fact stated as a field is per-instance: `verb: = POST` pins an unmarked field, which
[TSON-SCHEMA] §5.2 makes a *marker* each value must write (`TsonApiDescriptionTest`, the comparison kept at its end).

**A path is a URI template**: RFC 6570 levels 1 and 2, absolute, checked at schema load. The demos declare
`{+schemaPath}`, the reserved expansion an identity path with slashes needs, where `{schemaPath}` would percent-encode
them. The fragment operator and levels 3 and 4 describe expansion rather than a route a server can match, and are not
admitted. A variable is RFC 6570's varname, so a field named `order-id` cannot be bound from the path.

## Placement

`service.placement` is the bridge from HTTP's constructs to the exchange's request record, and the place where a
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

**There is no parameter list.** A parameter-only endpoint declares a one-field record (`schema_ref => { schemaPath:
text }`) placed from the path. An endpoint with no request admits no placement, so a `{segment}` on it is refused.

## Status

`status` is the success response's status; there is no list of responses, since an exchange has one response type.
The default 200 is for an endpoint with a response: an operation or binding with no response type writes a status that
carries no body, 204, 205 or 304 (`TsonApiDescriptionTest.aResponselessEndpointCannotAnswer200`). Each business error
fixes its own status (`design/service-description.md`, "Business errors").

## Verb checks

The verb agrees with what the exchange says about itself, the markers on its key (`design/service-description.md`,
"An exchange's facts"): GET is safe, PUT and DELETE idempotent, and nothing `@service.destructive` sits at GET or
HEAD, as nothing unsafe does. The check runs one way: no verb implies a fact. `Route.safe()`, `idempotent()` (safe
implies it) and `destructive()` carry the three resolved, from the exchange's key for a binding and the verb key for
an operation or a document.

## Media types

`service.endpoint` carries `media_types?: [media_type]`, net.tn's `media_type`. **Every endpoint but a
`!service.document` speaks `application/tson`**, and the list says what it speaks beside it; absent is
`application/tson` alone. **A label is listed, never implied**: an endpoint answering JSON lists `application/json`,
`application/tson+json` or both, and Accept chooses among what is listed. Media types rather than an enum of encodings:
HTTP already negotiates them, so a new encoding is a registered name and never an edit to the layer. One set serves both
directions, as the codec does; a split between what an endpoint accepts and what it produces is a later field.

`Route.mediaTypes()` is the resolved set, folded to lower case: `application/tson` first for a non-document, then what
is listed. `Route.speaksJson()` is membership of either JSON label, and a demo builds the route's codec from it
(`codec.acceptingJson()`), keeping the description the one statement.
`TsonApiConformanceTest.everyEndpointSpeaksTheMediaTypesItLists` holds the server to it, each listed JSON label sent
and asked for, and JSON refused where none is listed.

**The closed world is checked at startup, the shape of `prepareToRead`** (`design/binding.md`): `TsonApiDescription`
refuses a media type no codec it serves can speak, so a description naming an encoding the server lacks fails to start
and never answers 500. "Can speak" is `application/tson`, `application/json` and `application/tson+json` for an endpoint
writing a TSON value; a document's body is bytes, so any type labels it. And it refuses a `!service.document` listing
none: `application/tson` is not implied for a document, and the base record declares the field optional, so only the
reader can require it.

**An endpoint whose body is not a TSON value is a `!service.document`**: the schema route (schema documents served as
published bytes, `application/tson`) and the validator's page (`text/html`). Its media types are the whole contract of
its body; it may still take a request record to place and name business errors, and its success is labelled, not
judged.

## Security schemes

A description declares a scheme as a **value entry at its root**, tagged with its subtype, and names it by reference:
`bearer => !service.http_auth { scheme: Bearer }`, `partner_key => !service.api_key { in: header  name: "X-Api-Key" }`.
The base `service.security_scheme` is an empty `abstract` record, bound to the sealed interface `SecurityScheme`, so a
value tagged with the base is refused by the resolver naming the subtypes, as an untagged endpoint is. An `http_auth`
names an RFC 9110 §11 scheme, a `service.auth_scheme` (`bearer`, folded); `api_key_in` is an enum whose Java constants
are lower case, since an enum binds by name. An `api_key`'s `name` is `text`, a query parameter's name being no header
name; one carried in a header is folded as a header name by `ApiKey`'s constructor (`x-api-key`) and refused at
startup unless it is an RFC 9110 token (`TsonApiDescriptionTest.aHeaderBorneApiKeyIsHeldAsAHeaderName`).

`security?: [meta.ref<service.security_scheme>]` sits on `service.http`, the default, and on `service.endpoint`. The list
is **alternatives**: a credential for any one scheme will do. The resolver judges every name at load, so a type or a
protocol there is refused (`TsonApiDescriptionTest.aSecurityNamingNoSchemeIsRefusedAtLoad`), and each reference arrives
delivered its `HttpAuth` or `ApiKey`.

**The override rule**: an endpoint's `security`, when written, replaces the projection's whole; `security: []` says the
endpoint is open; absent inherits; both absent is open. So an endpoint record types the field `Optional<List<ValueRef<
SecurityScheme>>>`, which the binder delivers `Optional.empty()` when absent and a present empty list for `[]`
(`anAbsentSecurityAndAnEmptyOneArriveApart`). `Route.security()` is the resolved list. **Stated, not enforced**: nothing
here checks a credential; enforcement at the error boundary is backlog, its status `TsonHttpException.from`'s.

## Response headers

`response_headers?: {service.header_name => text}` on `service.endpoint` maps a header the **success** response carries
to an RFC 6570 level-1 template over the endpoint's response type's fields, `Location => "/orders/{id}"`, or to a literal,
`Cache-Control => "max-age=3600"`. Names are folded, as a request's `headers` are; a binding's response type is its
exchange's.

**The template is checked at startup**, as a request's placement is: each variable names a field of the response type
that is one scalar, the rule for a path segment; a form beyond level 1 is refused; and an endpoint with no response type,
a document or one answering no body, declares literals only. `Route.responseHeaders()` holds the templates, and
`expandResponseHeaders(fields)` expands them through `HeaderTemplate` from a function of field name to text that the
server writes from its own reply, since reading a field of an arbitrary bound record would need reflection or the tree
reader.

The demos' schema route declares `Cache-Control => "public, max-age=31536000, immutable"`, the
`TsonSchemaCatalog.IMMUTABLE` every schema handler already sends (§3.5: a published schema never changes). Each
`OrderServer` sets a route's declared headers on a success only, so a schema 404 is never cached. The demo's `order` has
no identifier, so nothing declares a `Location`.

## Header names and authentication schemes fold with `ASCII_CASEFOLD`

`service.header_name` and `service.auth_scheme` are text families carrying RFC 9110's token `pattern` and
`normalization: ASCII_CASEFOLD`, since RFC 9110 compares both without case (§5.1, §11.1); `Idempotency-Key` is held as
`idempotency-key`, the form HTTP/2 and HTTP/3 put on the wire. [TSON-SCHEMA] §5.5 judges the `pattern` against the
folded value, so a fold reaching beyond ASCII would widen it: `NFKC_CASEFOLD` would bring a fullwidth letter or a long
s into the token grammar, and `ASCII_CASEFOLD` leaves it outside, refused (`design/schema-authoring.md`). A header name
keys a map field rather than a namespace, so it is a text family, not an identifier family.

## The positional form, and what it costs

`service.resource`, `service.protocol` and `service.http` each have exactly one bare required field, so §5.6 lets the
map stand in for it: `"/orders" => { POST => … }` for `{ endpoints: { POST => … } }`. The documents use it for a
resource and write a protocol and a projection in full: a reference into a protocol spells `exchanges` whether the
document wrote it or not, and a projection writing `binds` cannot use the form, a brace holding fields or keys and never
both. A second required field on any of the three withdraws the positional form, and every document written in it stops
parsing, so a new field there is optional or a new type.

**A projection is a value of its own, never a template's application.** An endpoint is no entry, so the one thing a
template could hold is a whole `!service.http` value, and a template's body is a record body ([TSON-SCHEMA] §5.10):
`fetching => <T> !service.http { resources: { "/orders/{id}" => { … response: T … } } }` is refused at its declaration,
the diagnostic naming the remedy, one value per instantiation (`UpstreamGapsTest.aTemplateOverAnHttpValueIsRefused`).

## `Route`, and the conformance test

`Route` is one endpoint resolved: its verb and path, the exchange a binding borrows, its request, response and
business errors by status (`errorFor(status)`), its success status, the three exchange facts, whether it is a
document, its media types, its placement, its documentation (`summary()`, `doc()`, the verb key's, else the
exchange's), its security in force and its response headers. `TsonApiCoverage.serving` hands it to the handler
(`design/service-description.md`, "Wiring").

`TsonApiConformanceTest` resolves the description through a schema source fetching from the running server, proving
that every referenced schema is published and every payload type exists, then holds the server to what no compiler can
know: a reply at the success status carries the response type (a document, a media type it lists) and every literal
response header the endpoint declares, with the value declared; one at a business error's fixed status that error; and
any other is a 4xx or 5xx `problem.details`.

**Deliberately absent**, each a decision: links, callbacks, examples, tags, servers, more than one success response,
custom and WebDAV verbs (`service.http_verb` is a closed enum), and a declaration of the transport's own refusals, which
every endpoint has and the error boundary answers with a `problem.details`. Adding any is an edit in place until this is
published, and a new identity after; a new kind of endpoint is a new record composing `service.endpoint`.
