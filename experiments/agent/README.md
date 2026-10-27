# The agent -- a plan of calls, and the agent it compiles to

**Status: future work, kept for what it says about the descriptor.** An agent executing a plan of calls is the
consumer that presses hardest on an interface -- it needs a method addressable, its request and response typed, and
its errors declared -- so sketching it is how `meta-service.tn`'s interface layer gets tested against something that
is not HTTP, in the way a second implementation says whether an API holds up. The two layers are kept resolved and
probed for that reason and no other. **Do not build toward them, and do not let a decision that is the agent's alone
move the descriptor.** Nothing in `experiments/rpc/`'s order of work waits on them.

The interface a plan calls is the orders example that ships as `tson-http`'s test fixture (`OrdersExamples`); each
CALL an agent executes is one `call` of `rpc.tn` (`experiments/rpc/`). `experiments/one-level/` reads these layers as
evidence for its own direction: a plan is a one-level document, and the VM's admission is the resolver's algorithm
(`one-level/COMPUTATION.md`, `SLOTS.md`, and the agent section of its README).

## Three layers, as a compiler has

A plan SOURCE (a surface grammar, still to write) is read to a `plan` -- [`agent.tn`](agent.tn), the AST -- which
compiles deterministically to an `agent` -- [`agent-vm.tn`](agent-vm.tn), the bytecode -- that a server admits,
verifies in one forward pass against the pinned interface, and executes. The likely deployment is a gateway agent
processor whose calls are RPC to the box hosting each interface; the api gateway does the same for one call.

`agent.tn` is the plan, resolved -- named as a resolved schema is named against its source: the surface is plan
source, this is the plan, and an `agent` is what it compiles to. Everything the surface leaves implicit is
structural here: a reference is a `selector` (a step, then `segment`s -- `field`, `index`, or a `filter`),
distinguished from a constant by shape; an argument is an `arg` tree whose canonical form folds constants
maximally, so `record` and `array` appear only on the spine above a reference; `or` is a step's failure
substitute, a field because it is executed. `agent-vm.tn` is the lowering: memory slots are step indices, so
flattening is slot assignment; the pool interns names, constants, types and methods, kind-checked by index; the
`instruction` choice is `@disjoint` -- a bare mnemonic beside a labelled operand record. Admission is one forward
pass in both layers: straight-line code has no merge points, so checking and inference coincide, and the return's
inferred type is the derived response contract, known before execution.

This is the dataflow design arrived at as a compiler rather than a packet, with Cap'n Proto's promised answers as the
precedent; `rpc.tn` stays `call` and `return` only.

Both resolve under this repo's identities, `…/io/ltr8/http/agent.tn` and `agent-vm.tn`. `name` is an identifier
family declared once in `agent.tn` and imported by the VM, so [TSON-DATA] §8.2's hygiene judges a step name wherever
it stands; a constant is `constant => dynamic`, [TSON-SCHEMA] §7.8's widest scoped instance, so a literal names its
own type -- from this schema or from a foreign one through a nested `!!schema` -- and is validated in full, and a
value naming no type is refused. The AST's top type is `plan`, since the VM imports the AST and two `agent`s would
collide.

## Decisions this leaves, in the order to take them

1. **One interface per plan, or several?** Both layers carry one `interface: uri`, where an agent is expected to call
   across interfaces. The reconciliation that changes nothing here: the gateway publishes an interface that `extends`
   the ones it fronts, and a plan names that. `extends` was built for exactly this.
2. **Business errors.** A failed call without `or` aborts with `agent_error`, whose `detail` is text. The interface
   declared that call's errors as typed values composing `problem`; the abort should carry the one that occurred, and
   `agent_error` should compose `problem` like every other error here, so the api gateway and the agent processor
   answer failures in one shape.
3. **`version: uint8 = 2`** on the agent duplicates what the identity `agent-vm.tn` already says ([TSON-JSON] §3.5);
   keep it only if the binary form wants a magic byte, and say so.
4. **The surface grammar reads schemaless**, method names as preserved unknown type refs and `@or`/`@interface` as
   preserved annotations -- which sidesteps "which namespace may a document bind" (below) rather than answering it.
   One hazard to design out: a schemaless read resolves built-ins first, so a method named `date`, `uuid` or `text`
   would be parsed as the built-in, not preserved. The surface needs to reserve those or qualify method names.

## Open questions

**Which namespace may a document bind?** `!!schema` binds the type namespace and `!name` resolves against it, one
hop. A plan or batch document wants to bind an *interface* -- to say `!place_order { request: { … } }` and have the
step checked against the method -- and cannot: `place_order` is a key inside `orders`, not a type name, and a
qualified name stands only at a `ref<T>` slot in a schema. A reference in a data document is open, with the
one-level work (`experiments/one-level/README.md`, "The reference atom"); the URL namespace being hierarchical
(`/orders/{id}/items` under `/orders/{id}`) while a resource key in `meta-service.tn` is a flat template is the same
question, and one answer should serve both.

**A disjoint choice with an enum variant is not read tag-free.** `agent-vm.tn`'s `instruction => ( simple_op | op )`
is `@disjoint` -- string class beside brace class -- and the resolver accepts the assertion, but the reader refuses a
bare `RET` at that position and asks for a tag. Measured precisely in `AgentProbe`: `(text | integer)` reads
untagged, so tag-free dispatch exists; an enum variant is what it does not cover. Until it does, a compiled agent
writes `!simple_op RET` and `!op { make: 0 }`, and the VM's "tag-free" claim describes the schema rather than the
implementation. **Not filed** -- a library ask, raised when the agent is built, if it is.

## Files

- `agent.tn` -- the plan (AST); the plan-reading rules for the surface layer are in its header `@doc`.
- `agent-vm.tn` -- the agent (bytecode) it compiles to: pool, slots, stack code; the execution model in its `@doc`.
- `java/…/experiment/agent/AgentProbe` -- both layers resolve; a plan of references and a compiled agent read; a
  constant names its own type, across schemas, and one naming none is refused; a malformed or mixed-script step name
  is refused by the `name` role; and the disjoint-choice gap, written to fail the day it closes.
