# Computation beside TSON, never inside it

**Status: future-dreaming.** Ideas kept so they aren't lost, not explored and not planned. Nothing here constrains the
one-level design ([`README.md`](README.md)) or the slots reading ([`SLOTS.md`](SLOTS.md)), and nothing in this project
waits on it.

## The idea

TSON stays immutable: no assignment, and no computation beyond composition, refinement and template substitution. The
agent sketches (`agent.tn`, `agent-vm.tn`) already show a way to use immutable data and schemas from something that
computes: a plan is a TSON value typed by a schema, and running it is the interpreter's business.

Generalised, that is a programming-language AST written in TSON. **TSON is the representation of programs, never their
execution.** A program is an immutable, schema-typed value, and its computation lives in whatever interprets it. Data
stays immutable, and computation gets a place of its own.

## Threads worth pulling

**Graal and Truffle.**
- **The schema would be the node vocabulary.** In Truffle a language is a set of AST node classes that specialise
  themselves as they run, and partial evaluation turns the interpreter plus the program into compiled code. A TSON AST
  schema plays the part of those node classes.
- **TSON knows types up front.** Agent admission types every step in one forward pass before anything runs, so much of
  what Truffle discovers by watching execution would be settled at verify time.
- **Truffle frames have slots.** Its frame descriptor and frame slots are close to `agent-vm.tn`'s write-once memory
  slots, and write-once is what partial evaluation wants: after the write, a slot is a constant.
- **Truffle's interop protocol is a natural home for TSON values.** Languages on Graal share objects through messages
  (`readMember`, `invokeMember`, `isArray`). Schema-typed immutable values are a good common currency: a TSON language
  on Graal could make a typed document a first-class value in JavaScript, Python or Ruby, with the schema as its
  description. The most concrete of these threads.

**WebAssembly: branching without losing one-pass verification.** The agent VM bans branching, because a branch would
make a later step's type depend on an earlier value. Wasm's control flow is structured (blocks, `if` and loops each
declare their result types), so validation stays linear and single-pass. Structured branching with declared results
keeps the shape/value invariant: types still never depend on values, only control flow does.

**Dhall and Unison, the closest relatives in spirit.**
- **Dhall** is a total configuration language: it always terminates, has no side effects, and pins imports by hash, as
  TSON's `?sha256=` does. It shows how far computation can go while staying something that can be checked.
- **Unison** addresses code by content: a function's identity is the hash of its AST, and names are metadata. TSON's
  identities, hash pins and §8.2's structural identity of applications already lean that way, so a content-addressed
  TSON AST would give a function an identity the way a schema has one.

## A dial of computation power, as separate layers

| layer | power | checked by | example |
|---|---|---|---|
| values and schemas | composition, refinement, template substitution | the dependency model | today's TSON |
| total expressions | no unbounded recursion; always terminates | one pass | constraints and policies, in the spirit of CEL or Dhall |
| plans | straight-line; effects only through calls | one forward pass | `agent.tn`, `agent-vm.tn` |
| programs | structured branching, loops, functions | a real type checker | a Truffle-style interpreter |

**The middle layer has a use in this project.** The checks that stay in Java in the one-level design ("`query` names
fields of the record at `request`", "a path template matches its PATH parameters") are total predicates. As total
expressions in TSON, any implementation could run them. They would belong to the description reader's layer, not to
schemas, so schemas stay free of computation.

## What keeps it from leaking

- **Its own identity space and its own spec part,** a hypothetical Part 4 that Parts 1–3 never reference. A program can
  name a schema; a schema can never name a program, the same one-way rule as data naming its schema.
- **Effects stay explicit.** As in the agent VM, a call to an interface is the only effect; everything else is pure
  computation over immutable values.
- **One staging story.** Schemas are stage 0 (types), the AST stage 1 (programs), data stage 2 (values at run time). The
  shape/value split is the boundary between stage 0 and the rest, and "a shape never waits on a value" is what keeps the
  bottom stage decidable however much power the stages above it get.

## Programmable middleware, and why LLMs change it

The computation and agent layer is programmable middleware: a place between a caller and the services it uses where a
program composes their calls. There is prior art, and little of it succeeded.

**Why it mostly failed:**
- **Writing it was expensive.** ESBs and WS-BPEL had people writing programs in XML, and the tooling never made up for
  it.
- **Mobile code was a security problem.** General Magic's Telescript (agents carrying code to servers), IBM's Aglets and
  Java applets all foundered on running untrusted code where it could do damage.
- **Composition had no type contracts.** Pipelines broke at service boundaries nothing checked, and a central bus was
  hard to debug.
- **General languages cost more than they gave.** A Turing-complete step inside a request is hard to bound, observe and
  trust.

**What succeeded shares a profile: narrow, typed or total, and the server controls the cost.** GraphQL (a schema plus a
client-written query), SQL, CEL in Envoy and Kubernetes admission policies, AWS Step Functions' state-machine language,
Camel's integration patterns. Temporal succeeded the other way, as code-first durable execution, and gave up being
checkable to do it.

**What LLMs change:**
- **Writing stops being the bottleneck.** A model writes the plan; a person or a policy reviews it. A schema-typed AST is
  a good target: generation can be constrained by the schema, and the result verified before anything runs.
- **Tool calling one step at a time is the current pain.** Every step round-trips through the model, costing latency and
  tokens, and the model sees every intermediate result. The field is converging on letting the model write a program
  that calls the tools: Cloudflare's "Code Mode", Anthropic's write-up on code execution with MCP, and research on
  planning without observing results (ReWOO, LLMCompiler's call DAGs). That is `agent.tn`'s shape: plan up front, run
  without the model in the loop.
- **Prompt injection is the security problem, and this design answers it structurally.** Google DeepMind's CaMeL
  ("Defeating Prompt Injections by Design") has the model write a plan in a restricted language and runs it with
  control flow fixed by the trusted request, so untrusted data can't change what happens next. "Plans cannot branch on
  values" gives that by construction: an instruction injected into a tool's output can change a value, never control
  flow.

**What TSON brings that Python or JSON plans don't:**
- **Checked before execution.** One forward pass types every step against a pinned interface, and the return contract
  is known before anything runs.
- **Data flow you can see.** Selectors name the step they read and slots are write-once, so an information-flow policy
  is a static check at admission: "the output of `read_email` may not reach `send_email.to`". CaMeL tracks this at run
  time; here it falls out of the AST.
- **Bounded by construction.** The step count is the budget, there are no loops, and the server sets the limits.
- **Capability-shaped.** The interface is the only authority a plan holds, and the deployment supplies policy (§8.2),
  never the plan.
- **Content-addressed.** A plan's hash identifies it, so a server caches the compiled agent under it, as `agent-vm.tn`
  describes.

It sits between single tool calls and arbitrary Python: more expressive than the first, verifiable where the second
isn't.

**Where it's hard:**
- **Model fluency.** Models are trained on Python and JavaScript, not a new AST, so the surface grammar matters most.
  CaMeL's answer is a Python subset that compiles down; a TSON plan source could do the same, with constrained decoding
  against the schema as a backstop.
- **The expressiveness ceiling.** Real tasks need conditionals. Without branching, the model *is* the branch: run a
  plan, show the model a summary, issue the next plan. Structured branching (WebAssembly-style) could later be allowed
  only on values from trusted sources, which brings in labels like CaMeL's.
- **Adoption.** Middleware lives or dies by network effects, and MCP has the momentum. The wedge is a TSON plan executor
  over MCP tools: MCP describes a tool's input and output in JSON Schema, which maps onto a TSON interface, and the plan
  becomes code mode with a verifier in front of it instead of a sandbox around it.

## The caveat

A programming language is a decades-long project, and TSON's distinctive value is the format and the schema system. If
it ever goes this way, it grows upward from the agent, the one consumer whose constraints are already worked out, and a
layer is added only when something concrete needs it, so that the language never shapes the schema system beneath it.
