# The tson-java surface this project builds on

The facts about tson-java that shape the HTTP integration: the front door, schema access, the shared vocabulary, read
and write modes, self-describing documents and the scope push, and the concurrency contract. Current form only. Read
tson-java's own `CLAUDE.md` and `design/` before working on the codec; this note holds only what HTTP depends on.

**Invariants**

- Build the `Tson` and resolve every schema during startup, on one thread; then share it across request threads for
  reads only. Never resolve a schema from a request handler.
- `ProcessorConfig` is an immutable value: a helper that applies settings returns a config.
- A `!!schema` URL in a request body is an untrusted URL. Every schema source behind the codec is policy-gated, and the
  §7.8 scope push resolves through the same source and the same allow-list.
- Readers take `InputStream` as well as `String`; writers take a sink and flush without closing.
- Error classification is upstream's policy worn as status codes: see `design/status-classification.md`.

## The tson API surface this project builds on

Read tson-java's own `CLAUDE.md` and `docs/` before working on the codec — but these are the facts that
shape the HTTP integration specifically:

**Front door.** `ProcessorConfig.defaults()` → `.withSchemaAccess(…)` / `.withDataBindContext(…)` →
`Tson.of(config)`, or `Tson.standard()` unconfigured. **The config is an immutable value, not a builder**: each
`withX` returns a new one, so a helper that "applies" settings returns a config rather than mutating the caller's
(which is what `TsonDeployment.applyTo` does). Bootstrapping loads meta-kernel + `meta.tn` + `core.tn`. From a
`Tson` you get `treeReader()`, `objectReader()`, `treeWriter()`, `objectWriter()`, `resolve(schemaText)`,
`validate(…)`, and `begin(…)`.

**Where a schema comes from is its own value.** `SchemaAccess.registeredOnly()` (nothing is fetchable — what an
unconfigured deployment gets), `of(source)`, `httpSchemas(hosts…)`, `fileSchemas(host, dir)`, or a builder
carrying a `FetchPolicy` beside the source. `registeredOnly()` is the shape the validator demo wants, and it now
says so rather than passing a source that refuses everything.

**The shared vocabulary lives in `io.ltr8.tson.base`**, a module of its own, with the `Tson` prefix stripped —
`Diagnostic`, `SourcePosition`, `DiagnosticsCollector`/`Receiver`, `CanonicalIdentity`, `ProcessorConfig`,
`policy.IdentifierPolicy`/`ScriptPolicy`/`LimitsPolicy`/`ProcessorPolicy`,
`source.SchemaSource`/`HttpSchemaSource`/`FileSchemaSource`,
`bind.AtomContext`, and the exceptions. It is one vocabulary across both encodings by specification, which is
why it is not in `tson-compiler`. **The classifying factories stayed behind**: `TsonDiagnostics` in
`tson-compiler` owns `ofBaseSyntaxError` and friends, because they switch on the TSON reader's own exception
types.

**Read mode is which registry you hold, not a parameter.** `treeReader()` yields an immutable queryable
`TsonValue`; `objectReader()` binds to Java objects. Both readers take `InputStream` as well as `String`
(`read`, `readWithoutSchema`, `readAs`), so a request body streams in without being buffered into a
`String` first.

**Writers take a sink.** `TsonObjectWriter`/`TsonTreeWriter` have `write(value, OutputStream)` and
`write(value, Appendable)` alongside `toTson`; the stream is flushed and not closed, so it stays the
adapter's. `TsonHttpCodec.writeTo`/`writeTreeTo` stream an ordinary response; `write`/`writeTree` buffer for
a caller that wants `Content-Length`. **An error body is only ever buffered** — streaming one means a failure
part-way through leaves a client holding a truncated problem on a response whose status is already sent.

**A self-describing document carries its own `!!schema` directive** and the reader resolves it through
the configured `SchemaSource`. This is the crux of the HTTP story: a schema URL arriving in a request
body is an **untrusted URL**, so the HTTP-backed source must be policy-gated (allow-list of origins,
timeouts, size cap, cache) rather than fetching whatever it is handed. Never wire a naive fetcher in.

**§7.8's scope push is a second such URL, at a *value* position, and it is new in Revision 35.** At a `scoped`
field the value carries its own nested `!!schema` and a type-ref resolved there, loaded as the value arrives
rather than from the document header — so a body can name an origin from halfway down a record. It resolves
through the same `SchemaSource`, so the same allow-list guards it; `TsonHttpSchemaSourceIntegrationTest.`
`aScopePushInARequestBodyIsGatedByTheSameAllowList` pins both directions, because only the pair says the gate is
a gate. Two properties keep it from being a wider surface than it looks: **a schema has to opt in** — a push at a
position whose type is not `scoped` is `SCOPE_NOT_ADMITTED`, a resolver error and so a 400, refused before anything
is fetched (`aPushWhereTheSchemaOpensNoScopeIsABadRequest`), so a body cannot push a scope into an ordinary
record — and **a schemaless document opens no scope at all**. Both are §7.8's own rules, not this project's.


**Concurrency — the load-bearing constraint for a server.** tson-java is not documented as thread-safe as
a whole, and parts of it explicitly are not (`TsonCompiledMetaRegistry`: "`register`/`get` are
`synchronized`, but `loadMeta` … are not"; `Lexer` and `TsonDataEmitter` are single-use, not thread-safe).
What *is* built for cross-thread use is the post-compile read path — `CompiledReaders` holds its binding
`volatile` precisely because the compile happens on one thread and reads happen on many. So the required
shape is:

> **Build the `Tson` instance and resolve/compile every schema during startup, on one thread. Only then
> share it across request threads, and only for reads.** Never resolve a schema lazily from a request
> handler.

A schema arriving at runtime (an unknown `!!schema` URL) must go through a single guarded resolution
path, not a concurrent one. The contract is now stated where a server reads it — `Tson`'s class Javadoc
(concurrent reads through one instance are safe; on a race work may be duplicated but state never is;
`resolve`/`validateSchema` and mutating a `DataBindContext` under live reads are outside the guarantee) and
`ProcessorConfig.withDataBindContext` — and pinned upstream by `ReadPathConcurrencyTest` and
`SharedInstanceConcurrencyTest`. `TsonHttpCodecConcurrencyTest` is the same claim measured from this end.


