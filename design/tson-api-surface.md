# The tson-java surface this project builds on

The facts about tson-java that shape the HTTP integration: the front door, schema access, the shared vocabulary, read
and write modes, self-describing documents, and the concurrency contract. Current form only. Read tson-java's own
`CLAUDE.md` and `design/` before working on the codec; this note holds only what HTTP depends on.

**Invariants**

- Build the `Tson` and resolve every schema during startup, on one thread; then share it across request threads for
  reads only. Never resolve a schema from a request handler.
- `ProcessorConfig` is an immutable value: a helper that applies settings returns a config.
- An `!!import` in a request body is an untrusted URL, and every schema source behind the codec is policy-gated. A
  directive stands in a document's header alone; one inside a value is a parse error.
- Readers take `InputStream` as well as `String`; writers take a sink and flush without closing.
- Error classification is upstream's policy worn as status codes: see `design/status-classification.md`.

## The tson API surface this project builds on

**Front door.** `ProcessorConfig.defaults()` → `.withSchemaAccess(…)` / `.withDataBindContext(…)` /
`.withProcessorPolicy(…)` → `Tson.of(config)`, or `Tson.standard()` unconfigured. **The config is an immutable value,
not a builder**: each `withX` returns a new one, so a helper that "applies" settings returns a config rather than
mutating the caller's (which is what `TsonDeployment.applyTo` does). Bootstrapping loads the bundled schemas — the
kernel `meta.tn`, then `numeric.tn`, `temporal.tn`, `net.tn`, `core.tn` and `policy.tn` — and nothing else is served
unless registered or reachable through the configured source. From a `Tson`:

- `begin(…)` — a `TsonDocumentPeek` over a body, its `header()` a `TsonDocumentHeader(id, imports)`, the imports in
  header order. Total in the document's content: a malformed header peeks as no imports, and the read that continues
  reports the real problem. A peek belongs to the policy it was opened under (`design/codec.md`).
- `treeReader()` / `objectReader()` — schema-aware readers. A document's own `!!import`s select its schemas, several
  read under one namespace; `withSchema(uri)` or `withImports(list)` states them instead, and `readAs(peek, type)`
  (with a class, for the object reader) continues a peek at a stated root type. A stated schema overrides the
  document's own imports silently, which is why the codec checks agreement itself.
- `treeWriter()` / `objectWriter()`.
- `dataBindContext()` — the configured context with the kernel's vocabulary composed over its binder and asked first,
  so every kernel name binds to the schema model. It binds a schema's value entries and a data document's values
  alike (`design/binding.md`). `tson-json` given it whole binds nothing; `design/json-encoding.md` has the workaround.
- `resolve(schemaText)` / `validateSchema(schemaText)` — register a schema by its own `!!id`; the second collects
  rather than throws, and registers only a sound schema.
- `validate(data)` — a data document, schemaless where it imports nothing. A document rooted `!schema` is refused as
  a diagnostic whose message blames Class 1 (`UPSTREAM.md`); use `validateSchema` for one.
- `schemaRegistry()` — what is registered, by identity; the JSON reader is built `withSchemas(schemaRegistry())`.
  `loader()` — the resolution core as an on-demand loader.
- `processorPolicy()` / `limitsPolicy()` — the policy in force, which `TsonDeployment.profile` publishes.

**Where a schema comes from is its own value.** `SchemaAccess.registeredOnly()` (nothing is fetchable — what an
unconfigured deployment gets), `of(source)`, `httpSchemas(hosts…)`, `fileSchemas(host, dir)`, or a builder
carrying a `FetchPolicy` beside the source. `registeredOnly()` is the shape the validator demo wants.

**The shared vocabulary lives in `io.ltr8.tson.base`**, a module of its own, with the `Tson` prefix stripped —
`Diagnostic`, `SourcePosition`, `DiagnosticsCollector`/`Receiver`, `CanonicalIdentity`, `ProcessorConfig`,
`policy.IdentifierPolicy`/`ScriptPolicy`/`LimitsPolicy`/`ProcessorPolicy`,
`source.SchemaSource`/`HttpSchemaSource`/`FileSchemaSource`,
`bind.AtomContext`, and the exceptions. It is one vocabulary across both encodings by specification, which is
why it is not in `tson-compiler`. **The classifying factories stayed behind**: `TsonDiagnostics` in
`tson-compiler` owns `ofBaseSyntaxError` and friends, because they switch on the TSON reader's own exception
types. The schema model's classes live in `io.ltr8.tson.schema.<namespace>` by the bundled map that declares them
(`schema.meta.ValueEntry`, `schema.meta.Namespace`, `schema.net.IriType`).

**Read mode is which registry you hold, not a parameter.** `treeReader()` yields an immutable queryable
`TsonValue`; `objectReader()` binds to Java objects. Both readers take `InputStream` as well as `String`
(`read`, `readWithoutSchema`, `readAs`), so a request body streams in without being buffered into a
`String` first.

**Writers take a sink.** `TsonObjectWriter`/`TsonTreeWriter` have `write(value, OutputStream)` and
`write(value, Appendable)` alongside `toTson`; the stream is flushed and not closed, so it stays the
adapter's. `TsonHttpCodec.writeTo`/`writeTreeTo` stream an ordinary response; `write`/`writeTree` buffer for
a caller that wants `Content-Length`. **An error body is only ever buffered** — streaming one means a failure
part-way through leaves a client holding a truncated problem on a response whose status is already sent.

**A self-describing document carries its own `!!import`s** and the reader resolves each through the configured
`SchemaSource`. This is the crux of the HTTP story: a schema URL arriving in a request body is an **untrusted URL**,
so the HTTP-backed source must be policy-gated (allow-list of origins, timeouts, size cap, cache) rather than
fetching whatever it is handed. Never wire a naive fetcher in. The header is the only place such a URL can stand: a
directive inside a value is a parse error, refused before anything is fetched (`design/schema-sources.md`).

**Concurrency — the load-bearing constraint for a server.** tson-java is not documented as thread-safe as
a whole, and parts of it explicitly are not (`TsonCompiledMetaRegistry`: "`register`/`get` are
`synchronized`, but `loadMeta` … are not"; `Lexer` and `TsonDataEmitter` are single-use, not thread-safe).
What *is* built for cross-thread use is the post-compile read path — `CompiledReaders` holds its binding
`volatile` precisely because the compile happens on one thread and reads happen on many. So the required
shape is:

> **Build the `Tson` instance and resolve/compile every schema during startup, on one thread. Only then
> share it across request threads, and only for reads.** Never resolve a schema lazily from a request
> handler.

A schema arriving at runtime (an unknown `!!import` URL) must go through a single guarded resolution
path, not a concurrent one. The contract is now stated where a server reads it — `Tson`'s class Javadoc
(concurrent reads through one instance are safe; on a race work may be duplicated but state never is;
`resolve`/`validateSchema` and mutating a `DataBindContext` under live reads are outside the guarantee) and
`ProcessorConfig.withDataBindContext` — and pinned upstream by `ReadPathConcurrencyTest` and
`SharedInstanceConcurrencyTest`. `TsonHttpCodecConcurrencyTest` is the same claim measured from this end.


