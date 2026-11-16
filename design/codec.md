# `TsonHttpCodec` and the types around it

What `tson-http` owns: media types, the codec, the error-body types, the two schema headers and the catalog. Current
form only; history lives in git. The adapter side of the contract is `design/adapters.md`; the status policy is
`design/status-classification.md`.

**Invariants**

- `TsonHttpCodec` is the type an adapter drives; everything else supports it. `TsonHttpException.from` is the entire
  status policy, and nothing status-shaped lives anywhere else.
- A request body is peeked with `TsonHttpCodec.begin` and the peek is passed on: it *is* the body. A peek belongs to
  the processor policy it was opened under, so a reader that disagrees is refused.
- A TSON body's claim about what governs it is its `!!import` list, read as a set of canonical identities.
  `readObjectAs`/`readTreeAs` refuse a body whose imports are not the stated set (400 `conflicting-schema`), one more
  import included; upstream's `readAs` would silently let the stated one win.
- `TSON-Schema` agrees with a body's imports as a set, compared whole. Membership is never agreement: it would let a
  sender widen the namespace a contract agreed to by adding an import.
- `describing(…)` needs a root type name as well as a schema URI for an object; the tree form takes the URI alone.
- The JSON reader is built over the composed bind context minus the kernel's vocabulary. Do not hand it
  `Tson.dataBindContext()` whole until `UpstreamGapsTest.jsonBindingUnderTheComposedBindContextRefusesTheKernel` flips.
- A directive stands in a document's header and nowhere else; one inside a value is a parse error, a 400, refused
  before anything is fetched.
- An error body is only ever buffered. A problem names `problem.tn` in the `TSON-Schema` header in both encodings.
- `TsonHttpException.from`'s base-syntax branch is a net, not a path: it classifies three exception types no caller
  here can catch and rethrows everything else. Do not delete it for being unreachable.
- `HttpSchemaSource` is upstream's. Do not reintroduce a copy here.

**`TSON-Schema` header — built, and the spec's.** [TSON-JSON] §3.5 defines `TSON-Schema` and `TSON-Accept-Schema` for
every TSON-carrying body, [TSON-DATA] §7.1 points there, and `SCHEMA-HEADER.md` is the design record behind it. Cite
§3.5 for the rules; `TsonSchemaHeader` is the implementation. An RFC 9651 List of Strings, each member a schema
reference with no parameters (quoted, always — an unquoted URI parses as an sf-token right up until someone pins a
schema, which is why the strictness is deliberate); a List of one is spelt as the single String, so a one-schema
message is unchanged; permitted on requests and responses and on a body of any media type; agreeing with the body's
own binding as a set of canonical identities, a mismatch being a 400 rather than a precedence question; and a
schemaless body stays valid TSON, with "must name a schema" living in endpoint policy, which
`TsonSchemaVersions.route` enforces.

**The agreement rule as implemented.** `TsonSchemaHeader.resolve(peek, fieldValue)` reads the header against
`peek.header().imports()`, the `TsonDocumentHeader(id, imports)` the peek carries. Both sides are sets of canonical
identities: scheme, pin, order and repetition carry nothing. A schema an extern names ([TSON-SCHEMA] §5.13) is a
member of neither: it loads on an import's terms, the same source, policy and digest, but none of its names enters the
body's namespace, so a body holding a foreign value imports the declaring schema alone and agrees with a header naming
that alone (`ExternProbeTest`). `Governing.schemas()` is the governing set, one reference per identity:

| Header | Body imports | Governs |
|---|---|---|
| present | none | the header's set |
| present | the same set | that set, spelt as the body spells it |
| present | a different set (one more, one fewer, or other) | nothing: 400 `conflicting-schema`, both sides listed |
| absent | one or several | the imports |
| absent | none | nothing (schemaless) |

A header member with a parameter, an empty member, or a present field holding no member is 400
`malformed-schema-header`; a member that is no schema identity is 400 `unusable-schema-reference`. Which member of the
governing set is a contract's version is not `resolve`'s question: `TsonSchemaVersions.route` answers it
(`design/schema-versions.md`). A JSON body is not peeked, so its set is the header's alone. The adapters write the
field on a response through `TsonSchemaHeader.format`, a List of one.

## What `tson-http` looks like today

`TsonHttpCodec` is the type an adapter drives; everything else supports it.

- `TsonMediaType` / `TsonAcceptHeader` — value types, no HTTP-status policy. Malformed input throws
  `IllegalArgumentException` and the codec decides what that is worth.
- `TsonHttpCodec` — reads bodies (tree or bound, self-describing or against a stated schema and type),
  writes bodies, and gates on `Content-Type` and `Accept`.
- `TsonHttpException` — status plus diagnostics. **`from(RuntimeException)` is the entire status policy**;
  put nothing status-shaped anywhere else. Problem `type` URIs live under `https://ltr8.io/2026/40/http/problems/`
  — `ltr8.io` is the implementation resource, kept apart from the specification's `tson.io`, where schema
  identities live. The revision rides in it too, so a spec bump moves it with everything else.
- `TsonProblem` / `TsonProblemDiagnostic` / `TsonProblemSchema` / `problem.tn` — the error body, its schema,
  and the reader that proves the two agree. **This project's own schema, maintained here** — it began as a copy
  of `tson-cli`'s `diagnostics.tn` and has diverged on purpose: a CLI reports on files and a server reports on
  requests, and lifting the two into a shared module was asked for upstream and rightly rejected. Its one root
  is the schema map `problem`: `problem.details` follows **RFC 9457**; `problem.diagnostic` stays close to what a
  TSON read produces, because that is what it reports.
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
  one-shot, and `TsonDocumentPeek` is the continuation handle: the header has been read into a
  `TsonDocumentHeader(id, imports)` and a reader continues from just past it, so nothing is buffered and re-fed.
  `TsonSchemaHeader.Governing` and `TsonSchemaVersions.Routed` therefore carry a peek rather than an `InputStream`.
  **A peek belongs to the processor policy it was opened under** — §9.1's limits and §8.2's token policy were
  applied to the tokens the header is made of — and a reader that disagrees is *refused*, not quietly obeyed.
  That is why `begin` is on the codec: taking the peek from the codec that will read it keeps the two the same,
  and it is why `resolve` takes a peek rather than opening one it could not pick a policy for. A malformed header
  peeks as no imports, and the read that continues reports the real problem.
  **A `ByteArrayInputStream` will not catch a mistake here**: it supports `mark`/`reset`, so a peek that
  consumes the stream still looks intact. Test with a stream whose `markSupported()` is false and whose
  `reset()` throws, as `TsonSchemaHeaderTest` does — this project shipped a peek that ate the whole body on a
  real request and had a green test suite over the wrong fixture.
- **`describing()` needs a root type name as well as a schema URI, for an object.** A data document under imports
  names its root's type ([TSON-SCHEMA] §7.1), and a bound record writes no type-ref of its own, so `!!import` alone
  yields a document no reader can select a type in. The tree form takes one argument, because a tree node already
  carries a type-ref. `TsonHttpCodec.write(value, schemaUri, rootTypeName)` and `writeTree(value, schemaUri)` mirror
  that asymmetry deliberately.

- **`readAs` requires a schema URI.** Selecting a root type is meaningless without a schema to select it
  from; `readTreeAs`/`readObjectAs` therefore take one, and it must already be registered. Passing an
  unregistered URI is a server configuration error and surfaces as 500, by design.
- **Upstream's `readAs` overrides a TSON body's own imports, so the codec checks agreement itself.** A stated
  schema wins over the ones the document imports, even different ones, with no diagnostic — in both readers, and by
  upstream's decision: `TsonObjectReader.readAs(TsonDocumentPeek, …)` says so and leaves the comparison to the
  caller. So `TsonHttpCodec.readObjectAs`/`readTreeAs` peek a TSON body and answer 400 `conflicting-schema` when
  its imports are not the stated set of canonical identities, before reading on the peek — the body read once. A
  body importing none is read against the stated schemas, which is what stating them is for; one importing the
  stated schema and another is refused, since it would be read under a wider namespace than the one stated. The
  `List<String>` overloads state several and read under all of them through the reader's `withImports`, one
  namespace as the body's own imports would be. **Do not bypass the codec with the reader's `readAs`** on a body a
  client wrote: that is the unchecked path.
  `UpstreamGapsTest.aStatedSchemaSilentlyOverridesTheDocumentsOwn` pins the override, so the codec's copy of the
  rule is noticed the day upstream starts refusing on its own. `TsonSchemaVersions.Routed.readObjectAs` goes
  through the same path, stating the governing set, which is why a TSON body naming its schemas only in the header
  is still read under them rather than schemaless.
- **The JSON reader's bind context leaves the kernel's vocabulary out.** `Tson.of` composes the kernel's vocabulary
  over the configured binder and answers it first, so `Tson.dataBindContext()` binds every kernel record to the
  schema model. Handed that context, `tson-json`'s bind registry checks those records against their classes when it
  compiles a closure, refuses `net.iri_type`, and binds no body at all, since every closure holds the kernel.
  `acceptingJson` therefore builds the `Json` with the composed context wrapped so that a kernel name is unbound
  (`withoutKernelVocabulary`); a JSON body binds application names only, so nothing it needs is lost. Pinned by
  `UpstreamGapsTest.jsonBindingUnderTheComposedBindContextRefusesTheKernel` and carried in `UPSTREAM.md`; when the
  pin flips, the filter goes.
- **A directive inside a value is a parse error.** A request body names its schemas in its header and nowhere
  else, so no value can make the server fetch one: `{ attachment: !!import:"…" !order {…} }` is a 400
  `VALIDATION_ERROR`, *"directive '!!import' is not permitted here"*, refused before any fetch
  (`TsonHttpSchemaSourceIntegrationTest.aDirectiveInsideAValueIsABadRequestAndFetchesNothing`).
