# Identity: people, agents, and what an agent may do on someone's behalf

**Status: future-dreaming, written as a research map.** Where to start reading, and where TSON's existing properties
could help. Nothing here is designed or planned, and nothing in this project waits on it. It sits beside
[`COMPUTATION.md`](COMPUTATION.md): if programmable middleware runs agents' plans, something has to say whose plans they
are and what they are allowed to do.

## The problem, split into its parts

LLM-backed bots are becoming indistinguishable from people, and legitimate agents increasingly act *for* people. Telling
the two apart, and holding an agent to what its person allowed, is several distinct problems that are often run
together:

| part | the question | example |
|---|---|---|
| **authentication** | who is this principal? | the person, or the organisation |
| **personhood** | is there a unique real human behind it, without saying which? | one account per person, unlinkable |
| **delegation** | may this agent act for that principal, and how far? | "book travel under $500 until Friday" |
| **attestation** | what software is acting? | which agent, which model, which version |
| **provenance** | who asserted this content or value? | a signed claim, a content credential |
| **accountability** | can it be audited and revoked afterwards? | a log of grants and actions |
| **privacy** | can each of the above be proved while revealing as little as possible? | selective disclosure, unlinkability |

Most failures in this area come from solving one row and assuming the rest. An API key authenticates a caller and says
nothing about whose behalf it acts on. A personhood credential proves a human exists and says nothing about what their
agent may do.

## Where to start reading

### Delegation and capabilities

The most directly useful area, because "an agent acting on behalf of a person" *is* delegation.
- **OAuth's delegation extensions:**
  - RFC 8693, Token Exchange: its `act` claim records a chain of actors acting on behalf of a subject;
  - RFC 9396, Rich Authorization Requests: structured, typed `authorization_details` instead of flat scope strings;
  - RFC 9449, DPoP: binding a token to a key the caller proves it holds;
  - RFC 9635, GNAP: a ground-up redesign of grant negotiation.
- **Capability tokens, which attenuate:** a holder can narrow a token and pass it on, but never widen it.
  - Macaroons (Google, NDSS 2014): chained caveats added by HMAC;
  - Biscuit: attenuable tokens whose caveats are Datalog;
  - UCAN: delegation chains between decentralised identifiers;
  - ZCAP-LD: the W3C Credentials Community Group's authorization capabilities.
- **Object-capability foundations:** Mark Miller's "Robust Composition" and the E language. Newspeak (`SLOTS.md`) is the
  same principle in a language.

### Decentralised identity and credentials

- **W3C Decentralized Identifiers** (DIDs) and the **Verifiable Credentials Data Model 2.0**: identifiers that resolve
  to keys, and signed claims about a subject that a holder presents.
- **Selective disclosure:** IETF SD-JWT (salted digests per claim, revealed one by one); BBS signatures, an IRTF CFRG
  draft, for disclosure that also can't be linked across presentations.
- **Presentation and issuance protocols:** OpenID for Verifiable Presentations, and OpenID for Verifiable Credential
  Issuance.
- **Deployed wallets:** ISO/IEC 18013-5 mobile driving licences; the EU Digital Identity Wallet under eIDAS 2.0 and its
  Architecture Reference Framework.

### Personhood

- **"Personhood credentials"** (Adler et al., 2024): the argument for privacy-preserving credentials that distinguish
  real people online, written with AI's effect on the internet as its motivation.
- **Privacy Pass** (RFC 9576–9578): anonymous tokens attesting that a trusted issuer vouched for a client, without
  identifying it; deployed as Apple's Private Access Tokens.
- **Proof-of-personhood research and systems:** pseudonym parties (Ford and Strauss), and biometric approaches such as
  World ID, with the privacy debates around them.

### Agents specifically

Newer, moving fast, and worth re-checking before relying on any of it:
- **Web Bot Auth** (Cloudflare's proposal, taken to the IETF): a bot signs its HTTP requests with RFC 9421 HTTP Message
  Signatures, using a key published in a directory, so a site can tell a declared bot from an impostor.
- **Signed user mandates:** Google's Agent Payments Protocol (AP2) proposes verifiable credentials in which a user
  states the intent an agent is allowed to act on.
- **Agent directories:** A2A's agent cards, and the "know your agent" idea generally.
- **MCP's authorization,** which builds on OAuth 2.1, and the discussion around delegating it to agents.

### Provenance, accountability and attestation

- **Content provenance:** C2PA content credentials, signed manifests travelling with media.
- **Transparency logs:** Certificate Transparency (RFC 6962, RFC 9162); Sigstore's Rekor; the IETF's SCITT working group
  (signed statements registered with a transparency service). An append-only log is how a grant, or an action taken
  under one, becomes auditable afterwards.
- **Remote attestation:** the IETF RATS architecture (RFC 9334), for evidence of what software is running.

### Signing structured data

The underrated part. A signature covers bytes, and structured data has many byte spellings.
- RFC 8785, JSON Canonicalization Scheme; deterministic CBOR; W3C Data Integrity's RDF canonicalization.
- COSE (RFC 9052) and JOSE/JWS as signing envelopes.

## Where TSON could help

### 1. A canonical value form, so a signature survives encoding

TSON already has canonical identity and hash pins over a document's bytes (§2.2.1). What it lacks for signing is a
canonical form for a **value**, independent of formatting. Because TSON and its JSON encoding (Part 3) are two
encodings of one model, a canonical form defined over the model would let a value be signed once and verified in either
encoding. JCS can't offer that, since it is JSON-only.

### 2. Authorization as schema refinement

The most distinctive idea here. A delegation that narrows what an agent may send can be written as a **refinement of
the request type**:

```
place_order_request      => { order: order }
delegated_place_order    => place_order_request ^ { order: order ^ { quantity: int32 ^ { max: 5 } } }
```

- The agent's request must validate against the refined type. That's a check the validator already makes.
- Refinement only narrows: [TSON-SCHEMA] §5.7, "refinement can only restrict", checked facet by facet (a lower bound
  may rise, an upper bound may fall, a step may only tighten to a multiple). So **attenuation holds by construction**,
  the property capability tokens work hard for. A further delegation refines the refinement, and can only narrow again.
- Macaroon and Biscuit caveats are predicates outside any type system. Refinements are typed, compose by the existing
  rules, and are checked by the same code that checks every document.

What a refinement can't express (one field compared with another, a total across a list) is where `COMPUTATION.md`'s
total-expression layer would come in.

### 3. Approving an exact plan

An agent plan (`agent.tn`) is verified before it runs, its return contract is known in advance, and it is
content-addressed. So **a person can approve one exact plan by its hash**, and the server runs only that plan. This is
the human-in-the-loop step most agent systems lack: approve this specific sequence of calls, not "the agent".

Admission can then check a plan against a delegation as well as against the interface:
- every call's method is within the grant;
- every request validates against the delegated refinement;
- the budget is within the delegated budget;
- data-flow rules hold, such as "the output of `read_email` may not reach `send_email.to`" (`COMPUTATION.md`).

### 4. A delegation is a data document

A grant is naturally a TSON data document, governed by a schema for delegations. An illustrative shape, not resolved
against anything:

```
delegation => {
  principal:  did                 -- who grants
  agent:      did                 -- who acts
  interface:  uri                 -- the interface, pinned by hash
  grants:     [grant]
  until?:     datetime
  parent?:    digest              -- the delegation this one attenuates, by hash
}
grant => {
  method:   ref<method>           -- a method of the interface
  request?: kind<record>          -- the refinement its requests must validate against
}
```

This lands on threads from the one-level note:
- A data document holding a checked type name works today (a kind-typed field, measured by `OneLevelProbe`).
- A reference to a method can't be typed in a schema that governs data at all: `ref` is declared only in a
  kernel-governed schema, so `ref<method>` there is an unresolved name (`OneLevelProbe`). A reference in a data
  document is a Revision 39 question.
- A grant names methods in the *interface's* namespace, not its own schema's: the same scope problem as an agent plan,
  with the same candidate answers (a derived governing schema, or a §7.8 scope push).

### 5. Policy comes from the deployment, never the request

§8.2 already says no document may select the policy it is judged under. Identity needs the same rule: **a request can't
choose which issuers, credential types or personhood schemes the server trusts.** That belongs in the deployment
descriptor (`deployment.tn`). It could be published in the acceptance profile at `/.well-known/tson-deployment`, so a
client learns which credentials will be accepted before it sends a request, as it learns the nesting limit today.

### 6. Signatures over the HTTP exchange

This project has the precedent: `TSON-Schema` names a body's schema in a header, agreeing with the body by canonical
identity. RFC 9421 HTTP Message Signatures can cover the body together with that header, so a signed request commits to
the schema it claims as well as its content. Web Bot Auth uses the same RFC, so a TSON request could carry a bot's
declared identity and a delegation reference in one signed exchange.

### 7. Identity vocabulary as schemas

DID documents, verifiable credentials and delegation chains could be described by TSON schemas. The VC data model uses
JSON-LD `@context` for its vocabulary. A TSON schema, identity-addressed and hash-pinned, is a typed alternative, and
TSON's JSON reader (Part 3) could read an existing JSON credential against one.

## What to keep from the rest of these notes

- **Identity is a layer beside TSON, like computation.** Values stay pure. `CLAUDE.md` records that TSON deliberately
  does not track provenance, and that should hold: who asserted a value belongs in a signed **envelope** around it (in
  the style of COSE_Sign1 or JWS), never in the data model.
- **Authority comes from the deployment or the caller,** never from the document (§8.2, object capability).
- **Delegation only narrows,** which refinement already guarantees.
- **Everything is addressed by content:** interfaces, plans, delegations and schemas, pinned by hash, so an approval or
  a grant names exactly one thing.
- **Keep the rows of the table apart.** A design that answers personhood doesn't answer delegation, and the other way
  round.

## Cheap first experiments

Each could be tried with what exists, without committing to anything:
1. **Authorization as refinement:** write a delegated refinement of a demo request type and show the codec refusing a
   request outside it. The resolver and validator already do all of it.
2. **A canonical value form:** define one for TSON's model and sign a value in a COSE or JWS envelope, verifying it in
   both encodings.
3. **Plan approval by hash:** sign an `agent.tn` plan's digest and have admission refuse any other plan.
4. **HTTP Message Signatures** over a TSON body and its `TSON-Schema` header, in the JDK adapter.
5. **A verifiable credential read through TSON's JSON reader** against a TSON schema for it.
