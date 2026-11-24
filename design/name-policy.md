# Name hygiene and the processor policies

What [TSON-DATA] §8.2's three rules refuse and which positions are names, the two policy types and their opposite
defaults, why a refusal carries its code and no policy, and why raising the token policy is the application's call.
Current form only. How a refusal becomes a status and a problem `type` is `design/status-classification.md`.

**Invariants**

- Name hygiene is policy, not validity. Each of §8.2's rules has its own code (`CONFUSABLE_NAMES`,
  `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`); each is a 400 the sender fixes and none is a verdict. Ask
  `Diagnostic.Code.isNameRefusal()` rather than listing them.
- A field name is a name and meets all three rules, as a value of its record's `name_type`, judged only by the schema
  the document names.
- A name is decided by type, not position: an identifier-typed field value, an identifier-keyed map's keys and a unique
  array of identifiers are names, in a data document and in a schema's value entries alike.
- The rule rides the code. No second field or enum beside it restates which rule refused.
- A refusal carries its code and nothing about the policy that judged it. The processor states its policy once
  (`Tson.processorPolicy()`), and over HTTP it is published once at `/.well-known/tson-deployment`
  (`design/deployment-descriptor.md`); the problem body carries no `policy` member.
- The identifier policy and the token policy are two types with opposite defaults: Highly Restrictive over declared
  names, unrestricted over values.
- `tson-http` never builds the `Tson`, so raising the token policy is the application's call.

**Name hygiene is policy, not validity, and it arrives as three codes of its own.** [TSON-DATA] §8.2 is a layer
two conforming processors may legitimately disagree on, which is why its defaults are the library's choice
rather than the format's. **One code per rule**: `CONFUSABLE_NAMES` (two names in one scope with equal
UTS #39 skeletons), `RESTRICTED_CHARACTER` (a character outside the identifier profile) and
`RESTRICTED_SCRIPT` (a script combination the level does not admit — wider than a mix, since `ASCII_ONLY`
refuses a single-script name with nothing mixed).

**All three are 400 and none is a verdict**, both halves pinned rather than left implicit
(`TsonHttpCodecTest.nameHygieneIsARefusalTheSenderFixes`): §8.1 makes a refusal its fifth outcome, so
`verdict()` is false for them as for `LIMIT_EXCEEDED` (`design/status-classification.md`, "`LIMIT_EXCEEDED` is a
413"). These are the first codes a server can meet because of its
*own* configuration, and the temptation is to read "my policy refused it" as a 5xx. It is not — a body refused
under a raised policy is refused as one over a depth limit is, and it is still the client's to fix. None of
them says anything went unchecked *for want of this server*, which is what the 5xx codes have in common and
these do not — and why a status keyed on `verdict()` would be wrong here too.

**A field name is a name and meets all three rules**: the two per-name rules reach a Class 1 record's field names as
well as the look-alike rule. So a within-word homograph — Latin `admin` beside a Cyrillic-`а` one — draws
`RESTRICTED_SCRIPT` *as well as* `CONFUSABLE_NAMES`, and the script one is reported first,
which decides the problem `type`: `policyRefusal` takes the first refusal in the list. A body whose only
refusal is the set rule needs two names the identifier policy admits *individually* — the all-Cyrillic
spelling of `pass` beside the Latin one, which is what
`TsonHttpCodecTest.twoConfusableFieldNamesInASchemalessBodyAreABadRequest` sends, and both halves are
pinned by `UpstreamGapsTest.everyFieldNameOfASchemalessRecordMeetsAllThreeNameRules`.

**A field name is a value of its record's `name_type`**, an identifier family defaulting
to the series' identifier, so nothing here changes unless a schema says so. What a server meets: a schemaless
`{ "first name": 1 }` is `ATOM_FORM_INVALID`, a 400; a governed record's undeclared
name, identifier or not, is `UNRECOGNIZED_FIELD` in both encodings; and a body naming a schema this deployment will
not supply is skipped unjudged, so its problem carries the unusable reference alone rather than name refusals a
schema admitting `"first name"` would never make (`UpstreamGapsTest.aFieldNameIsJudgedOnlyByTheSchemaTheDocumentNames`).
A relaxed name type is a translation's tool — `@id`, `$ref`, or an HTTP-style name under `ASCII_CASEFOLD` — stated on
a fieldless abstract base its records compose, and bound with `@Field("@id")`; the hygiene rules then run under that
type's profile. `RecordBody.fields()` is an ordered text-keyed `AnnotatedMap`: a field name is in no namespace.

**The rule rides the code, not a field beside it**, and that is worth knowing before designing anything
similar: a consumer routes on the code, and a second enum would restate what the code already fixes while
being free to disagree with it. The three want three different fixes — rename one of a colliding pair, change
a character, or relax the level — so the code has to carry enough to say which.

**A refusal carries its code and nothing about the policy that judged it.** The level, the unit and the
Unicode data version are properties of the processor, constant for its life, and tson-java states them once —
`Tson.processorPolicy()` returns a `ProcessorPolicy` (both §8.2 policies, the limits, and `dataVersion()`), and a
derived reader answers for itself — rather than on each refusal; `Diagnostic` carries no per-refusal version.

The problem body follows the same line. tson-cli's envelopes carry `policy` on every run because a report file has
nothing to dereference; an HTTP response does, and RFC 9457's own pointer from a problem to deployment-scoped
documentation is `type`, which is how a refusal is typed (`design/status-classification.md`, "A refusal is a 400 of
its own problem `type`, one per code, and the body carries no policy"). Embedding the profile in every refusal would
restate a processor constant N times, the mistake `Diagnostic` avoids by carrying no per-refusal version, one level
up. Not adding a `policy` member was therefore a layering decision, not an RFC 9457 constraint — extension
members are the standard's own growth mechanism and `errors` already is one. If a client ever needs to know
*which version* of the profile judged it, the answer is a `Link` header plus the profile's `name`, added then.

**The two policies are two types, because they govern different things.** Each is flat: it holds a `RestrictionLevel` and the
script combinations `permitting(...)` admits over it, judged by the package-private `ScriptRule` both share.
`IdentifierPolicy` adds what only a name has: a unit (`perSegment()`, dividing at the name's profile's own separators) and
`withSkeletonDistinctness`, the look-alike rule's own switch, which no level reaches. `TokenPolicy` has neither, so a
per-segment token policy is *unwritable* rather than refused. **A name is decided by type, not position**: an
identifier-typed field value, an identifier-keyed map's keys and a unique array of identifiers are names and meet all three
rules, the key and element sets being look-alike scopes — in a data document and in a schema's value entries alike, where a
namespace value and an annotation value are read under the same policy. `NameRoleTest` pins both places. Over HTTP the
once-statement is `deployment.tn`'s acceptance profile at `/.well-known/tson-deployment` (`design/deployment-descriptor.md`).
§8.3 is why any of it matters: all three rules are unstable across Unicode releases, so two conforming processors may
legitimately disagree about one name and the version is what explains it.

**The defaults are opposite on purpose**, and a server inherits both: `ProcessorConfig.withIdentifierPolicy` defaults
to Highly Restrictive over *declared names*, so a schema a request body names is refused for a homograph;
`ProcessorConfig.withTokenPolicy` defaults to unrestricted over *values*, because data may legitimately be a Cyrillic
display name and no scan runs at all. Pinned by
`UpstreamGapsTest.aDeclaredNameDefaultsToHighlyRestrictiveAndAValueToUnrestricted` — asserting one half would
leave the other free to move.

**Raising the token policy is the application's call, not this library's.** §8.2's "Values" paragraph names
this project's exact situation — a service that renders or matches untrusted values faces on values the
spoofing surface §9.4 raises for names — and says such a deployment applies the level *knowingly*.
`tson-http` never builds the `Tson`, so it has no place to decide; a service that renders what it reads should
pass `withTokenPolicy(...)` where it builds one. A trap if it does: a token policy stricter than the identifier
policy **subsumes** it, since the check runs before anything knows which tokens are names. And there is no unit to
reach for — `_` and `-` are word separators in a name and ordinary characters in a value, so segmenting one would
admit UTS #39's own `Toys-Я-Us`, which is why `TokenPolicy` has no per-segment form.
