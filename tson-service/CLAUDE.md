# tson-service — a service's protocol description (`io.ltr8.tson.service`)

What a service says about its exchanges, naming no transport: `service.tn` (in `src/main/resources`, the module's only
schema) and the packages below. The layout rule, shared with `tson-http`: **the root package is the API; `schema` holds
only the classes that mirror the module's `.tn` types, each carrying `@Typename`; `internal` is unexported machinery.**
The root `io.ltr8.tson.service` holds `TsonBindings`, `TsonServiceSchema` (the holder of `service.tn`),
`TsonServiceProtocol` (the join) and `ExchangeName`. `io.ltr8.tson.service.schema` holds `Protocol`, `Exchange` and
`Signature`. `io.ltr8.tson.service.internal` holds `Landed`, exported only to `io.ltr8.tson.http` and kept out of the
generated Javadoc. The HTTP projection, its reader and `http.tn` are `tson-http`'s; this module depends on tson-java,
`ltr8-bind`, `ltr8-annotation` and `tson-atom` directly and on nothing of this repo. Start with the note for the type you
are touching:

| Working in | Read (`design/…`) |
|---|---|
| `TsonBindings` (`of`, `bySchema`) | `binding.md` |
| `service.tn`, `ExchangeName`, the bound records, `TsonServiceSchema` | `service-description.md` |
| `TsonServiceProtocol`, the join, the protocol layer | `invoke.md` |
| `Landed` (`internal`), which the reader in `tson-http` uses | `service-reader.md` |

Rules that bite here:

- Nothing here names a type of `tson-http`, and nothing HTTP: the HTTP half is `tson-http`'s `http.tn`, which imports
  `service.tn`. `TsonServiceSchemaTest.anHttpTagDoesNotResolveThroughTheProtocolSchema` holds `service.tn` to resolving
  with no HTTP name visible.
- No external runtime dependency; tson-java's own modules (`io.ltr8:tson`, `io.ltr8:ltr8-bind`, `io.ltr8:ltr8-annotation`,
  `io.ltr8:tson-atom`) are not external.
- `schema` imports nothing from the module's other packages, and `internal` imports neither `schema` nor the root. A
  class in `schema` binds to a record only by being named in `TsonServiceSchema.bindings()`, an explicit map keyed by
  qualified name, as its `@Typename` is; a record added there binds nothing until a line is added to the map, and an
  application binding one of those names is refused.
- A `{@link}` to `Landed`, or any `internal` type, from an exported package breaks the Javadoc build: the package is
  excluded from it. Name it in `{@code}`.
- The join's atom check copies one name-keyed rule for the kernel's `boolean` from a package-private part of the
  compiler, and its test for a closed union is its own; each is pinned in `UpstreamGapsTest` and flips when upstream
  answers. A protocol the join cannot judge (a namespace type, a tuple, a record written in place) is refused, not
  skipped, as is a protocol whose value did not bind. It does not check a record's fields, facets, a bridge's conversion
  or an exchange's facts (`design/invoke.md`, "The join").
- The tests are this module's own, in package `io.ltr8.tson.service`: `TsonServiceProtocolTest` and
  `TsonServiceSchemaTest` over the protocol-only shop fixtures (`Shop`, `ShopExamples`, resources under
  `io/ltr8/tson/service/shop/`), which need nothing of `tson-http`. The description's tests are `tson-http`'s.
