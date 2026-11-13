# The agent -- a plan of calls, and the agent it compiles to

**Status: future work, kept for what it says about the descriptor.** An agent executing a plan of calls is the
consumer that presses hardest on a protocol -- it needs an exchange addressable, its request and response typed, and
its errors declared -- so sketching it is how `service.tn`'s protocol layer gets tested against something that
is not HTTP, in the way a second implementation says whether an API holds up. The two layers are kept resolved and
probed for that reason and no other. **Do not build toward them, and do not let a decision that is the agent's alone
move the descriptor.** Nothing in `experiments/rpc/`'s order of work waits on them.

The protocol a plan calls is the orders example that ships as `tson-http`'s test fixture (`OrdersExamples`); each
CALL an agent executes is one `call` of `rpc.tn` (`experiments/rpc/`). Two notes beside the sketches look further
out: [`COMPUTATION.md`](COMPUTATION.md), on computation as a layer beside TSON, a plan being its straight-line middle;
and [`IDENTITY.md`](IDENTITY.md), on whose plans they are and what an agent may do on someone's behalf.

## Three layers, as a compiler has

A plan SOURCE (a surface grammar, still to write) is read to a `plan` -- [`agent.tn`](agent.tn), the AST -- which
compiles deterministically to an `agent` -- [`agent-vm.tn`](agent-vm.tn), the bytecode -- that a server admits,
verifies in one forward pass against the pinned protocol, and executes. The likely deployment is a gateway agent
processor whose calls are RPC to the box hosting each protocol; the http gateway does the same for one call.

`agent.tn` is the plan, resolved -- named as a resolved schema is named against its source: the surface is plan source,
this is the plan, and an `agent` is what it compiles to. Everything the surface leaves implicit is structural here: a
reference is a `selector` (a step, then `segment`s -- `field`, `index`, or a `filter`), distinguished from a constant by
shape; an argument is an `arg` tree whose canonical form folds constants maximally, so `record` and `array` appear only
on the spine above a reference; `or` is a step's failure substitute, a field because it is executed. `agent-vm.tn` is
the lowering: memory slots are step indices, so flattening is slot assignment; the pool interns names, constants, types
and methods, kind-checked by index; the `instruction` choice is `@disjoint` -- a bare mnemonic beside a labelled operand
record -- and [TSON-SCHEMA] §5.4 reads a disjoint choice by each value's form, an enum variant included, so a bare `RET`
and an untagged `{ call: … }` each select their variant and a compiled agent writes no tag
(`AgentProbe.anUntaggedInstructionReadsAtADisjointChoice`). Admission is one forward pass in both layers: straight-line
code has no merge points, so checking and inference coincide, and the return's inferred type is the derived response
contract, known before execution.

This is the dataflow design arrived at as a compiler rather than a packet, with Cap'n Proto's promised answers as the
precedent; `rpc.tn` stays `call` and `return` only.

Both resolve under this repo's identities, `…/io/ltr8/http/agent.tn` and `agent-vm.tn`. `name` is an identifier
family declared once in `agent.tn` and imported by the VM, so [TSON-DATA] §8.2's hygiene judges a step name wherever
it stands; a constant is `constant => meta.declared`, the kernel's widest declared type, so a literal names its own
type -- from this schema or from one the document imports, a nested directive being a parse error -- and is
validated in full, and a value naming no type is refused. The AST's top type is `plan`, since the VM imports the AST
and two `agent`s would collide.

**A dependent field gives a constant a second shape, and a step none.** A record field typed `kind!` is
read per instance against the type its sibling `kind` names, so a constant could be the pair `{ type: meta.type_ref
value: type! }`, its type a type reference in a field rather than a tag, binding to an `Object` component. Neither
shape is chosen here: the tag is one token, the pair two fields, and both check the value in full. A step cannot type
its parts that way. `or` is to validate against its method's response, which is a projection through `method` that
[TSON-SCHEMA] §5.2 records as deliberately absent; and `method` itself, as `meta.ref<service.exchange>`, could not be a
sibling, which is typed by a reference over a constructor or by `meta.type_expr`, and never by a reference naming
values. Neither is the plan's `interface: uri`, which names a schema. So a step's typing stays
the admission verifier's.

## Decisions this leaves, in the order to take them

1. **One protocol per plan, or several?** Both layers carry one `interface: uri`, where an agent is expected to call
   across protocols. The layer has no inheritance between protocols, so a gateway fronting several either declares
   one protocol of its own holding their exchanges, which a plan names, or a plan names several and this is decided.
2. **Business errors.** A failed call without `or` aborts with `agent_error`, whose `detail` is text. The protocol
   declared that call's errors as typed values composing `problem.details`; the abort should carry the one that
   occurred, and `agent_error` should compose `problem.details` like every other error here, so the http gateway
   and the agent processor answer failures in one shape.
3. **`version: uint8 = 2`** on the agent duplicates what the identity `agent-vm.tn` already says ([TSON-JSON] §3.5);
   keep it only if the binary form wants a magic byte, and say so.
4. **The surface grammar reads schemaless**, method names as preserved unknown type refs and `@or`/`@interface` as
   preserved annotations -- which sidesteps "which namespace may a document bind" (below) rather than answering it.
   One hazard to design out: a schemaless read resolves built-ins first, so a method named `date`, `uuid` or `text`
   would be parsed as the built-in, not preserved. The surface needs to reserve those or qualify method names.

## Open questions

**Which namespace may a document bind?** `!!import` binds the type namespace and `!name` resolves against it, one hop. A plan
or batch document wants to bind a *protocol* -- to say `!place_order { request: { … } }` and have the step checked against
the exchange -- and cannot: `place_order` is a key inside `orders`, not a type name, so it can stand only where a slot is
typed `meta.ref<service.exchange>`, never as a tag. A reference in a data document, to a type or to a value, is a name judged
against the closure the document imports ([TSON-SCHEMA] §3.3.4), and an argument list there is a parse error ([TSON-DATA]
§7.2.5): at `m: meta.ref<service.exchange>`, `orders.place_order` reads and `orders.no_such` is refused ("'orders' has no key
'no_such'"), and at `m: meta.ref<order>` a value entry `o1` reads while `order`, a type, is refused
(`UpstreamGapsTest.aValueReferenceInADataDocumentIsJudged`). The URL namespace being hierarchical (`/orders/{id}/items` under
`/orders/{id}`) while a resource key in `service.tn` is a flat template is the same question, and one answer should serve
both.

**The pool's `type` and `method` entries are typed `name`** in `agent-vm.tn`, and want to be `meta.type_ref` and
`meta.ref<service.exchange>`, judged on read as any reference in data is. The names they hold are the interface's, while a
reference resolves in the governing schema's closure, and a field naming the scope for later fields (`interface: uri`)
is a dependent pair no dependent field reaches, its sibling naming a schema rather than a type. The answer TSON has is
to make the scope visible instead of computed: the plan governed by a derived schema importing both `agent.tn` and the
interface, the pattern `experiments/rpc/orders-wire-1.tn` uses for RPC packets. That fits decision 1 if a plan names
one protocol, and retires `interface: uri` as a field.

## Files

- `agent.tn` -- the plan (AST); the plan-reading rules for the surface layer are in its header `@doc`.
- `agent-vm.tn` -- the agent (bytecode) it compiles to: pool, slots, stack code; the execution model in its `@doc`.
- `COMPUTATION.md` -- future-dreaming: computation as layers beside TSON, never inside it, and why LLMs change
  programmable middleware.
- `IDENTITY.md` -- future-dreaming: a research map of identity, delegation and attestation for agents acting on
  someone's behalf, and where TSON's properties could help.
- `java/…/experiment/agent/AgentProbe` -- both layers resolve; a plan of references and a compiled agent read; a
  constant names its own type, across schemas, and one naming none is refused; a malformed or mixed-script step name
  is refused by the `name` role; and an untagged instruction reads at the disjoint choice.
