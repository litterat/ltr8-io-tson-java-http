# Schemas this project owns: identity, media type, and the spellings that bite

The `!!id` convention, the `.tn` extension, immutability before and after publication, what a spec revision bump
touches, and the schema-language rules that read like bugs when they bite. Current form only.

**Invariants**

- Project-owned `!!id`: `https://tson.io/2026/38/io/ltr8/http/<name>.tn`, no version in the name. The demos' example
  application keeps numbered names on purpose.
- `.tn`, never `.tn1`. A revision bump is one substitution across every identity; the check is that no `2026/<previous>`
  string survives and the build is green.
- Nothing here is published yet, so a schema is edited in place. Once published, a shape change is a new identity.
- `problem.tn`'s `diagnostic_code` is a hand-written copy of `Diagnostic.Code`; `TsonProblemSchemaTest` is the only
  thing that catches it going stale. Keep it.
- An optional field is `name?: T`; `name: T?` is a required field admitting `_`. A `text` field accepts any token.
  Core declares only what a schema cannot do without. A `pattern` is I-Regexp and `^`/`$` are literal characters.

## Media type and file extension

`application/tson`, optionally `application/tson; version=1` where HTTP-context disambiguation is needed
([TSON-DATA] §7.1, intended for IANA registration). A document's own header determines whether it is a
data or a schema document, in at most two directives of lookahead and with no value parsing — so a
handler can classify from the opening bytes without a full parse.

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

**`TsonProblemSchemaTest` is what catches a stale `diagnostic_code`**, and it is the only thing that does: an
upstream revision adding a code is otherwise invisible here until an error body emits one its own schema
rejects. It has caught every addition so far. Keep it.

**Project-owned schema `!!id`** follows tson-java's convention with this repo's own group:
`https://tson.io/2026/38/io/ltr8/http/<name>.tn` — `/2026/38` the spec revision, `io/ltr8` the publisher, `http`
the subsystem, `<name>` the schema, and **no version in the name**. That covers `problem.tn`, `deployment.tn`,
`meta-service.tn` and the experiment's meta layers. The demos' example application (`schemas.example.com/…/app/`)
is not project-owned and keeps its numbered names, deliberately: `order-1.tn` beside `order-2.tn` is what
`TsonSchemaVersions` exists to route, an application that has published two versions.

**A spec revision moves every identity in the repo**, this project's own and the three bundled ones alike, and
it is one substitution across everything — `.tn` sources, Java constants, test fixtures, `README.md`,
`SCHEMA-HEADER.md`. Nothing derives the revision from a constant and nothing should: an identity is a literal in
a published document, which is exactly why `OrderServerTest.identitiesMatchTheConstants` holds the demo schemas'
literals to the Java constants rather than letting either side interpolate. So the check that the bump is
complete is that no `2026/<previous>` string survives anywhere, and then a green build — the three bundled
identities move at the same time, so a missed one fails to resolve rather than resolving to something stale.


## Traps

- **`problem.tn`'s `diagnostic_code` is a hand-written copy of `Diagnostic.Code`.** Nothing but
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
  `status?: int32 = 404` injects. A refinement cannot move a field from required to omissible, so a base whose
  subtypes should inject a pin has to declare the name marked. Bound values cannot tell the three
  optional-ish spellings apart; only a tree keeps whether `_` was written.
- **A `text` field accepts any token, including `42`, `true` and `2026-01-01`.** It rejects only what is
  not a token at all — an array, a record. Correct per spec, and it reliably reads as a bug: [TSON-DATA]
  §4 says base type resolution does not apply at a schema-typed position, and §7.1's "form is not meaning"
  makes a type contract operate on the token's *text*, not on how it was written. A handler that needs
  string-ness says so with a `pattern`, not by assuming `text` means it. Pinned by
  `TsonHttpCodecTest.aTextFieldAcceptsAnyTokenButNotAContainer`.
- **Core declares only what a schema cannot do without**, because every name it declares is reserved in each
  schema importing it (§2.2.3). `identifier`, `positive_integer`, `non_negative_integer`, `non_empty_text` and
  the sign bounds are gone, so a schema wanting one declares it — `non_empty_text => !text ^ { min_length: 1 }`,
  `identifier => !identifier_type { continue_add: "-" }`. A schema still naming one fails as *"field 'sku' has an
  unresolved reference 'non_empty_text'"*, which reads like a typo in the schema and is a library upgrade.

- **A `pattern` is I-Regexp, and `^`/`$` are not anchors.** The kernel pins `regex` to RFC 9485
  ([TSON-SCHEMA] §4.2, "pinned to I-Regexp"), whose match is against the **whole** token — so a pattern needs
  no anchors, and writing them adds two literal characters no token has. It fails in the worst way: the
  schema compiles, and every value is refused with *"'Latn' does not match the required pattern
  `^[A-Za-z][A-Za-z_]*$`"* — a message that reads like the value is wrong when the pattern is. Reflex from
  JSON Schema, which is unanchored and needs them. `deployment.tn`'s `script_name` is the one pattern here.

