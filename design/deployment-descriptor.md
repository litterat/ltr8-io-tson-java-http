# Deployment descriptors (`deployment.tn`, `TsonDeployment`)

The third artifact kind: how one instance is configured, why it is data and not a schema, what it states and what it
publishes at `/.well-known/tson-deployment`. Current form only.

**Invariants**

- `TsonDeployment.read` takes source text; there is no search path. No document may name a descriptor, nothing registers
  one with a schema source, and the catalog never serves one. `deployment.tn` itself is published.
- A descriptor states its policy whole and it is applied exactly as written. There are no partial overrides.
- An absent policy is not a permissive one: `applyTo` leaves the config alone.
- `limits` sits beside the two policies, not inside them. `TsonDeployment.Policy.Limits` carries every member of
  `policy.limits`; a descriptor writes the ones it means and `policy.tn`'s defaults fill the rest.
- The profile is the policy in force (`profile(inForce)`), not what the descriptor says, and it is a hint.
- Hosts are `net.tn`'s `host`; `schema_hosts` is a `set<host>` compared by the type's equality.
- `deployment.tn` declares one root, the schema map `deployment`: a descriptor is a `deployment.descriptor`, the
  profile a `deployment.acceptance_profile`, and each bound record's `@Typename` is that qualified name.

## Deployment descriptors (`deployment.tn`, `TsonDeployment`)

**The third artifact kind**, beside a schema (what a document must be) and an API description (what an
endpoint offers): how *one instance* is configured. [TSON-DATA] §8.2 says no document may name, import or otherwise
select the policy it is judged under, and the spec bundles `policy.tn` (`https://tson.io/2026/40/m/policy.tn`, which
imports `meta.tn` alone) as the policy's vocabulary. Its entries are members of a schema map `policy` nested in its root
(`policy => !schema { … }`), so each is written by path: `policy.restriction_level`, `policy.token_policy`,
`policy.identifier_policy`, `policy.limit`, `policy.limits`, and `policy.processor`, the shape a deployment writes its
policy in and a processor reports it in. `deployment.tn` imports `core.tn`, which brings `host`, and `policy.tn`, and
both ends are a `policy.processor`: the descriptor states one and the profile publishes one. A descriptor is a data
document that imports `deployment.tn` and whose root is `!deployment.descriptor { … }`. The argument is in
`deployment.tn`'s own `@doc`; the short form:

- **Not a schema.** An artifact declaring its own strictness chooses its own check, and §3.5's immutability
  means raising a level mints a new identity, so every document pinning the old one keeps the old policy.
  §8.3 settles it independently: skeleton distinctness does not compose across `!!import`, so the policy is a
  property of the merged namespace rather than of any one schema.
- **Not an API description**, which is a schema here and inherits both, and which would put policy in a
  contract — raising a token policy would mean publishing a new description.
- **It is data**, for those reasons and not for what it references: as a schema it would certify itself and be immutable at
  its identity, and nothing may name it, so it exports no namespace for anything to refer into
  (`design/service-description.md`). An API description is a schema because others refer into it. A descriptor referring
  *into* the API, a `meta.ref<service.exchange>` for what this instance serves or `TsonServiceCoverage.notServedHere`
  exemptions as configuration, is data referring into a namespace it imports: possible, and not done.

**Two rules, enforced by shape rather than by documentation.** `TsonDeployment.read` takes source text — there
is no search path and there must not be one, because a runtime that loads whatever it finds lets a container
image change a security policy with no code diff. And no document may name a descriptor: nothing registers one
with a schema source, and the catalog never serves one. `deployment.tn` itself *is* published, because a
client needs it to read the profile.

**A descriptor states its policy whole, and it is applied exactly as written.** `policy?: policy.processor` is
`policy.tn`'s, every part required — identifier policy, token policy, limits — and `applyTo` hands the
`ProcessorPolicy` it makes to `ProcessorConfig.withProcessorPolicy`. There are **no partial overrides** falling
back to the library's defaults, deliberately: a default is the library's to move, and a security setting whose
effect shifts under an upgrade with no diff to the descriptor is the silent relaxation §8.2 exists to prevent.
So the validator's descriptor writes out the parts it leaves at the default — the identifier policy and every
limit, each at `policy.tn`'s value — and the profile it publishes writes every limit in force. Pinned by
`TsonDeploymentTest.aPolicyStatesEveryPart` and `aStatedPolicyIsAppliedExactlyAsWritten`.
`unicode_data_version` is the one optional member of `policy.processor`, and a descriptor leaves it out or writes
the processor's own: the tables are the processor's, so `TsonDeployment` refuses a descriptor naming a version this
processor does not carry, and the read stops.

**Hosts are `net.tn`'s `host`**: a host name in either label form or an IPv4 or IPv6 address, one string-class
atom, so `deployment.listener`'s `host` takes `localhost`, `"127.0.0.1"` and `"[::1]"` untagged — where
`( hostname | ipv4 | ipv6 )` is not disjoint under §5.4 and would need a tag on every value. `schema_hosts` is a
`set<host>`, compared by the type's equality: `bücher.example` is `xn--bcher-kva.example`, `Example.COM` is
`example.com`, `[::1]` is `0:0:0:0:0:0:0:1`, so one host twice is refused whatever the spellings, and a port, a URL,
a trailing dot or a zone identifier is refused at read with its fix named. A `String` component binds the canonical
text (U-label names, RFC 5952 for IPv6). `TsonDeploymentTest`'s host tests pin all of it. Canonical identity
reads an identity's host the same way, so `allowHost` takes either label form and an entry matches a reference in
the other (`UpstreamGapsTest.aNonAsciiHostIsOneIdentityInEitherLabelForm`).

**An absent policy is not a permissive one.** The two defaults point opposite ways (Highly Restrictive over
declared names, unrestricted over values), so a descriptor stating no `policy` returns empty from
`processorPolicy()` and `applyTo` leaves a config alone rather than overwriting it with a guess.

**`limits` is the third part and it is beside the two policies, not inside them.** §8.2's policies say what this
processor will *admit as a name*; §9.1's and [TSON-SCHEMA] §11.5's say what it will *spend reading a document*, and
a deployment changing one has said nothing about the other. `TsonDeployment.Policy.Limits` carries every
member of `policy.limits`, one component per member, and converts both ways: `Limits.of(LimitsPolicy)` from the
library's type and `toLimitsPolicy()` to it. Every component is a `long`, since a valid descriptor may state any
`policy.limit` up to 2^63-1; a size reaches `LimitsPolicy` unchanged, and a count or depth past `Integer.MAX_VALUE` is
capped there, as the library holds it. Every member is optional, typed `policy.limit` (a positive `int64`) with the
spec's default, so a descriptor writing `{ max_depth: 64 }` states one limit and reads the rest as `policy.tn` states
them. A member left out of `policy.limits` takes `policy.tn`'s own default, so a descriptor stating one limit is
complete, and the profile still publishes every limit in force. That is a default, but not the library's to move: it
is written in the schema the descriptor's own imports name, so it changes only when those imports do.

**The profile is the policy in force, and it is a hint.** `profile(inForce)` takes the `ProcessorPolicy` the
processor actually enforces — `applyTo(config).processorPolicy()`, or `tson.processorPolicy()` — not what the
descriptor says, so a descriptor stating no policy still publishes the library's defaults, and a client needs
to know no library's defaults to know what applies. It is written as a `policy.processor`, the
shape `tson policy` and the CLI's report use, scripts named by UAX #24 alias (`Latin`). It drops the fetch
allow-list and the listener — internal topology — and a server publishes *that*, at
`/.well-known/tson-deployment`. A well-known path
because everything with an identity is served at its identity's path, and a descriptor is precisely what must
not have one. It can be cached and go stale; only the refusal a request receives says what applied to it,
which is where §8.2 puts the policy. **`limits` is kept in the projection** on the same argument that keeps
the policies: a 413 says a document went past a bound, and a sender that read the bound first never writes
past it — a limit is the shape of what this endpoint accepts, where the allow-list is topology.
`unicode_data_version` comes from the
`ProcessorPolicy` rather than being copied — a constant would go stale silently on an upgrade —
and it is in the profile because §8.3 marks all three rules unstable across Unicode releases, so two
conforming processors may legitimately disagree about one name and the version is what explains it.

**`policy.tn` mirrors the library's two policy types**: `policy.identifier_policy` is a level, `per_segment`,
`skeleton_distinctness` and `permitting`, `policy.token_policy` a level and `permitting` — no unit, since a value has
no segments, so a per-segment token policy is unwritable (`TsonDeploymentTest.aTokenPolicyCannotBeGivenAUnit`).
`permitting` is a list of script combinations, each named by UAX #24 alias; `TsonDeployment.Policy` canonicalises
every spelling (`Latn`, `cyrillic`) to the long alias, sorted within a combination, and a name that is not a
script — `policy.tn` types it as `text` — stops the read.

**`policy.restriction_level` is `policy.tn`'s**, imported rather than copied, so nothing here holds a copy to
`io.ltr8.unicode.RestrictionLevel`; upstream's `PolicySchemaTest` holds the bundled one.


