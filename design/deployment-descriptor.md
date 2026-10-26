# Deployment descriptors (`deployment.tn`, `TsonDeployment`)

The third artifact kind: how one instance is configured, why it is data and not a schema, what it states and what it
publishes at `/.well-known/tson-deployment`. Current form only.

**Invariants**

- `TsonDeployment.read` takes source text; there is no search path. No document may name a descriptor, nothing registers
  one with a schema source, and the catalog never serves one. `deployment.tn` itself is published.
- A descriptor states its policy whole and it is applied exactly as written. There are no partial overrides.
- An absent policy is not a permissive one: `applyTo` leaves the config alone.
- `limits` sits beside the two policies, not inside them. `max_depth` is its only member because it is the only limit
  the library enforces.
- The profile is the policy in force (`profile(inForce)`), not what the descriptor says, and it is a hint.
- Hosts are `net.tn`'s `host`; `schema_hosts` is a `set<host>` compared by the type's equality.

## Deployment descriptors (`deployment.tn`, `TsonDeployment`)

**The third artifact kind**, beside a schema (what a document must be) and an API description (what an
endpoint offers): how *one instance* is configured. Revision 34 added the §8.2 policies as a security control
with no artifact, and this was the proposal for where it lives. **Revision 37 adopted it**: [TSON-DATA] §8.2 now
says no document may name, import or otherwise select the policy it is judged under, and the spec bundles
`policy.tn` (`https://tson.io/2026/38/m/policy.tn`) as the policy's vocabulary — `restriction_level`,
`script_policy`, `identifier_policy`, `limits` and `policy`, the shape a deployment writes its policy in and a
processor reports it in. `deployment.tn` imports it, and both ends are a `policy.tn` `policy`: the descriptor
states one and the profile publishes one. The argument is in `deployment.tn`'s own `@doc`; the short form:

- **Not a schema.** An artifact declaring its own strictness chooses its own check, and §3.5's immutability
  means raising a level mints a new identity, so every document pinning the old one keeps the old policy.
  §8.3 settles it independently: skeleton distinctness does not compose across `!!import`, so the policy is a
  property of the merged namespace rather than of any one schema.
- **Not an API description**, which is a schema here and inherits both, and which would put policy in a
  contract — raising a token policy would mean publishing a new description.
- **It is data**, and that line is the useful one: an API description *must* be a schema because
  `request: order` is a type reference; a descriptor references no types, so nothing about it needs a
  namespace.

**Two rules, enforced by shape rather than by documentation.** `TsonDeployment.read` takes source text — there
is no search path and there must not be one, because a runtime that loads whatever it finds lets a container
image change a security policy with no code diff. And no document may name a descriptor: nothing registers one
with a schema source, and the catalog never serves one. `deployment.tn` itself *is* published, because a
client needs it to read the profile.

**A descriptor states its policy whole, and it is applied exactly as written.** `policy?: policy` is
`policy.tn`'s, every part required — identifier policy, token policy, limits — and `applyTo` hands the
`ProcessorPolicy` it makes to `ProcessorConfig.withProcessorPolicy`. There are **no partial overrides** falling
back to the library's defaults, deliberately: a default is the library's to move, and a security setting whose
effect shifts under an upgrade with no diff to the descriptor is the silent relaxation §8.2 exists to prevent.
So the validator's descriptor writes out the two parts it leaves at the default. Pinned by
`TsonDeploymentTest.aPolicyStatesEveryPart` and `aStatedPolicyIsAppliedExactlyAsWritten`.
`unicode_data_version` is left out of a descriptor: the tables are the processor's, so a descriptor naming one
this processor does not carry stops the read.

**Hosts are `net.tn`'s `host`** (Revision 38): a host name in either label form or an IPv4 or IPv6
address, one string-class atom, so `listener.host` takes `localhost`, `"127.0.0.1"` and `"[::1]"` untagged — where
`( hostname | ipv4 | ipv6 )` is not disjoint under §5.4 and would need a tag on every value. `schema_hosts` is a
`set<host>`, compared by the type's equality: `bücher.example` is `xn--bcher-kva.example`, `Example.COM` is
`example.com`, `[::1]` is `0:0:0:0:0:0:0:1`, so one host twice is refused whatever the spellings, and a port, a URL,
a trailing dot or a zone identifier is refused at read with its fix named. A `String` component binds the canonical
text (U-label names, RFC 5952 for IPv6). `TsonDeploymentTest`'s four host tests pin all of it. Canonical identity
reads an identity's host the same way, so `allowHost` takes either label form and an entry matches a reference in
the other (`UpstreamGapsTest.aNonAsciiHostIsOneIdentityInEitherLabelForm`).

**An absent policy is not a permissive one.** The two defaults point opposite ways (Highly Restrictive over
declared names, unrestricted over values), so a descriptor stating no `policy` returns empty from
`processorPolicy()` and `applyTo` leaves a config alone rather than overwriting it with a guess.

**`limits` is the third part and it is beside the two policies, not inside them.** §8.2's policies say what this
processor will *admit as a name*; §9.1's say what it will *spend reading a document*, and a deployment changing
one has said nothing about the other. `max_depth` is the only member because it is the only limit the library
enforces — §9.1 states twelve, and the rest are added as the library enforces them rather than as a member
nothing reads.

**The profile is the policy in force, and it is a hint.** `profile(inForce)` takes the `ProcessorPolicy` the
processor actually enforces — `applyTo(config).processorPolicy()`, or `tson.processorPolicy()` — not what the
descriptor says, so a descriptor stating no policy still publishes the library's defaults, and a client needs
to know no library's defaults to know what applies. It is written as `policy.tn`'s `policy`, the
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

**`policy.tn` mirrors the library's two policy types**: `identifier_policy` is a level, `per_segment`,
`skeleton_distinctness` and `permitting`, `script_policy` a level and `permitting` — no unit, since a value has
no segments, so a per-segment token policy is unwritable (`TsonDeploymentTest.aTokenPolicyCannotBeGivenAUnit`).
`permitting` is a list of script combinations, each named by UAX #24 alias; `TsonDeployment.Policy` canonicalises
every spelling (`Latn`, `cyrillic`) to the long alias, sorted within a combination, and a name that is not a
script — `policy.tn` types it as `text` — stops the read.

**`restriction_level` is `policy.tn`'s**, imported rather than copied, so the hand-written copy and the test
holding it to `ScriptPolicy.Level` are gone; upstream's `PolicySchemaTest` holds the bundled one.


