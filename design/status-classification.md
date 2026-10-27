# Status classification: `Diagnostic.Code` to HTTP status

How a read's outcome becomes a status and a problem `type`: the split upstream states, the codes that are not verdicts,
the rank order, the two fetch channels, name hygiene and the policies behind it. Current form only. The policy is
`TsonHttpException.from`; nothing else decides a status.

**Invariants**

- Validation is 4xx; a gap or an internal fault is 5xx. A gap is never reported as "your request was invalid".
- Ask the `Diagnostic.Code`, never the exception type: a gap arrives as a `ReadException` carrying `NOT_IMPLEMENTED`,
  the same type a violation arrives as.
- A code `verdict()` calls true may never be answered 5xx
  (`TsonHttpCodecTest.everyCodeEarnsAStatusAndNoVerdictBecomesAServerFault`).
- The status is a scan in rank order over every diagnostic, not a first match on the list. The origin's two codes
  (502, 504) rank last and answer only when they are all that is wrong.
- One fetch status table: a thrown `SchemaFetchException.Reason` maps through `Diagnostic.Code.of(reason)` into it.
  Never add a second switch over `Reason`.
- Name hygiene (`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`) and `LIMIT_EXCEEDED` are refusals the
  sender fixes: 400 and 413, not 5xx, and not verdicts. Ask `isNameRefusal()` and `verdict()` rather than listing codes.
- A refusal is typed by its code, one problem `type` per code, and the body carries no policy; the policy is published
  once at `/.well-known/tson-deployment`.
- `tson-http` never builds the `Tson`, so raising the token policy is the application's call.

**Error classification is already a policy upstream — mirror it, don't invent one.** tson-java splits:
the author's document/schema is wrong per the spec; the library hasn't implemented that yet
(`UnsupportedOperationException`, where it is raised outside a read); `IllegalStateException` = an
internal invariant broke. The CLI rides its exit codes on that split (1 = your document is bad, 70 = a fault
in the library), and **the HTTP mapping is the same split wearing status codes**: validation → 4xx, gap or
internal fault → 5xx. A gap must never be reported to a client as "your request was invalid".

**Ask the `Diagnostic.Code`, not the exception type — the split is no longer one type per outcome.** A read
gap is now *reported* like any other problem rather than thrown, so it reaches a collecting caller as a
`NOT_IMPLEMENTED` diagnostic among the rest, and a fail-fast one as a `ReadException` carrying that same
code — the very type a schema violation arrives as. Classifying by type therefore answers 400 for a gap,
which is the one verdict this policy may never give. `TsonHttpException.invalidDocument` holds the rule and
`from` routes every `ReadException` through it, so both channels answer alike. Upstream states the rule
as *"asking by code rather than by exception type is the stated policy"*.

**Eleven codes are not verdicts on the document, and they differ by who could not give one** — this library, the
reading application, this deployment's own policy and budget, whoever was to serve the schema. `Diagnostic.Code.`
`verdict()` is that set, stated upstream so no consumer keeps a private copy; **use it rather than listing them**.

| Code | Who | Status |
|---|---|---|
| `NOT_IMPLEMENTED` | this library has not built it | 501 |
| `BIND_MISMATCH` | this server's own wiring | 500 |
| `SCHEMA_UNREACHABLE` | the origin could not be reached | 502 |
| `SCHEMA_TIMEOUT` | the origin did not answer in time | 504 |
| `LIMIT_EXCEEDED` | the body went past what this deployment reads | 413 |
| `CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT` | this deployment's name policy declined it | 400 |
| `SCHEMA_NOT_PERMITTED` | this deployment will not supply it — off the allow-list, or a closed source's miss | 400 |
| `SCHEMA_NOT_FOUND` | an origin or directory looked and found nothing at the reference | 400 |
| `SCHEMA_TOO_LARGE` | the document exceeds this deployment's size cap | 400 |

**Not being a verdict does not settle the status**, and the fetch codes are where that shows. `verdict()` answers *was the
document judged*; a status answers *who must act*. For a reference this deployment will not fetch, cannot find, or finds too
large, the body went unchecked **and** the sender still holds the fix — so those are 400s. The spec says the first half
itself: [TSON-DATA] §8.1's fifth outcome is *not judged*, whose members are a **refusal** (§8.2's name hygiene and §9.1's
limits — this processor declined, and the next may accept the same document in full) and an **unavailable schema**
([TSON-SCHEMA] §10.1) — so neither is a verdict by the spec's own account. Upstream counts `SCHEMA_NOT_PERMITTED` and
`SCHEMA_TOO_LARGE` as refusals too (`Code.isRefusal()`): a schema this deployment *would not* supply, where the other three
are what the world *could not*; tson-java's `SPEC-FEEDBACK.md` carries the change to §8.1's wording, and a `?sha256=` pin
mismatch stays a resolver error, a finding about bytes that *were* obtained. The invariant that does hold is the other
direction, and `TsonHttpCodecTest.everyCodeEarnsAStatusAndNoVerdictBecomesAServerFault` pins it: **a code `verdict()` calls
true may never be answered 5xx.** That is the failure the classification exists to prevent — telling a sender the server
broke when their document really was wrong sends them round a loop that cannot terminate.

Ranking, most-inward actor first, and the `tson` CLI's exit order (`70 > 78 > 69 > 1 > 75`) wearing statuses:
`BIND_MISMATCH` (an operator has to fix it, and until they do nothing else is evaluated) → `NOT_IMPLEMENTED` →
the three 400 fetch codes → `LIMIT_EXCEEDED` → §8.2 refusals → ordinary violations → `SCHEMA_UNREACHABLE` →
`SCHEMA_TIMEOUT`. `TsonHttpException.REFERENCE_CODES` and `ORIGIN_CODES` state the two fetch halves.
**This is a scan in rank order, not a first-match on the diagnostic list** — the status used to come from
whichever fetch reason was reported first, so document ordering decided whether the sender or the dependency
was blamed.

**The origin's two rank last, and answer only when they are all that is wrong.** A retry is advice only when
nothing else needs fixing: every other fix ends in a resend, which retries the origin for free, while a bare retry
of a body holding anything else the sender must change — a verdict, a refusal, a reference this deployment will
not supply — reaches the same answer. So beside one of those a 502 or 504 is the wrong advice, and the answer is
that problem's 4xx, the detail saying part of the body went unchecked. This reverses an older rule here ("a mixed
failure is never blamed on the client"): the client *is* who must act first. `BIND_MISMATCH` and
`NOT_IMPLEMENTED` keep their place above everything: an operator acts first, and a server fault answered 4xx would
leave nothing to log. So the status answers what the CLI's exit code answers, and parts from its `outcome`
exactly where the exit code does — a rejection beside a gap or a bind mismatch is a 500 or 501 here and
`REJECTED` there, and `SCHEMA_NOT_FOUND` is a 400 here (exit 69, the sender most likely holds the fix) and
`UNDETERMINED` there. Pinned by `TsonHttpCodecTest.aRejectionOutranksAnOriginsFailure` and
`aReferenceTheSenderMustChangeOutranksAnOriginsFailure`.

**`LIMIT_EXCEEDED` is a 413, and it is the one status here about what reading would *cost* rather than about
what the body says.** §9.1's resource limits are a policy the library now enforces — nesting depth, bounded at
§9.1's own default of 64 — and a body that goes past one is stopped where it stood. So it is not a verdict:
everything past that point went unread, and the detail says so. It is still a 4xx, on `SCHEMA_TOO_LARGE`'s
argument: the bound is this deployment's configuration, and the sender is nonetheless who can act. 413 rather
than 400 because RFC 9110 §15.5.14's subject is exactly this — content larger than the server will process —
and the diagnostic carries the depth admitted against the depth reached, so a client can act without parsing
prose. It ranks above a §8.2 refusal, though both are refusals, because a refused name was found by a reader
that *finished* the document, where this one it did not. The bound is published in the acceptance profile at
`/.well-known/tson-deployment`, which is the point: a sender that reads it first never writes past it.

**`SCHEMA_TOO_LARGE` is a 400 and reads like a 502.** Retrying shrinks a schema no more than it conjures a
missing one, so a 502 there would advertise a retry that cannot help. It goes with `SCHEMA_NOT_PERMITTED` and
`SCHEMA_NOT_FOUND` under `unusable-schema-reference` — a size cap is exactly a reference this deployment will
not fetch. The CLI reaches the same place from the other side, ranking it permanent.

**Both channels must answer alike, and there is now one table rather than two held together.** One fetch
failure reaches a consumer two ways: thrown as `SchemaFetchException` (essentially startup-only, since
**every read through the codec collects**) and collected as a diagnostic, the common path. The two speak
different enums — thrown carries a `Reason`, collected a `Code` — so `TsonHttpException.from` maps through
`Diagnostic.Code.of(reason)` into `fetchFailure`, which is the only fetch status table. Do not add a second
switch over `Reason`: that is what they were before, and they diverged, `NOT_PERMITTED` answering 400 thrown
and 502 collected. The agreement tests stay
(`TsonHttpCodecTest.aFetchDiagnosticIsAnsweredByItsCode`,
`TsonHttpSchemaSourceIntegrationTest.bothChannelsAnswerAnUnfetchableSchemaAlike`).

**The reason is the code, and must not also be a field.** `Diagnostic.fetchReason` is gone upstream and
`problem.tn` no longer declares a `fetch_reason` enum or field. A second carrier for one fact is free to
disagree with the first — on the wire that means a body stating a code and a reason that contradict each
other, which the schema would still call valid. Pinned by
`TsonProblemSchemaTest.theSchemaCarriesNoSecondCarrierForAFetchFailure`.

`Diagnostic.Code` is the detail vocabulary — mostly verdicts, but see the table above for the eleven that are not:
`FIELD_REQUIRED`, `FIELD_FIXED`, `FIELD_GROUP`, `TYPE_MISMATCH`, `WRONG_ARITY`, `UNKNOWN_TYPE_REF`,
`SCOPE_NOT_ADMITTED`, `ATOM_FORM_INVALID`, `ATOM_CONSTRAINT_VIOLATION`, `UNRECOGNIZED_FIELD`, `DUPLICATE_MAP_KEY`,
`DUPLICATE_FIELD`, `CONFUSABLE_NAMES`,
`RESTRICTED_CHARACTER`, `RESTRICTED_SCRIPT`, `SCHEMA_ERROR`, `UNKNOWN_TYPE`, `VALIDATION_ERROR`,
`NOT_IMPLEMENTED`, `BIND_MISMATCH`, `LIMIT_EXCEEDED`, `SCHEMA_NOT_PERMITTED`, `SCHEMA_NOT_FOUND`,
`SCHEMA_UNREACHABLE`, `SCHEMA_TIMEOUT`, `SCHEMA_TOO_LARGE`.

**An atom refuses in two categories, so it carries two codes.** §5.2 splits the refusal and §8.1 files the
halves apart: a token the atom's grammar rejects is `ATOM_FORM_INVALID` (a resolver error), a parsed value
violating the atom's range is `ATOM_CONSTRAINT_VIOLATION` (a validation one). Both are verdicts and both are
400; they are two codes because the fixes differ — rewrite the token, or change the value it denotes. `"not-a-
uuid"` at a `uuid` field is the first, an `age` of 300 against a `max` the second, and
`ValidatorServerTest.aFaultingDocumentIsAnAnswerRatherThanAFailure` shows both in one document. Adding it to
`problem.tn`'s `diagnostic_code` is exactly the drift `TsonProblemSchemaTest` exists to catch, and it did.

**`SchemaFetchException` lives in `io.ltr8.tson.base`**, beside the `SchemaSource` interface whose
contract it is — `fetch` names it as the one way a source says "cannot supply this", which is what lets the
classification route on it at all.

**Name hygiene is policy, not validity, and it arrives as three more codes.** [TSON-DATA] §8.2 is a layer
two conforming processors may legitimately disagree on, which is why its defaults are the library's choice
rather than the format's. **One code per rule**: `CONFUSABLE_NAMES` (two names in one scope with equal
UTS #39 skeletons), `RESTRICTED_CHARACTER` (a character outside the identifier profile) and
`RESTRICTED_SCRIPT` (a script combination the level does not admit — wider than a mix, since `ASCII_ONLY`
refuses a single-script name with nothing mixed).

**A field name is a name and meets all three rules**, which is a Revision 35 change and reads like a test being
wrong when it bites. A Class 1 record's field names used to see only the look-alike rule; now the two per-name
rules reach them as well. So a within-word homograph — Latin `admin` beside a Cyrillic-`а` one — draws
`RESTRICTED_SCRIPT` *as well as* the `CONFUSABLE_NAMES` it always drew, and the script one is reported first,
which decides the problem `type`: `policyRefusal` takes the first refusal in the list. A body whose only
refusal is the set rule needs two names the identifier policy admits *individually* — the all-Cyrillic
spelling of `pass` beside the Latin one, which is what
`TsonHttpCodecTest.twoConfusableFieldNamesInASchemalessBodyAreABadRequest` now sends, and both halves are
pinned by `UpstreamGapsTest.everyFieldNameOfASchemalessRecordMeetsAllThreeNameRules`.

**A field name is a value of its record's `name_type`** (Revision 38), an identifier family defaulting
to the series' identifier, so nothing here changes unless a schema says so. What a server meets: a schemaless
`{ "first name": 1 }` is `ATOM_FORM_INVALID`, a 400 like the parse error it was; a governed record's undeclared
name, identifier or not, is `UNRECOGNIZED_FIELD` in both encodings; and a body naming a schema this deployment will
not supply is skipped unjudged, so its problem carries the unusable reference alone rather than name refusals a
schema admitting `"first name"` would never make (`UpstreamGapsTest.aFieldNameIsJudgedOnlyByTheSchemaTheDocumentNames`).
A relaxed name type is a translation's tool — `@id`, `$ref`, or an HTTP-style name under `ASCII_CASEFOLD` — stated on
a fieldless abstract base its records compose, and bound with `@Field("@id")`; the hygiene rules then run under that
type's profile. `RecordBody.fields()` is an ordered map from name to `RecordField`, which carries no name of its own.

**All three are 400 and none is a verdict**, both halves pinned rather than left implicit
(`TsonHttpCodecTest.nameHygieneIsARefusalTheSenderFixes`): §8.1 makes a refusal its fifth outcome, so
`verdict()` is false for them as for `LIMIT_EXCEEDED`. These are the first codes a server can meet because of its
*own* configuration, and the temptation is to read "my policy refused it" as a 5xx. It is not — a body refused
under a raised policy is refused as one over a depth limit is, and it is still the client's to fix. None of
them says anything went unchecked *for want of this server*, which is what the 5xx codes have in common and
these do not — and why a status keyed on `verdict()` would be wrong here too.

**The rule rides the code, not a field beside it**, and that is worth knowing before designing anything
similar: a consumer routes on the code, and a second enum would restate what the code already fixes while
being free to disagree with it. The three want three different fixes — rename one of a colliding pair, change
a character, or relax the level — so the code has to carry enough to say which.

**A refusal carries its code and nothing about the policy that judged it.** The level, the unit and the
Unicode data version are properties of the processor, constant for its life, and tson-java states them once —
`Tson.processorPolicy()` returns a `ProcessorPolicy` (both §8.2 policies, the limits, and `dataVersion()`), and a
derived reader answers for itself — rather than on each refusal; `Diagnostic` carries no per-refusal version.

**The two policies are two types, because they govern different things.** `IdentifierPolicy` is a `ScriptPolicy`
level plus what only a name has: a unit (`perSegment()`, dividing at the name's profile's own separators) and
`withSkeletonDistinctness`, the look-alike rule's own switch, which no level reaches. The token policy is a bare
`ScriptPolicy`, so a per-segment token policy is *unwritable* rather than refused. **A name is decided by type,
not position**: an identifier-typed field value, an identifier-keyed map's keys and a unique array of identifiers
are names and meet all three rules, the key and element sets being look-alike scopes — in a data document and in a
schema governed by a meta layer alike, where a namespace value and an annotation value are read under the same
policy. `NameRoleTest` pins both places.
Over HTTP the once-statement is `deployment.tn`'s acceptance profile at `/.well-known/tson-deployment`. §8.3
is why any of it matters: all three rules are unstable across Unicode releases, so two conforming processors
may legitimately disagree about one name and the version is what explains it.

**A refusal is a 400 of its own problem `type`, one per code, and the body carries no policy.** tson-cli's
envelopes carry `policy` on every run because a report file has nothing to dereference; an HTTP response does,
and RFC 9457's own pointer from a problem to deployment-scoped documentation is `type`. So
`TsonHttpException.policyRefusal` types a refusal `…/problems/restricted-script` (the code's name, kebab-cased)
and that documentation is where a client is sent to `/.well-known/tson-deployment`. Embedding the profile in
every refusal would be the constant-restated-N-times mistake upstream just removed from the diagnostic, one
level up. Not adding a `policy` member was therefore a layering decision, not an RFC 9457 constraint — extension
members are the standard's own growth mechanism and `errors` already is one. If a client ever needs to know
*which version* of the profile judged it, the answer is a `Link` header plus the profile's `name`, added then.

**Both channels type a refusal by its rule.** A refusal at schema load arrives collected, as one of the three codes,
or thrown on a fail-fast path as a `SchemaRefusalException` — the one subtype of a sealed `SchemaValidationException`.
`from` matches it *before* its supertype, which would answer `invalid-schema`, since §8.2 requires a refusal be told
from a malformed schema. Ask `Diagnostic.Code.isNameRefusal()` for the three-code set rather than listing it, as with
`verdict()`. Pinned by `TsonHttpCodecTest.aThrownSchemaRefusalIsAnsweredAsACollectedOneIs`.

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
admit UTS #39's own `Toys-Я-Us`, which is why `ScriptPolicy` has no per-segment form.


