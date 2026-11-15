# Schemas this project owns: identity, media type, and the spellings that bite

The `!!id` convention, the `.tn` extension, immutability before and after publication, what a spec revision bump
touches, and the schema-language rules that read like bugs when they bite. Current form only.

**Invariants**

- Project-owned `!!id`: `https://tson.io/2026/40/io/ltr8/http/<name>.tn`, no version in the name. The demos' example
  application keeps numbered names on purpose.
- A header is `!!id`, then `!!import`s; a schema's root is `!schema { … }`. A data document imports its schemas and its
  root names its type. `!!meta` and `!!schema` are parse errors.
- A schema sees its own entries and its import closure, nothing else: importing nothing, it does not see `text`.
- `.tn`, never `.tn1`. A revision bump is one substitution across every identity; the check is that no `2026/<previous>`
  string survives and the build is green.
- Nothing here is published yet, so a schema is edited in place. Once published, a shape change is a new identity.
- `problem.tn`'s `problem.diagnostic_code` is a hand-written copy of `Diagnostic.Code`; `TsonProblemSchemaTest` is the
  only thing that catches it going stale. Keep it.
- A schema importing core reserves every root name of the bundled documents, so an application type is never named
  `int64`, `schema`, `host`, `date`, `uri`, `media_type`, `title` or the like.
- A vocabulary this project publishes declares its entries under one map named for itself, so it reserves one root name
  in every importer: every root name of a closure is reserved and a clash is unfixable by the importer
  ([TSON-SCHEMA] §2.2.3). `service.tn` declares everything, annotations included, under `service`, `problem.tn` under
  `problem` and `deployment.tn` under `deployment`, and each names its own entries by the same path from inside the
  map. A bound class's `@Typename` is the qualified name (`problem.details`, `deployment.descriptor`).
- An optional field is `name?: T`; `name: T?` is a required field admitting `_`. A `text` field accepts any token.
  A `pattern` is I-Regexp and `^`/`$` are literal characters.
- `^` follows `!name` and takes values; a record derives by `&` alone, which admits a new field, so a misspelt
  restatement is a new field and its spelling is the author's to check.
- A `pattern` judges the value after the type's `normalization` has folded it. An ASCII protocol's name (an HTTP
  header field name, an authentication scheme) folds with `ASCII_CASEFOLD`, never `NFKC_CASEFOLD`, which folds
  compatibility characters into ASCII and widens what an ASCII pattern admits.
- A base record that has no values of its own is declared `abstract` (`service.endpoint`, `service.security_scheme`):
  the resolver then refuses an untagged value, and one tagged with the base, naming the subtypes, in every mode.

## The header, and the bundled documents

**One directive, and the root says the kind.** Every document's header is an optional `!!id` followed by any number
of `!!import`s, and a document is a schema exactly when its root is tagged `!schema`:

```
!!id:"https://tson.io/2026/40/io/ltr8/http/problem.tn"
!!import:"https://tson.io/2026/40/m/core.tn"
!schema {
  problem => !schema {
    details => { … }
  }
}
```

A data document imports the schemas it is read under — several are one namespace over their closures, a name two of
them declare differently being a collision — and its root names its type (`!problem.details { … }`). `!!meta` and `!!schema`
are parse errors naming their replacement, and a directive anywhere past the header is a parse error too.

**A schema's namespace is its own entries and its import closure's, and nothing else.** There is no ladder and no
level: any schema may declare a constructor or a namespace type, and an import crosses no level
(`UpstreamGapsTest.anImportCrossesNoLevel`). The other face of it is that a schema importing nothing sees only its own
entries, so `text` there is an unresolved reference with no word about where `text` lives (`UPSTREAM.md` carries the
message).

**The bundled documents** live under `https://tson.io/2026/40/m/`:

| Document | Imports | Its root |
|---|---|---|
| `meta.tn` | nothing; pre-loaded | the kernel: `void`, `boolean`, `integer`, `int64`, `text`, `bytes`, `iri`, `iri_reference`, `regex`, `doc`, `enum`, `set`, `schema` |
| `numeric.tn`, `temporal.tn`, `net.tn` | `meta.tn` | `int32`, `uint64`, `number` and kin; `date`, `time`, `datetime`, `duration`, `period`; `host`, `uri`, `uri_reference`, `media_type`, `uuid`, `email` and kin |
| `core.tn` | `numeric.tn`, `temporal.tn`, `net.tn` | the author annotations `deprecated`, `title`, `comment`, `examples`, `read_only`, `write_only` |
| `policy.tn` | `meta.tn` | the schema map `policy`, the processor policy's vocabulary, from `policy.processor` down |

**Root names are the common vocabulary; machinery is path-qualified.** An author writes `text`, `int32`, `date`,
`uuid`, `media_type`, `!enum [A B]` and `@doc` bare, and `meta.record`, `meta.identifier`, `meta.type_ref`,
`meta.identifier_ref`, `meta.ref<T>`, `!meta.namespace { key_type: K  value_type: V }`, `!unicode.text_type { … }`,
`!numeric.integer_type { … }`, `numeric.count` and `net.media_type_type` by path. A path's first segment is a schema
map nested in its document's root as an entry, `meta => !schema { … }`, which is how a library has a namespace of its
own; `policy.tn` declares `policy => !schema { … }` the same way, and this project's `service.tn` declares
`service => !schema { … }`. `meta.ref<T>` is the one reference spelling, sugar for the construction
`!meta.ref { of: T }`: over a constructor it names the types built with it (`meta.ref<net.media_type_type>`), over any
other type the values of it (`meta.ref<service.exchange>`), and `meta.type_ref` is the reference over `meta.type`.
`kind!` is no reference and no value: it is the schema grammar's sugar for a dependent type written in place,
`{ of: !meta.dependent { on: kind } }`, and stands only at a record field's type. `media_type` and `uri_reference`
come from `net.tn` through core, which is where `service.tn` and `problem.tn` take them.

## Media type and file extension

`application/tson`, optionally `application/tson; version=1` where HTTP-context disambiguation is needed
([TSON-DATA] §7.1, intended for IANA registration). A document is classified by its header, its root's leading
annotations and its root's tag, never the root's value — so a handler can classify from the opening of a document
without a full parse, past a schema's document-level `@doc` where it has one.

**Use the `.tn` extension, not `.tn1`**, matching tson-java. §7.1 now says this outright rather than leaving it
to be inferred: `.tn` is the extension of the 2026 revision series and makes no stability claim, while `.tn1` is
a positive claim of TSON version 1 stability that **MUST NOT** be used before that freeze. The rule has a sting
in its tail for this repo — renaming a document to `.tn1` at the freeze changes its identifying URI and so its
canonical identity, and references pinned during the revision series do not carry over. So the eventual freeze
is a re-pinning exercise across every `!!id`, `!!import` and constant here, not a rename.

**Immutability binds a *published* schema, and nothing here is published yet.** §3.5 makes a schema immutable
once it is available for someone else to pin — that is what the rule protects: a document that named it must go
on resolving. Until this project is released, **edit the file in place**: a project-owned name carries no
version (below), so there is nothing to bump. A release fixes each document under its identity.

**Once this *is* published**, the rule is the real one and the machinery is already here: a shape change is a new
identity beside the old, `TsonProblemSchema.publishedSources()` returns the whole history (one entry today) and
the demos publish all of it; `publishedById()` is the same keyed by identity, for a caller wiring a schema source
by hand. Two traps worth knowing:

- **A hand-wired source serving the current text at a superseded URI fails as an "identity mismatch"** from
  the loader — a long way from the map that was actually wrong. Use `publishedById()` even at one version.
- **Don't restate an identity in a document that could interpolate it.** The demos' own error schemas build
  their `!!import` from `TsonProblemSchema.ID`, so the next identity change is one constant rather than a file
  per demo.

**`TsonProblemSchemaTest` is what catches a stale `problem.diagnostic_code`**, and it is the only thing that does: an
upstream revision adding a code is otherwise invisible here until an error body emits one its own schema
rejects. It has caught every addition so far. Keep it.

**Project-owned schema `!!id`** follows tson-java's convention with this repo's own group:
`https://tson.io/2026/40/io/ltr8/http/<name>.tn` — `/2026/40` the spec revision, `io/ltr8` the publisher, `http`
the subsystem, `<name>` the schema, and **no version in the name**. That covers `problem.tn`, `deployment.tn`,
`service.tn` and the experiments' schemas. The demos' example application (`schemas.example.com/…/app/`)
is not project-owned and keeps its numbered names, deliberately: `order-1.tn` beside `order-2.tn` is what
`TsonSchemaVersions` exists to route, an application that has published two versions.

**A spec revision moves every identity in the repo**, this project's own and the bundled ones alike, and
it is one substitution across everything — `.tn` sources, Java constants, test fixtures, `README.md`,
`SCHEMA-HEADER.md`. Nothing derives the revision from a constant and nothing should: an identity is a literal in
a published document, which is exactly why `OrderServerTest.identitiesMatchTheConstants` holds the demo schemas'
literals to the Java constants rather than letting either side interpolate. So the check that the bump is
complete is that no `2026/<previous>` string survives anywhere, and then a green build — the bundled identities
move at the same time, so a missed one fails to resolve rather than resolving to something stale. The problem `type`
URIs under `https://ltr8.io/2026/<revision>/http/problems/` move with them. **A revision can move more than
identities** — the header's directives, the path a bundled name is written by — so the substitution is the floor and
the build is the check.


## Traps

- **`problem.tn`'s `problem.diagnostic_code` is a hand-written copy of `Diagnostic.Code`.** Nothing but
  `TsonProblemSchemaTest` checks it is current, and an error body emitting a code its own schema rejects would
  not otherwise be caught, since no fixture produces a code that is new. The Java enum is the source of truth —
  never check this schema against tson-cli's, which would only prove they drifted together.
- **An optional field is `name?: T`, and `name: T?` is not one.** [TSON-SCHEMA] §5.2 gives a field three
  slots: `?` on the *name* says the key may be omitted, `?` on the *type* says a written `_` is admitted, and
  `~`/`=` say what a value may be. So `detail: text?` is a field the document **must write** (as `_` if it has
  nothing), and reads exactly like the optional field it used to be — until a response omitting it comes back
  `FIELD_REQUIRED`. Every schema here spells optional as `name?: T`, following upstream's kernel. Two more that
  bite: a default needs the name marked (`page?: int32 ~ 1`; `page: int32 ~ 1` is refused), and a pin on an
  unmarked name (`status: int32 = 404`) is a **marker** the document must write and is never injected — only
  `status?: int32 = 404` injects. A restated field cannot move from required to optional (`x?: int32` over `x:
  int32` is refused), but it may take a default or a pin with its name marked (`x?: = 5` over `x: int32` loads and
  injects); and a restated field's type is the inherited one or a type IS-A it ([TSON-SCHEMA] §5.7, judged at link),
  so `int32` does not restate a choice holding it. A type written
  in place is IS-A nothing but itself and narrows only to an equal one, so `[int8]` does not restate `[int32]`; and a
  name never restates as its construction or the reverse: over `text_list => [text]`, a field `tags: text_list` is not
  restated `tags: [text]`. Bound values cannot tell the three optional-ish spellings apart; only a tree keeps whether `_` was
  written.
- **`^` follows `!name` and refines a built type by values; a record derives by `&` alone.** `status =>
  !integer ^ { min: 100  max: 599 }` narrows a type a constructor built, and `name ^ { … }` after a bare name is a parse
  error naming `name & { … }` ([TSON-SCHEMA] §5.5, §5.8). A composition body is field definitions, and it admits a new
  field where nothing refuses a misspelt restatement, so `operation & { statsu?: = 200 }` adds a field and the author
  checks a restated field's spelling.
- **A `text` field accepts any token, including `42`, `true` and `2026-01-01`.** It rejects only what is
  not a token at all — an array, a record. Correct per spec, and it reliably reads as a bug: [TSON-DATA]
  §4 says base type resolution does not apply at a schema-typed position, and §7.1's "form is not meaning"
  makes a type contract operate on the token's *text*, not on how it was written. A handler that needs
  string-ness says so with a `pattern`, not by assuming `text` means it. Pinned by
  `TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
- **A schema importing core reserves every root name of the bundled documents.** A name two documents of one closure
  declare is a collision wherever it is declared, whether or not anything names it, and core's closure holds the
  roots of `meta.tn`, `numeric.tn`, `temporal.tn` and `net.tn` and core's own annotations — `text`, `int64`, `schema`,
  `date`, `time`, `period`, `uuid`, `email`, `host`, `hostname`, `uri`, `uri_reference`, `media_type`, `title`,
  `comment` among them — and the map names `meta`, `atom`, `numeric`, `unicode`, `net`, `binary`, `temporal`. A schema
  importing `policy.tn`, as `deployment.tn` does, reserves the map name `policy` too. So an application type is
  never named after one, and a vocabulary this project publishes declares none: `service.tn` takes `media_type`
  from `net.tn` rather than declaring its own, since a description imports both it and core. The bundled roots are
  kept to what an author writes, so `record`, `identifier`, `field_name` and `kind` stay free. Core declares no sign
  bounds, no `non_empty_text` and no bare `identifier`, so a schema wanting one declares it — `non_empty_text =>
  !text ^ { min_length: 1 }`, `identifier => !unicode.identifier_type { continue_add: "-" }` — or writes
  `meta.identifier`. A schema naming one it never declared fails as *"field 'sku' has an unresolved reference
  'non_empty_text'"*, which reads like a typo in the schema and is a library upgrade. A vocabulary
  this project publishes therefore declares its entries under one map named for itself, as `policy.tn` does, so that
  it costs an importer one root name. `service.tn` nests every entry and annotation under `service => !schema { … }`,
  and an entry inside the map names another by its path, `exchange => service.signature & {}`, since a name resolves
  in the schema's one namespace: a bare `signature` there is an unresolved reference. A description then writes
  `!service.protocol` and `@service.safe`, and a bound class's `@Typename` is the qualified name. `problem.tn` nests
  under `problem` and `deployment.tn` under `deployment` the same way, so an error body is `!problem.details { … }`, a
  business error composes `problem.details`, and a descriptor is `!deployment.descriptor { … }`.

- **A `pattern` is I-Regexp, and `^`/`$` are not anchors.** The kernel pins `regex` to RFC 9485
  ([TSON-SCHEMA] §4.2, "pinned to I-Regexp"), whose match is against the **whole** token — so a pattern needs
  no anchors, and writing them adds two literal characters no token has. It fails in the worst way: the
  schema compiles, and every value is refused with *"'Latn' does not match the required pattern
  `^[A-Za-z][A-Za-z_]*$`"* — a message that reads like the value is wrong when the pattern is. Reflex from
  JSON Schema, which is unanchored and needs them. `service.tn`'s `service.header_name` and `service.path_template`
  carry patterns here. RFC 9485's classes are stricter than a host engine's: inside `[...]` a `-` is literal only
  first or last and `[`, `]` and `\` are escaped, while `{` and `}` are literal there and are escaped outside one,
  written `\\{` in a TSON string. A class of ASCII ranges is how a pattern says ASCII-exact, as `path_template`'s is.
- **A `pattern` judges the folded value, so the fold decides what an ASCII pattern admits.** [TSON-SCHEMA] §5.5 puts a
  text value into its type's `normalization` form first, and every facet, `pattern` included, judges the result.
  `NFKC_CASEFOLD` folds compatibility characters *into* ASCII, so under it a header name spelt with a fullwidth `C`
  (U+FF23) or a long s (U+017F) folds to a plain token and the RFC 9110 token pattern admits it: two names a reader
  takes for one header that no server does. `ASCII_CASEFOLD` maps `A` to `Z` and nothing else, the comparison HTTP field
  names, URI schemes and DNS names state for themselves, so the same spelling stays outside the pattern and is refused.
  `service.header_name` and `service.auth_scheme` use it
  (`TsonApiDescriptionTest.aHeaderNameIsFoldedInAsciiAndOneACompatibilityFoldWouldAdmitIsRefused`).
- **`abstract` is grammar, and it is what makes a base have no values of its own.** `endpoint => abstract
  service.placement & { … }` ([TSON-SCHEMA] §5.2, §12.1's `definition-mark`, as the kernel writes `type => abstract
  meta.top & { … }`) makes the resolver refuse an untagged value at an `endpoint`-typed slot, and a value tagged
  `!service.endpoint`, naming the subtypes — in every mode, bound or not. Binding the base to a sealed interface says the
  same fact on the Java side and no longer carries it alone. The mark is not inherited, so `operation`, `binding` and
  `document` stay open. A subtype nothing binds, at an abstract base's slot in a value entry, leaves the whole value a
  tree rather than being refused (`UPSTREAM.md`, "A subtype nothing binds, at an abstract base's slot in a value entry,
  leaves the value a tree"), which is why `TsonApiDescription` refuses a `service.http` entry that did not bind.

