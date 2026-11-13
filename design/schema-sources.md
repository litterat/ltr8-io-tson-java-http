# Schema sources: identity is not location

The single most important thing to understand before touching the schema source: a reference names a document and
does not say where to get it. `allowHost` is the security boundary, `mapHost` is the transport. Current form only.

**Invariants**

- Canonical identity is lowercase host plus path ([TSON-DATA] §2.2.1). A reference may not carry a port, userinfo or a
  fragment, so `mapHost` is the only way to reach a non-default port.
- Policy is checked on every reference, cached or not. A cache hit skips the network, never the allow-list.
- Use `SchemaSource.ofMap`, never `map::get`: it refuses a miss as `NOT_PERMITTED` and compares by canonical identity.
- Never `computeIfAbsent` on a schema cache; `HttpSchemaSource` uses get-then-put.
- A request body names its schemas in its header (`!!import`) and nowhere else. A directive inside a value is a parse
  error, so no value can make the server fetch.
- The boundary admits schemas; it does not judge their kind. An admitted schema may declare constructors and namespace
  types.
- `HttpSchemaSource` lives upstream. The mapping of its failures to statuses is `design/status-classification.md`.

## Identity is not location

The single most important thing to understand before touching the schema source. [TSON-DATA] §2.2.1:

> A reference's **canonical identity** is … **lowercase host plus path** … The scheme is a *transport hint*,
> not part of the name … a consumer **MAY fetch by whichever scheme its policy allows** … an identifying URI
> MUST already be in canonical form apart from scheme and hash query — lowercase host, no userinfo, **no port
> (default or otherwise)**, no percent-encoding of unreserved characters, no dot-segments, and no fragment.

So a schema reference names a document; it does not say where to get it. Two separate questions, configured
separately: `allowHost` decides which schema *names* this server will load (the security boundary), and
`mapHost` decides where the bytes come from. **The no-port rule means `mapHost` is the only way to reach a
non-default port** — an ephemeral test port, an internal endpoint, a mirror — because such a URL can never be
an identity. A mapping renames nothing: the loader still cross-checks the fetched document's `!!id`.

Getting this backwards produces a confusing failure a long way from its cause: a port-carrying `!!import`
fails inside `CanonicalIdentity.canonicalize` during resolution, with a message about identity and a
stack trace through the resolver.

## What the boundary does not refuse

A schema the allow-list or the fixed map admits may declare constructors and namespace types, as any schema may
([TSON-SCHEMA] §2.2.3 allows no hiding and no rebinding, so such a declaration adds an entry and changes nothing
existing), and nothing here refuses it: the boundary is which schemas are admitted and the limits they are read under
(§11.5), not what kind of schema they are. A check would refuse this project's own `meta-service.tn`, which declares
namespace types; should a harm ever be identified, whether a linked schema declares a constructor is derivable from its
entries (`TsonLinkedSchema.isConstructor`, `originOf`).

## Where a body can name a schema

A schema reference in a request body is an untrusted URL, and the body's header is the one place it can stand: a
data document's `!!import`s bind it, several read under one namespace ([TSON-SCHEMA] §7.1). Each is resolved through
the configured `SchemaSource`, so the allow-list sees every one.

**A directive inside a value is a parse error**, outside [TSON-DATA]'s placement for directives. A body writing
`{ attachment: !!import:"…" !order {…} }` is a 400 `VALIDATION_ERROR`, *"directive '!!import' is not permitted
here"*, and nothing is fetched first: `TsonHttpSchemaSourceIntegrationTest.`
`aDirectiveInsideAValueIsABadRequestAndFetchesNothing` points the value's reference at a source that permits nothing,
so a fetch attempted before the refusal would surface as `SCHEMA_NOT_PERMITTED` instead. A value tagged at a position
typed `meta.declared` names a type of the closure the header bound, and nothing outside it.


## Traps

- **A schema reference may not carry a port, userinfo or a fragment** (§2.2.1), so a schema origin cannot run
  on a non-default port. Use `mapHost`. See "Identity is not location" above — this is the trap that costs the
  most time, because the failure surfaces from the resolver rather than from the fetch.

- **Use `SchemaSource.ofMap`, never `map::get`.** `SchemaFetchException` is the whole contract for
  "cannot supply this", and a source returning `null` is now refused by name rather than dereferenced — but
  refused is still a failure, and `ofMap` is the form that does not fail: it throws `NOT_PERMITTED` for a miss —
  the map is this deployment's whole configuration, so a schema outside it is one it will not supply — and
  compares by **canonical identity**, so a reference differing only in scheme or `?sha256=` pin still resolves
  (§2.2.1). A hand-rolled map source gets the first half and misses the second. All three demos shipped
  `schemaSource(schemas::get)`, which was an NPE — and so a 500 any client could produce at will, by naming a
  schema nobody publishes. Pinned by `UpstreamGapsTest.ofMapRefusesAMissAndComparesByCanonicalIdentity` and,
  in all three adapters, by `OrderServerTest.aDocumentNamingAnUnknownSchemaIsTheSendersMistake`.
- **Never `computeIfAbsent` on a schema cache.** It holds a `ConcurrentHashMap` bin lock for the whole of a
  network fetch, blocking every other thread whose key lands in that bin — and stalling a resize — for as long
  as the timeout allows. `HttpSchemaSource` uses get-then-put; two threads racing one identity fetch it
  twice and store identical content, which costs a request and breaks nothing.

  *Not* because the loader is re-entrant: it isn't. It fetches a document, returns, and only then resolves and
  fetches its imports, so `fetch` is never called from inside `fetch` (measured — max depth 1). Kept here
  because the reasoning was worked out in this repo and reads as a bug to anyone who meets get-then-put cold;
  the code and its concurrency test are upstream's now, so this is a rule to know rather than one to maintain.
  An earlier version of this note claimed the loader was re-entrant.
- **Policy is checked on every reference, cached or not.** A cache hit must skip the network, never the
  allow-list — otherwise a schema fetched for one request becomes fetchable for a request that would have been
  refused.

