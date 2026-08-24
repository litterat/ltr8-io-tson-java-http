# Naming the governing schema in an HTTP header

A proposal, written up for the spec author. **Implemented in this repo** — see §6 for what it took.

**Decided:**

- **A schemaless body stays valid TSON over HTTP.** The requirement is endpoint policy, not a format rule: *a
  schema-governed endpoint MUST reject a document naming no schema.* Class 1 remains sendable as
  `application/tson`.
- **The field is `TSON-Schema`, and its value is an RFC 9651 sf-string** — quoted. Which matches `!!schema`,
  whose argument must also be quoted, a URI being outside the unquoted-token profile (§7.1). One rule, both
  ends.

- **The header and the directive may both appear, and must then agree** (§3.2). A message can be routable and
  self-contained at once.
- **The header is defined for any body, not only `application/tson`** (§3.3) — which is what gives a JSON
  payload a way to name its governing schema at all.

**The rules, settled:**

1. `TSON-Schema` is an RFC 9651 structured field: an Item whose bare-item is an **sf-string** carrying a schema
   reference. Quoted, always.
2. It may appear on a request or a response, and on a body of any media type.
3. If the body carries `!!schema` and the header is present, their **canonical identities** ([TSON-DATA] §2.2.1
   — scheme and any `?sha256=` pin do not count) MUST be equal. A mismatch is an error, never a precedence
   question.
4. Where the body cannot carry a directive — JSON — the header is the only channel and is authoritative. Rule 3
   simply has nothing to compare against.
5. A body naming no schema by either channel is schemaless (Class 1) and still valid TSON. **A schema-governed
   endpoint MUST reject it**; that is endpoint policy, not a property of the media type.

## 1. What it is, and why the case got stronger

A header carrying the identity of the schema that governs the message body:

```
TSON-Schema: "https://schemas.example.com/2026/32/app/order-1.tn"
```

**It is a projection of `!!schema`, not an alternative to it.** That framing is the whole proposal. The body
directive stays the format's own mechanism; the header exists so that things which cannot or should not parse a
body can still act on what governs it.

The case for it was weak when this project first considered it, and two things changed:

- **`describing()` landed**, so a TSON response now names its own schema in-band. That removed one of the two
  original arguments outright — and removed it *better* than a header would have.
- **Version routing was built**, which produced a stronger argument than either original one. See §2.

## 2. Routing is the argument

Serving `order-1.tn` and `order-2.tn` side by side means deciding which one governs a request before deciding
how to read it. Inside one process that is fine. Across two servers — the deployment this was raised for — it
is a job for a gateway, and:

- **nginx, Envoy, API gateways and CDNs route on headers and paths. None of them parse bodies.** Body
  inspection at that layer is a layering violation before it is anything else.
- **`Content-Encoding: gzip` makes it impossible.** Routing on a directive inside a compressed body means
  decompressing to route. The same goes for any payload encrypted above the transport.
- **It forced a lexer fragment into this project.** `TsonDocumentPeek` exists only because routing needs the
  schema before the read, and nothing public answers that (`UPSTREAM.md` #9). It is a second, partial
  implementation of something the real lexer already does, with a standing rule that it must never guess. A
  header removes the need for it in the routing path.

**Honest limit: a header does not save the origin server from peeking.** If header and body can disagree, the
endpoint must still read the directive to check. The saving is at the network, not at the server — except for a
JSON body, where the header is the only possible source and there is nothing to check against.

**Precedent.** CloudEvents does exactly this. `dataschema` is a context attribute, and the HTTP binding maps
every context attribute to a `ce-`-prefixed header in binary content mode — so `ce-dataschema` carries a schema
URI in a header precisely so intermediaries can handle a message without opening it.

## 3. The rules — and the one that needs a decision

As proposed:

1. If the header is present, the body MUST NOT carry `!!schema`.
2. Both directions: requests and responses.
3. If the header is absent, then for `application/tson` the `!!schema` directive MUST be present.
4. If both are present they MUST match, or it is an error.

Rules 2 and 4 are straightforwardly right. Rule 4 matches what [TSON-DATA] §2.2.1 already does for conflicting
content hashes — *"a consumer that observes both MUST report an error rather than choosing between them"* — and
matching should be by canonical identity, so scheme and a `?sha256=` pin do not count as a difference.

### 3.1 Rule 3 — DECIDED: endpoint policy, not a format rule

*Superseded: a schemaless body stays valid TSON over HTTP; a schema-governed endpoint rejects one that names no
schema.* The reasoning is kept because it is the argument for where the rule belongs.



Rule 3 makes every `application/tson` payload schema-governed. Class 1 — schemaless TSON, which Part 1 defines
at length and a Class 1 processor is required to handle — becomes unsendable over HTTP, because there is no
other media type to send it as.

That may be intended. It has a real virtue: a client that forgets both the header and the directive gets
rejected rather than silently accepted unvalidated, which is the same class of hazard as the version-mismatch
silent drop this project just found (`UPSTREAM.md` #10).

**But it makes the same bytes mean different things depending on transport**, which is the kind of discrepancy
that surfaces years later. Absence already has a meaning in TSON — schemaless — and redefining it at the HTTP
layer creates two answers to one question.

**Suggested instead:** leave the format alone and put the requirement where the safety actually matters — *a
schema-governed endpoint* MUST reject a document naming no schema. That is endpoint policy, it is enforceable
today (`TsonSchemaVersions.route` already does exactly this), and it gets the protection without making valid
TSON unsendable.

### 3.2 Rule 1 — DECIDED: both may appear, and must agree

*The reasoning below is kept because it is the argument for the decision.*



Mutual exclusion guarantees no conflict can exist. It also means a message can be routable *or*
self-contained, never both:

- Header only → an intermediary can route it; the body, once logged, stored, replayed or forwarded, no longer
  says what governs it. This is the property `describing()` was just added to provide.
- Directive only → self-contained; unroutable by anything that will not parse it.

**Decided: permit both, and require them to match.** The duplication is about sixty bytes and buys routability
and self-containment at once. HTTP already does this — `Content-Length` duplicates information derivable from
the body, for the same reason: so that something which has not read the body yet can act.

The counter-argument is real and worth weighing: a match *rule* can be skipped by a lazy implementation, where
mutual exclusion cannot. If the priority is that no conflict is ever possible anywhere, exclusion is the only
way to get it.

If exclusion is kept, the "producers MUST NOT send both, consumers MUST reject a mismatch" shape is the usual
one — but note its known cost: a consumer that accepts a matching pair means non-conformant producers never
find out they are wrong.

### 3.3 DECIDED: the header is defined for any body, not only `application/tson`

Not in the original rule set, and it is where the header earns the most.

A JSON body cannot carry `!!schema` at all (§6 makes it valid TSON; TSON directive syntax is not valid JSON).
So for `Content-Type: application/json`, the header is not a projection of anything — it is the **only**
channel, and the only way a JSON payload can say which TSON schema governs it. That is the structured-output
case the format is aimed at.

Defined only for `application/tson`, that case would have no answer and implementations would each invent one.
Defined for any body, §3.2's matching rule simply never applies to JSON — there is nothing in the body to match
against — and the header is authoritative by necessity rather than by choice.

A consequence worth stating in the spec: this makes `TSON-Schema` the JSON-compatibility story's missing half.
§6 says every JSON document is valid TSON; this is how one says which schema it is valid *against*.

## 4. Naming, and how one is meant to be named

There is a defined procedure, and three RFCs govern it.

- **RFC 9110 §16.3** — the "Hypertext Transfer Protocol (HTTP) Field Name Registry". §16.3.1 is the
  registration procedure; §16.3.2.1 covers choosing a name. Registration can be *provisional* (expert review
  only) before anything is finalised, which suits a format that is still a working revision.
- **RFC 6648** — *Deprecating the "X-" Prefix and Similar Constructs*. `X-TSON-Schema` is out; this is a BCP,
  not a style opinion.
- **RFC 9651** — *Structured Field Values for HTTP*, which obsoletes RFC 8941. A new field SHOULD be defined as
  a structured field so generic parsers, proxies and routers handle it without bespoke code. See §5.

**Candidates:**

| Name | For | Against |
|---|---|---|
| `TSON-Schema` | Specific; obviously scoped; one name for the same concept as `!!schema`; registerable without claiming general territory. Reads correctly for a JSON body too — it names a *TSON schema*, whatever the body is. | Needs registration (as does any of these). |
| `Content-Schema` | Pleasing symmetry with `Content-Type`; format-neutral, so it could carry a JSON Schema URL too. Semantically it *is* representation metadata, which is what `Content-*` means. | Claims a general-purpose name for a whole-industry concern — a much bigger ask at registration, and a much bigger thing to get wrong. |
| `ce-dataschema` | Already exists and is already routed on by CloudEvents-aware infrastructure. | The `ce-` prefix means "this message is a CloudEvent", which a plain TSON request is not. Borrowing it misrepresents the message. |

**DECIDED: `TSON-Schema`.** The spec already intends to register `application/tson` with IANA, so registering a
field name alongside it is coherent rather than extra machinery.

## 5. Syntax — and a trap that only shows up later

Define it as an RFC 9651 structured field: an **Item** whose bare-item is an **sf-string**.

```
TSON-Schema: "https://schemas.example.com/2026/32/app/order-1.tn"
```

**DECIDED: sf-string, so the quotes are mandatory.** Which also matches the directive: `!!schema`'s argument
must be quoted too, because a URI contains `:` and `/` and falls outside §7.1's unquoted-token profile. Same
rule at both ends, for the same reason.

**And the quotes are load-bearing in a way testing will not tell you.** RFC 9651's `sf-token` production is
`( ALPHA / "*" ) *( tchar / ":" / "/" )` — which an unpinned `https://` URL satisfies completely, since every
character in it is a tchar or `:` or `/`. So an unquoted URL parses fine as a token, and a header defined
loosely will work in every test anyone writes.

Then someone pins a schema. `?sha256=…` contains `?` and `=`, neither of which is a tchar, and the unquoted
form stops parsing — for exactly the references §2.2.1 encourages as the strongest integrity control. A pinned
reference in this header is not a corner case, either: it is the same reference the body would have carried, and
the whole point of allowing both is that they are the same string.

Mandating sf-string from the start avoids this entirely. It is one sentence in the spec and a class of bug that
never happens.

## 6. What it took

- **`TsonSchemaHeader`** — the field name, a strict sf-string parse and format, and `resolve(body, fieldValue)`,
  which reads both channels and enforces rule 3. Everything about the header in one place.
- **`TsonSchemaVersions.route(body, fieldValue)`** — routes on whichever channel names a schema.
- **`TsonHttpCodec.acceptingJson()`** — a derived codec that also admits `application/json`. Opt-in, because
  "reads TSON" and "reads JSON" are different promises and an endpoint wanting only the first should go on
  answering 415 (it does, by default, and a test pins that).
- **`TsonDocumentPeek` stays.** Verification needs it, and so does any endpoint the header does not reach.

**A JSON body is now readable, which it never was before.** The header names the schema; the root type comes
from the route, because a JSON body carries no type-ref either — the same two-part requirement as writing a
self-describing document, for the same reason. `{"sku": "ABC-1", "quantity": 3, "currency": "AUD"}` posted with
a `TSON-Schema` header validates against `order-2.tn`, and an incomplete one comes back 400 with every
diagnostic. Before this, JSON appeared in this repo only as something to answer 415 to.

**The honest limit held.** `route` still peeks: a header cannot be trusted over a directive that contradicts
it, so verification costs the same read it always did. What the header buys is that whatever routed the request
*here* — a gateway, which will not parse a body and cannot parse a compressed one — never had to.

## 7. Its companion: `TSON-Accept-Schema`

**Built, and the reason it is a second field rather than a second meaning.** `TSON-Schema` says what the body
of *this* message is — the schema layer's `Content-Type`. A client also needs to say which versions it can
read **back**, which is the schema layer's `Accept`. HTTP keeps those apart because one message routinely asks
both at once: a POST sending a v1 order and wanting a v2 confirmation is the ordinary case, and a single field
cannot carry both.

Overloading `TSON-Schema` is tempting on a `GET`, where there is no body for it to describe and the meaning
would be unambiguous by vacuity. That yields a field whose meaning depends on the method — worse than two
fields, and something HTTP field semantics avoid.

**Why negotiation is needed at all, when reading is not negotiated.** A request body names the schema that
governs it, so reading obeys the document. A response has no such anchor, and a `GET` carries no request body
to hold one. Something has to say which version the reply is in, and only the client knows what it can read.

**The field.** An RFC 9651 sf-list of sf-strings, each optionally carrying `;q=` — the same shape and meaning
as `Accept`'s quality values:

```
TSON-Accept-Schema: "https://schemas.example.com/2026/32/app/order-2.tn",
                    "https://schemas.example.com/2026/32/app/order-1.tn";q=0.5
```

The rules, each pinned by a test in `TsonSchemaVersionsTest`:

1. **Absence means the server chooses**, as `Accept`'s absence means "anything" — normally its newest version.
   This is what makes the field additive: a client that has never heard of it keeps working unchanged.
2. **Quality orders the choice**, and the client's own order breaks a tie.
3. **`q=0` refuses a version** rather than ranking it last.
4. **A version the server does not serve is ignored**, not fatal — refusing the whole field for one unknown
   member would deny the client its other choices.
5. **Matching is by canonical identity** (§2.2.1), so a different scheme or a `?sha256=` pin still matches.
6. **Nothing acceptable is `406`**, never a fallback. Answering in a version the client said it cannot read is
   worse than refusing: it hands back a body that will fail to parse, under a status claiming success.
7. **A malformed field is `400`**, not silently "any". A client that meant to constrain the answer and
   mistyped should hear about it rather than receive whatever the server preferred.

**The reply needs no new field.** It names what was chosen in its own `TSON-Schema` and in the body's
`!!schema`, so a response stays self-describing — `Accept` → `Content-Type`, exactly.

**What is still open**, and is the reason this is a note rather than a proposal: how a client learns which
versions exist before asking. An error naming them is the honest floor, and any advertisement can be stale
under a rolling deploy, so the error path is load-bearing whatever else is added. That thread is parked.

## 8. What this should not become

A way to validate a document against a schema its author did not choose. The header says what the *sender*
claims governs the body. It is not an instruction to the receiver to apply a schema of the receiver's choosing
to an unmarked document — that is how a payload gets interpreted under a contract nobody agreed to. If an
endpoint wants to impose a schema on an unmarked body, that is `readTreeAs`/`readObjectAs`, which is explicit,
local, and already exists.
