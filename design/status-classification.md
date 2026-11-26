# Status classification: `Diagnostic.Code` to HTTP status

How a read's outcome becomes a status and a problem `type`: the split upstream states, the codes that are not verdicts,
the rank order, the two fetch channels, and how a refusal is typed. Current form only. The policy is
`TsonHttpException.from`; nothing else decides a status. What name hygiene refuses, and the processor policies behind
it, are `design/name-policy.md`.

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
  sender fixes: 400 and 413, never 5xx, and not verdicts. Ask `isNameRefusal()` and `verdict()` rather than listing codes.
- A refusal is typed by its code, one problem `type` per code, and the body carries no policy; the policy is published
  once at `/.well-known/tson-deployment`.

**Error classification is already a policy upstream — mirror it, don't invent one.** tson-java splits:
the author's document/schema is wrong per the spec; the library hasn't implemented that yet
(`UnsupportedOperationException`, where it is raised outside a read); `IllegalStateException` = an
internal invariant broke. The CLI rides its exit codes on that split (1 = your document is bad, 70 = a fault
in the library), and **the HTTP mapping is the same split wearing status codes**: validation → 4xx, gap or
internal fault → 5xx. A gap must never be reported to a client as "your request was invalid".

**Ask the `Diagnostic.Code`, not the exception type.** A read gap is *reported* like any other problem rather than
thrown, so it reaches a collecting caller as a `NOT_IMPLEMENTED` diagnostic among the rest, and a fail-fast one as a
`ReadException` carrying that same code — the very type a schema violation arrives as. Classifying by type therefore
answers 400 for a gap, which is the one verdict this policy may never give. `TsonHttpException.invalidDocument` holds
the rule and `from` routes every `ReadException` through it, so both channels answer alike. Upstream states the rule
as *"asking by code rather than by exception type is the stated policy"*.

**Some codes are not verdicts on the document, and they differ by who could not give one** — this library, the
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

A type nothing binds is a 500 because the server chose the types it reads; a dependent field (`payload: kind!`), where
the sender names the type, cannot reach it, since `TsonServiceDescription` refuses at startup a bound request record whose
sibling may name a type no class binds (`ChosenTypes`).

**Not being a verdict does not settle the status**, and the fetch codes are where that shows. `verdict()` answers *was the
document judged*; a status answers *who must act*. For a reference this deployment will not fetch, cannot find, or finds too
large, the body went unchecked **and** the sender still holds the fix — so those are 400s. The spec says the first half
itself: [TSON-DATA] §8.1 sets two outcomes beside the four categories, neither a verdict on validity — **refused**
(this processor declined under its own configuration: §8.2's name hygiene, §9.1's limits, or a schema it will not
fetch or one past its size limit, and the next may accept the same document in full) and **undetermined** (it could
not obtain what it would judge against, [TSON-SCHEMA] §10.1). Upstream's `Code.isRefusal()` follows that line:
`SCHEMA_NOT_PERMITTED` and `SCHEMA_TOO_LARGE` are a schema this deployment *would not* supply, the other fetch codes
what the world *could not*, and a `?sha256=` pin mismatch stays a resolver error, a finding about bytes that *were*
obtained. The invariant that does hold is the other
direction, and `TsonHttpCodecTest.everyCodeEarnsAStatusAndNoVerdictBecomesAServerFault` pins it: **a code `verdict()` calls
true may never be answered 5xx.** That is the failure the classification exists to prevent — telling a sender the server
broke when their document really was wrong sends them round a loop that cannot terminate.

Ranking, most-inward actor first, and the `tson` CLI's exit order (`70 > 78 > 69 > 1 > 75`) wearing statuses:
`BIND_MISMATCH` (an operator has to fix it, and until they do nothing else is evaluated) → `NOT_IMPLEMENTED` →
the 400 fetch codes → `LIMIT_EXCEEDED` → §8.2 refusals → ordinary violations → `SCHEMA_UNREACHABLE` →
`SCHEMA_TIMEOUT`. `TsonHttpException.REFERENCE_CODES` and `ORIGIN_CODES` state the two fetch halves.
**This is a scan in rank order, not a first-match on the diagnostic list**, so document ordering never decides whether
the sender or the dependency is blamed.

**The origin's two rank last, and answer only when they are all that is wrong.** A retry is advice only when
nothing else needs fixing: every other fix ends in a resend, which retries the origin for free, while a bare retry
of a body holding anything else the sender must change — a verdict, a refusal, a reference this deployment will
not supply — reaches the same answer. So beside one of those a 502 or 504 is the wrong advice, and the answer is
that problem's 4xx, the detail saying part of the body went unchecked: the client *is* who must act first.
`BIND_MISMATCH` and `NOT_IMPLEMENTED` keep their place above everything: an operator acts first, and a server fault
answered 4xx would leave nothing to log. So the status answers what the CLI's exit code answers, and parts from its `outcome`
exactly where the exit code does — a rejection beside a gap or a bind mismatch is a 500 or 501 here and
`REJECTED` there, and `SCHEMA_NOT_FOUND` is a 400 here (exit 69, the sender most likely holds the fix) and
`UNDETERMINED` there. Pinned by `TsonHttpCodecTest.aRejectionOutranksAnOriginsFailure` and
`aReferenceTheSenderMustChangeOutranksAnOriginsFailure`.

**`LIMIT_EXCEEDED` is a 413, and it is the one status here about what reading would *cost* rather than about
what the body says.** §9.1's resource limits are a policy the library enforces — nesting depth, bounded at
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

**Both channels must answer alike, through one table.** One fetch failure reaches a consumer two ways: thrown as
`SchemaFetchException` (essentially startup-only, since **every read through the codec collects**) and collected as a
diagnostic, the common path. The two speak different enums — thrown carries a `Reason`, collected a `Code` — so
`TsonHttpException.from` maps through `Diagnostic.Code.of(reason)` into `fetchFailure`, which is the only fetch status
table. Do not add a second switch over `Reason`: two tables held together are free to diverge, and one failure would
then answer one status thrown and another collected. The agreement tests stay
(`TsonHttpCodecTest.aFetchDiagnosticIsAnsweredByItsCode`,
`TsonHttpSchemaSourceIntegrationTest.bothChannelsAnswerAnUnfetchableSchemaAlike`).

**The reason is the code, and must not also be a field.** `Diagnostic` carries no fetch reason, and
`problem.tn` declares no `fetch_reason` enum or field. A second carrier for one fact is free to
disagree with the first — on the wire that means a body stating a code and a reason that contradict each
other, which the schema would still call valid. Pinned by
`TsonProblemSchemaTest.theSchemaCarriesNoSecondCarrierForAFetchFailure`.

`Diagnostic.Code` is the detail vocabulary — mostly verdicts, but see the table above for those that are not:
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
`ValidatorServerTest.aFaultingDocumentIsAnAnswerRatherThanAFailure` shows both in one document. A code upstream
adds and `problem.tn`'s `problem.diagnostic_code` lacks is the drift `TsonProblemSchemaTest` exists to catch.

**`SchemaFetchException` lives in `io.ltr8.tson.base`**, beside the `SchemaSource` interface whose
contract it is — `fetch` names it as the one way a source says "cannot supply this", which is what lets the
classification route on it at all.

**Name hygiene and `LIMIT_EXCEEDED` are refusals the sender fixes.** They answer 400 and 413, never 5xx, and none is a
verdict: ask `isNameRefusal()` and `verdict()` rather than listing codes, and see `design/name-policy.md` for the rules
themselves and why a refusal under this server's own policy is still the client's to fix.

**A refusal is a 400 of its own problem `type`, one per code, and the body carries no policy.** RFC 9457's own pointer
from a problem to deployment-scoped documentation is `type`. So `TsonHttpException.policyRefusal` types a refusal
`…/problems/restricted-script` (the code's name, kebab-cased) and that documentation is where a client is sent to
`/.well-known/tson-deployment`. Why the body carries no `policy` member is a layering decision:
`design/name-policy.md`, "A refusal carries its code and nothing about the policy that judged it". `errors` is an
RFC 9457 extension member, and `problem.tn` declares it optional: the boundary writes it on every problem it renders,
empty where a failure produced no diagnostic, and a business error omits it. `status` is a `problem.status`, an integer
from 100 to 599, so the body cannot state a number that is no HTTP status.

**Both channels type a refusal by its rule.** A refusal at schema load arrives collected, as one of the three name-hygiene
codes, or thrown on a fail-fast path as a `SchemaRefusalException` — the one subtype of a sealed `SchemaValidationException`.
`from` matches it *before* its supertype, which would answer `invalid-schema`, since §8.2 requires a refusal be told
from a malformed schema. Ask `Diagnostic.Code.isNameRefusal()` for the three-code set rather than listing it, as with
`verdict()`. Pinned by `TsonHttpCodecTest.aThrownSchemaRefusalIsAnsweredAsACollectedOneIs`.
