# The JSON encoding over HTTP

How a codec built `acceptingJson()` reads and writes [TSON-JSON] bodies: what it admits, which reader judges it, what a
JSON body cannot name, how a response is negotiated, and the shape of a problem written as JSON. Current form only.

**Invariants**

- JSON is admitted only where the endpoint opted in, read by tson-java's `tson-json` built from the codec's own `Tson`,
  so one policy and one `DataBindContext` judge both encodings.
- A JSON body names neither its schema nor its root type: the schema comes from `TSON-Schema` and the root type from
  the route. A JSON body is never peeked and never read into a `TsonValue`.
- JSON `null` is `_`: refused at `a?: T`, which is how every optional field here is spelled.
- A tie in `Accept` goes to TSON. A `TsonValue` or pre-encoded bytes fall back to TSON or are a 406.
- A JSON response names its schema in the header (`respondDescribed`); a problem written as JSON is
  `application/problem+json` where the client admits that type.
- JSON readers compile on the first read that names a schema, on a request thread; nothing is resolved there.

**JSON is read by the JSON reader and written by the JSON writer, and only where the endpoint opted in.** TSON is
JSON-*like* and **is not a JSON superset** ([TSON-DATA] §6); JSON is a second encoding of the same model, defined by
**[TSON-JSON]** (spec Part 3) with its own media type, `application/tson+json`. `acceptingJson()` admits that,
`application/json` and any other `+json` type, and reads all of them with tson-java's **`tson-json`**. The
`Json` it uses is built from the codec's own `Tson`: the same processor policy, `DataBindContext` and
registered schemas, so a class binds identically under both encodings and a body is judged by one policy
whichever carried it (`TsonHttpCodecJsonTest.theJsonReaderIsJudgedByTheTsonPolicy` shows a TSON-side nesting
bound answering a JSON body 413). **It compiles a schema's JSON readers on the first read that names it** —
on a request thread, the one departure from resolving everything at startup, since `Json` has no entry for
compiling ahead. What makes it tolerable is that nothing is *resolved* there (the schema is already the
`Tson`'s) and the compiled cache is safe for concurrent reads; `TsonHttpCodecConcurrencyTest.`
`bindsJsonAndTsonConcurrentlyFromColdStart` races that first compile rather than assuming it. Three rules come
with it:
- **A JSON body names neither its schema nor its root type, as far as this reader goes.** Part 3 §3.4 has two
  routes and tson-java builds only the out-of-band one, so the schema comes from the `TSON-Schema` header
  and the root type from the route: `readObjectAs`/`readJsonTreeAs`. The in-band route — a root object
  carrying `$schema` and `$type` — is *refused* by the reader today, so a spec-valid body naming its binding
  in agreement with the header is a 400. `UPSTREAM.md` carries it and
  `UpstreamGapsTest.aJsonDocumentsInBandBindingIsRefused` fails the day it lands, which is when the codec
  should start checking §3.5's agreement itself.
- **A tree is the encoding's own model.** Bind mode reads either encoding through the same methods; tree mode
  does not, because a `TsonValue` is the TSON reader's tree. A JSON body is read into a `JsonValue` with
  `readJsonTree`/`readJsonTreeAs`, and a JSON body handed to a `TsonValue` read is an `IllegalStateException`
  — the route's fault, a 500, never a 415 blaming a client that sent what the endpoint admits.
- **A JSON body is never peeked.** A `TsonDocumentPeek` is the TSON reader's continuation of a header, so a
  JSON body reaching a peek-taking read is refused the same way. `TsonSchemaVersions.route` knows this: given
  the `Content-Type`, it routes a JSON body on the header alone and hands back its stream, and `Routed.body()`
  — the peek — refuses to exist for one.

**JSON `null` is the absent sentinel, and this repo's optional fields refuse it.** In the JSON encoding `null`
is how `_` is written, so it is read on §5.2's terms: absence at a voidable field (`a?: T?`, `a: T?`),
refused at `a?: T`, which is how every optional field here is spelled — omit it instead. A client writing
`null` for such a field gets a 400, correctly; if a public schema should take one, spell the field `a?: T?`.

**The write side is negotiated, and has three rules of its own.** `negotiate` offers `application/tson`,
`application/tson+json` and `application/json` on a codec built `acceptingJson` (TSON alone otherwise) and
the client's highest quality wins; **a tie goes to TSON**, the encoding a response can name its schema in, so
`*/*` or no `Accept` at all still gets self-describing TSON. Then:
- **A JSON response names its schema in the header, because it cannot in band.** tson-java's JSON writer is
  class-directed and JSON has no directive syntax, so `respondDescribed(status, value, schemaUri, rootType)`
  — on all three adapters — sets `TSON-Schema` in both encodings and adds `!!schema` only to TSON. Prefer it
  to `respondBytes(codec.write(value, schema, root))`, which can only ever be TSON.
- **A response only TSON can carry falls back to TSON, or is a 406.** A `TsonValue` is the TSON encoding's
  tree and `respondBytes` holds bytes the caller encoded, so both are sent as `application/tson` wherever the
  client accepts it *at all* — a server may send any representation the client accepts — and are a 406
  where it accepts none. `Representation.requireTson()` is that rule, applied before the response commits so
  the 406 is still a problem body. It is also why a schema route answers a JSON-only client 406: a schema
  document is TSON text in every encoding.
- **A problem names `problem.tn` in the `TSON-Schema` header**, in both encodings — the only place a JSON
  one can, and true of a TSON one too. `respondProblem` sets it on all three adapters.
- **A problem written as JSON is `application/problem+json`.** `problem.tn`'s `problem` is RFC 9457's five
  members plus `errors`, an extension member, and the JSON writer omits an absent member rather than writing
  `null` — so its JSON encoding *is* an RFC 9457 body. It is labelled `application/problem+json` where the
  client's `Accept` admits that type, and with the negotiated JSON type where it does not, so a client that
  asked only for `application/json` is not answered in a type it never named.

A failure from a *plain* route (`install` on Javalin and Helidon) negotiates too, and falls back to TSON
where nothing is acceptable: the failure being reported is not a 406, and the client is still owed a body.

