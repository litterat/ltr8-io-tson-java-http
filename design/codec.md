# `TsonHttpCodec` and the types around it

What `tson-http` owns: media types, the codec, the error-body types, the two schema headers and the catalog. Current
form only; history lives in git. The adapter side of the contract is `design/adapters.md`; the status policy is
`design/status-classification.md`.

**Invariants**

- `TsonHttpCodec` is the type an adapter drives; everything else supports it. `TsonHttpException.from` is the entire
  status policy, and nothing status-shaped lives anywhere else.
- A request body is peeked with `TsonHttpCodec.begin` and the peek is passed on: it *is* the body. A peek belongs to
  the processor policy it was opened under, so a reader that disagrees is refused.
- `readObjectAs`/`readTreeAs` compare a TSON body's own `!!schema` with the stated one by canonical identity and answer a
  disagreement 400 `conflicting-schema`; upstream's `readAs` would silently let the stated one win.
- `describing(…)` needs a root type name as well as a schema URI for an object; the tree form takes the URI alone.
- An error body is only ever buffered. A problem names `problem.tn` in the `TSON-Schema` header in both encodings.
- `TsonHttpException.from`'s base-syntax branch is a net, not a path: it classifies three exception types no caller
  here can catch and rethrows everything else. Do not delete it for being unreachable.
- `HttpSchemaSource` is upstream's. Do not reintroduce a copy here.

**`TSON-Schema` header — built, and now the spec's.** Revision 36 adopted it: [TSON-JSON] §3.5 defines
`TSON-Schema` and `TSON-Accept-Schema` for every TSON-carrying body, [TSON-DATA] §7.1 points there, and the rules
are the ones settled here. Cite §3.5 for them; `SCHEMA-HEADER.md` is the design record behind it, and
`TsonSchemaHeader` is the implementation. An RFC 9651 sf-string (quoted, always — an unquoted URI parses as an sf-token
right up until someone pins a schema, which is why the strictness is deliberate); permitted on requests and responses
and on a body of any media type; may coexist with the body's `!!schema` and must then agree by canonical
identity, a mismatch being a 400 rather than a precedence question; and a schemaless body stays valid TSON,
with "must name a schema" living in endpoint policy — which is what `TsonSchemaVersions.route` enforces.


## What `tson-http` looks like today

`TsonHttpCodec` is the type an adapter drives; everything else supports it.

- `TsonMediaType` / `TsonAcceptHeader` — value types, no HTTP-status policy. Malformed input throws
  `IllegalArgumentException` and the codec decides what that is worth.
- `TsonHttpCodec` — reads bodies (tree or bound, self-describing or against a stated schema and type),
  writes bodies, and gates on `Content-Type` and `Accept`.
- `TsonHttpException` — status plus diagnostics. **`from(RuntimeException)` is the entire status policy**;
  put nothing status-shaped anywhere else. Problem `type` URIs live under `https://ltr8.io/2026/38/http/problems/`
  — `ltr8.io` is the implementation resource, kept apart from the specification's `tson.io`, where schema
  identities live. The revision rides in it too, so a spec bump moves it with everything else.
- `TsonProblem` / `TsonProblemDiagnostic` / `TsonProblemSchema` / `problem.tn` — the error body, its schema,
  and the reader that proves the two agree. **This project's own schema, maintained here** — it began as a copy
  of `tson-cli`'s `diagnostics.tn` and has diverged on purpose: a CLI reports on files and a server reports on
  requests, and lifting the two into a shared module was asked for upstream and rightly rejected. `problem`
  follows **RFC 9457**; `diagnostic` stays close to what a TSON read produces, because that is what it
  reports.
- `io.ltr8.tson.base.source.HttpSchemaSource` / `SchemaFetchException` — **upstream's, not this module's** (see
  above). What stays here is the half upstream cannot know: `TsonHttpException.from` maps a fetch `Reason`
  through `Diagnostic.Code.of` into the one fetch status table, and `TsonHttpSchemaSourceIntegrationTest` pins
  that mapping and the codec's end-to-end read of a document whose schema arrived over the wire. The policy itself is
  upstream's suite — do not retest it here.
  Read the class notes before relying on anything in it; every rule there is load-bearing.


## Traps

- **`TsonHttpException.from`'s base-syntax branch is a net, not a path.** A document that will not parse is
  reported through the receiver, so a collecting read returns its diagnostics rather than throwing.
  `Diagnostic.ofBaseSyntaxError` stays in `from` because it classifies the three base-syntax exception
  types — two of which live in an unexported package, so no caller here could
  `catch` them — and **rethrows anything else**, which is what stops an unexpected fault becoming a false
  verdict about the request. Do not delete it for being unreachable.

- **Peek a request body with `TsonHttpCodec.begin`, and pass the peek on — it *is* the body.** A body is
  one-shot, and `TsonDocumentPeek` is the continuation handle: the header has been read and a reader continues
  from just past it, so nothing is buffered and re-fed and `document()` no longer exists. `TsonSchemaHeader
  .Governing` and `TsonSchemaVersions.Routed` therefore carry a peek rather than an `InputStream`.
  **A peek belongs to the processor policy it was opened under** — §9.1's limits and §8.2's token policy were
  applied to the tokens the header is made of — and a reader that disagrees is *refused*, not quietly obeyed.
  That is why `begin` is on the codec: taking the peek from the codec that will read it keeps the two the same,
  and it is why `resolve` takes a peek rather than opening one it could not pick a policy for.
  **A `ByteArrayInputStream` will not catch a mistake here**: it supports `mark`/`reset`, so a peek that
  consumes the stream still looks intact. Test with a stream whose `markSupported()` is false and whose
  `reset()` throws, as `TsonSchemaHeaderTest` does — this project shipped a peek that ate the whole body on a
  real request and had a green test suite over the wrong fixture.
- **`describing()` needs a root type name as well as a schema URI, for an object.** A bound record writes no
  type-ref of its own, so `!!schema` alone yields a document whose reader cannot select a type. The tree form
  takes one argument, because a tree node already carries a type-ref. `TsonHttpCodec.write(value, schemaUri,
  rootTypeName)` and `writeTree(value, schemaUri)` mirror that asymmetry deliberately.

- **`readAs` requires a schema URI.** Selecting a root type is meaningless without a schema to select it
  from; `readTreeAs`/`readObjectAs` therefore take one, and it must already be registered. Passing an
  unregistered URI is a server configuration error and surfaces as 500, by design.
- **Upstream's `readAs` overrides a TSON body's own `!!schema`, so the codec checks agreement itself.** A stated
  schema wins over one the document names, even a different one, with no diagnostic — in both readers, and by
  upstream's decision: `TsonObjectReader.readAs(TsonDocumentPeek, …)` says so and leaves the comparison to the
  caller. So `TsonHttpCodec.readObjectAs`/`readTreeAs` peek a TSON body, compare its `!!schema` with the stated
  one by canonical identity, and answer a disagreement 400 `conflicting-schema` before reading on the peek — the
  body read once. A body naming no schema is read against the stated one, which is what stating one is for. **Do
  not bypass the codec with the reader's `readAs`** on a body a client wrote: that is the unchecked path.
  `UpstreamGapsTest.aStatedSchemaSilentlyOverridesTheDocumentsOwn` pins the override, so the codec's copy of the
  rule is noticed the day upstream starts refusing on its own. `TsonSchemaVersions.Routed.readObjectAs` goes
  through the same path, which is why a TSON body naming its version only in the header is still read against
  that version's schema rather than schemaless. The demos still read a TSON body by its own binding (`readObject`)
  and use `readObjectAs` for JSON — a choice, since both are now checked.


