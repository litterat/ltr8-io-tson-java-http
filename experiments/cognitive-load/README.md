# Cognitive load -- measuring TSON's schema syntax against LLMs

**Status: a proposed experimental protocol, not yet run, and baseline-first.** It measures the schema syntax as it
stands, feature by feature, derives hypotheses from that, and only then designs variants and tests them against the
baseline. It has no probe of its own; its materials are the bundled schemas and existing probes. Its subjects are LLMs
only, never people.

## The question

Given the schema syntax as written, which features does an LLM read and write correctly with little effort, which cost
it effort, and which does it get wrong? It is asked with no proposal in hand: a proposal made before the baseline risks
improving a feature that was never hard while the one that was goes unmeasured.

The question is live because the schema syntax is still moving in tson-java (the sibling repo,
`../ltr8-io-tson-java`). A schema's map is `schema => !namespace { key_type: type_name  value_type: declared }`, so
every entry is a value of some declared type, and the author is still deciding the syntax that writes it. A measured
profile is evidence for that decision; a list of suspicions is not.

## The feature inventory

The baseline measures features, not schemas. Each feature has its own item bank, drawn from the bundled schemas as
written, and each item asks a model to **classify** a construct, **predict** whether a document is admitted, or
**write** one.

| Feature | Example | What an item asks |
|---|---|---|
| Entry arrow and declaration | `name => ...` | Classify the entry: a type, a constructor, a value, an annotation. |
| Constructor application | `!C { ... }`, `!enum [A B]` | Classify what `!C` produces; write one. |
| Atom refinement | `!integer ^ { min: 0 }` | Predict which tokens it admits; write a bounded atom. |
| Restated field | `T & { x?: ~"hello" }` | Predict what the composition admits that its source refused, and the reverse; whether a name restates a field or adds one. |
| Composition | `A & B & { ... }` | Predict the merged fields; classify admissible operands. |
| Subtraction | `head - { fields }` | Predict the fields left, and whether the result IS-A `head`. |
| Bare record | `{ sku: text }` | Write one from a prose shape. |
| Definition marks | `abstract { ... }`, `final { ... }` | Predict whether a value, or a subtype, is admitted. |
| Field optionality | `name?: T` versus `name: T?` | Predict omitting the field, and writing `_` in it, under each spelling. |
| Field modifiers | `~ 8080`, `= v`, `=?` | Predict what an omitted or written value becomes. |
| Choice | `( circle \| square )` | Predict the variant a value reads as; when a tag is needed. |
| Labelled field groups | `{ ( on: date \| note: text ) }` | Predict a document with both members, with neither, with one. |
| Container sugar | `[T]`, `{K => V}`, `[T, U]`, `[T; 1..]` | Classify the desugared constructor; predict at the size bounds. |
| Templates | `<T> { item: T }`, `box<order>`, `uuid_pair => <B> { first: uuid  second: B }` | Predict the closed type an application yields. |
| Name-holding slots | `meta.type_ref`, `meta.ref<T>`, `orders.exchanges.place_order` | Predict which names the slot admits: a type's or a value's. |
| Namespace types and values | `exchanges => !meta.namespace { ... }`, `orders => !service.protocol { exchanges: { ... } }` | Classify the entry; predict what its keys may hold. |
| Positional form | `"/orders" => { GET => ... }` | Predict what the map stands in for, and what a new required field breaks. |
| Annotations | `doc => @annotation text`, `@service.safe get_order => { ... }` | Predict where an annotation on an entry lands. |
| Directives | `!!id`, `!!meta`, `!!import`, `!!schema` | Predict which names are visible, and at which level. |
| Void and absence | `_`, an omitted key | Predict admission of each at `a?: T`, `a: T?` and `a: T`. |
| Quoting | `"/orders/{id}"` | Predict whether a token needs quotes; write a key that does. |
| Patterns | `text_type { pattern: "[a-z]+" }` | Predict a token against an I-Regexp (whole-token; `^`, `$` literal). |
| Cross-schema references | an imported type named in a field | Predict whether, and from where, the name resolves. |
| The `_type` convention | `integer_type`, `media_type_type` | Classify constructor or type with the suffix the only signal. |
| Declaration order | a name used before it is declared | Predict whether the schema resolves. |

**The inventory is the unit of reporting.** Every experiment's result is a per-feature profile (accuracy, error class
and effort for each row, by model), and a proposal is derived from that profile alone.

## Method, common to every experiment

Phase 1 needs only the doc stripper and the item banks over the bundled schemas as written. The variant printer is a
Phase 3 tool and is not built until a hypothesis calls for it.

- **The compiler is the judge wherever a judge is needed.** tson-java's resolver (its `tson` CLI and Java API) grades
  every generated schema and every predicted admission. Its diagnostics already name error classes a feature can be
  charged with: a type applied where a constructor was needed, `^` over a record whose fields `&` restates, a facet body in
  field grammar, an unresolved constructor. An LLM judge is used only for prose, with a rubric drawn from the
  stripped documentation, and never to grade syntax.
- **The stripped documentation is the answer key.** Stripping `@doc` and `@service.summary` from a layer produces the
  stimulus; what was stripped is the rubric for comprehension items and the target for the unknowns experiment.
- **The item banks already exist.** The bundled schemas, `meta-kernel.tn`, `meta.tn`, `core.tn` and `net.tn` in the
  sibling's `spec/m/`, and this repo's `service.tn`, `http.tn`, `deployment.tn`, `problem.tn` and the orders fixtures under
  `tson-http/src/test/resources/io/ltr8/tson/http/orders/`. The pinned diagnostics in `UpstreamGapsTest` and
  `MediaTypeKindProbeTest` (`tson-http/src/test/java/io/ltr8/tson/http/`): each is a planted error with its exact
  message. Each item is tagged with the inventory features it exercises.
- **Phase 3 variants are printed mechanically from one resolved form** (the sibling's `spec/m/*-resolved.tn` for the
  bundled layers), so they are semantically identical and a difference in outcome is one the syntax made. No prompt
  names a variant or says which is in use today.
- **Subjects.** Anthropic's current family at least (Fable, Opus, Sonnet, Haiku), and a small open model where one is
  available, because load shows in the smaller models first. The spread across models is the signal: a feature every
  model fails is a syntax problem; a feature only the small models fail is a capability gradient.
- **Controls.** Fixed prompts, fixed temperature, several samples per item, item order randomised.
- **Not evidence.** A model's own report of what it finds clear, including the drafting model's. Measurement replaces
  introspection.
- **Scoring.** Per item, correct or incorrect against the compiler or the rubric. The baseline is reported as a
  per-feature difficulty profile: for each feature, accuracy by model with the spread beside the mean, between a
  ceiling (the documented layer) and a floor (the stripped layer).

## Phases

1. **Baseline.** The existing syntax, Revision 40, measured feature by feature over the inventory. The output is the
   per-feature profile.
2. **Hypotheses.** Derived from the profile: which features are hard, and why (an operator overloaded, a convention
   carrying meaning, a distinction the syntax does not mark, an order dependence), naming feature and cause together.
3. **Variants.** Designed against the hard features, printed from the resolved form, and tested with the same items,
   so each is compared to the baseline on the feature it targets and on every other.

**No variant is tested before its feature is shown hard in Phase 1.**

## The experiments

Ordered by weight: what a result adds to the profile, against its cost and noise. Each runs over the inventory in
Phase 1 and over variants in Phase 3, with the same items.

### 1. Minimal-pair classification

**Measures:** whether a model reads each feature correctly, directly and cheaply.

**Weight: High.** It needs no generation and scores against the resolved form with no judge at all.

**Steps:**
1. Per feature, write forced-choice items over a stripped layer's entries, in minimal pairs differing only in that
   feature (`name?: T` beside `name: T?`).
2. Phase 1: run them over the layers as written. Phase 3: render the same entries under each variant.
3. Score each answer against the resolved form or the compiler's verdict.
4. Report accuracy per feature, and per-feature heat across models.

**Reads as:** a feature every model fails is hard in the syntax; one that splits the models is a gradient. Pairs that
fail together point at a shared cause, which Phase 2 names.

### 2. Compiler-judged generation

**Measures:** production load, graded objectively, with every error charged to a feature.

**Weight: High.** It is the task the syntax exists for, and the compiler grades it.

**Steps:**
1. Write target domains in prose (an orders service, a deployment descriptor, a small type library), naming what to
   declare, never how.
2. Give the model the stripped layer it should write against, plus the prose, and ask for a schema. Phase 3 repeats
   this per variant.
3. Compile every answer.
4. Classify every diagnostic by the inventory feature it implicates, and by error class within it.
5. Report error rates per feature by model, and as the headline, the share of schemas that compile clean.

**Reads as:** the features carrying most errors are the production load. One hard to read in experiment 1 but rarely
written wrong is one models avoid rather than master.

### 3. Unknowns elicitation

**Measures:** which documentation is load-bearing, and for which features.

**Weight: High.** Only it says what the `@doc` text should keep, and where syntax could carry more.

**Steps:**
1. Give the model the stripped layer.
2. Ask it to list what it cannot determine from the text alone.
3. Cluster the questions across samples and models, and tag each cluster with the feature it is about.
4. Match each cluster to the stripped `@doc` paragraph that answers it, or to none.
5. Rank paragraphs, and features, by how often their content was asked for.

**Reads as:** a paragraph nobody asks for restates the syntax; a feature every model asks about is one the syntax does
not explain; a cluster no paragraph answers is a gap in the documentation.

### 4. Thinking tokens per feature

**Measures:** the effort to reach a correct answer, per feature, not only whether it is reached.

**Weight: Medium to High.** It is the most direct per-feature load map, but it is valid only within a model, since
verbosity differs across models.

**Steps:**
1. Run experiment 1's items with extended thinking on.
2. Keep the items every model eventually gets right.
3. Compare the distribution of thinking tokens per feature within each model; in Phase 3, per variant within each
   feature.

**Reads as:** a feature answered correctly at high cost is hard in a way accuracy hides, and joins the hard ones.

### 5. Stripped-layer explanation against the docs

**Measures:** comprehension of each entry in prose.

**Weight: Medium.** An LLM judge grades it, so it is noisier than the experiments above.

**Steps:**
1. Ask the model to explain each entry of a stripped layer.
2. Judge each explanation against the stripped doc on a fixed rubric: what the entry declares, what it admits, what it
   refuses.
3. Report rubric scores per feature by model.

**Reads as:** corroboration for experiments 1 and 3. Where it disagrees with them, the judge is the first suspect.

### 6. Repair from a diagnostic

**Measures:** whether the diagnostics carry a reader past a feature the syntax does not.

**Weight: Medium.** The bank is ready-made, but the result speaks to the diagnostics as much as to the syntax.

**Steps:**
1. Take each pinned probe's planted error, tagged with its feature; hand over the schema and the exact diagnostic.
2. Ask for the fix, and compile it.
3. Report the fix rate per feature and model, and separately the rate with the diagnostic withheld.

**Reads as:** a wide gap between the two rates means the diagnostic is carrying the reader. A small gap on a feature
experiment 2 shows to be often written wrong means its diagnostic says too little.

### 7. Length as the stressor

**Measures:** how each feature's load grows with the size of a layer.

**Weight: Medium.** Load hides in a short layer and shows at `meta.tn`'s size.

**Steps:**
1. Embed the same target entries in layers of increasing length, drawn from the bundled schemas.
2. Run experiment 1's items on the target entries only.
3. Report accuracy against length per feature.

**Reads as:** a feature whose accuracy falls with length carries its signal in the reader's memory of the layer, not
on the line: the signature of a convention or an order dependence.

### 8. Learning curves

**Measures:** whether a feature is unfamiliar or hard.

**Weight: Low to Medium.** It separates the two, at the cost of many runs.

**Steps:**
1. Vary the number of worked examples in the prompt, from none upward.
2. Run experiment 1's items at each count.
3. Find the plateau per feature and model.

**Reads as:** a feature that starts low and plateaus high was only unfamiliar; one that plateaus low is hard.

### 9. A meta layer from the stripped kernel

**Measures:** whether a model can extend the vocabulary.

**Weight: Low.** The most informative single artefact, but most attempts fail and grading is by hand.

**Steps:**
1. Give the stripped kernel and `meta.tn`; ask for a layer describing a service, in the shape of `service.tn`.
2. Compile each attempt, and tag each diagnostic with its feature.
3. Keep the diagnostics and the attempts as a record of the failure shapes per model.

**Reads as:** a record, not a score: which features load a layer author, which experiment 2's subjects never are.

## Order of work

1. Build the doc stripper and tag the item banks by feature.
2. Phase 1: run experiment 1, then 2 and 3, then 4.
3. Derive the Phase 2 hypotheses from the profile.
4. Only then build the variant printer, rendering each variant from resolved output.
5. Phase 3: rerun the experiments the hypotheses call for, over the variants.

Nothing here changes the spec. The result feeds tson-java's syntax decisions, and `UPSTREAM.md` as spec
feedback if the spec should hear it.

## What this experiment is not

- **Not a preference survey.** Asking a model which syntax it prefers measures nothing.
- **Not a human study.** What it finds about people is at most a hypothesis for one.
- **Not a test of a preferred proposal.** The variants in the appendix are candidates, and the baseline may discard
  every one of them.
- **Not a commitment to any variant.** A variant scoring best is evidence for the decision, not the decision.

## Appendix: candidates already in discussion

These are prior suspicions, raised while writing a meta layer. They are tested only if Phase 1 shows the feature they
address to be hard: the constructor/type/value distinction, the `_type` convention, or declaration order.

**The suspicion.** Under one level every entry is a value of a declared type: when that type is a constructor the
entry's value is a type, otherwise it is data, and `!T body` is the same instantiation either way. So a line
`name => !X { ... }` cannot be classified without knowing whether `X` is a constructor, and only `X`'s name says so.
The `_type` suffix (`integer_type`, `text_type`, `media_type_type`) carries most of that; `record`, `enum`, `choice` and
`namespace` carry none of it. Across a layer the size of `meta.tn`, that would be a lookup per line.

Refinement, `^`, is different in kind: it follows `!name` and refines a type a constructor built by values, under
the rules that type states, facet by facet (bounds inward, member sets shrinking) or a reference's `of` (narrowed to a
subtype), so `int8 => !integer ^ { min: 0  max: 255 }` and `identifier_ref => !meta.type_ref ^ { of:
unicode.identifier_type }` are one rule. A record is never refined: it derives by composition, `&`, whose body is field
definitions, and a field restated there narrows by the same rules, its modifier (`=` fixed, `~` replaceable) or its
type (one IS-A the inherited), so `defaultbase => base & { x?: ~"hello" }` restates `x` where a name `base` lacks adds
a field. **The parser note:** the operator tells the parser the body's grammar before anything resolves, values after
`^` and field definitions after `&`, so a bare `T ^ { ... }` is a parse error naming `T & { ... }` ([TSON-SCHEMA]
§12.1) and `!R ^ { ... }` over a record is a resolver error naming the same (§4.3).

**Syntax variants**, named neutrally in any prompt:

- **V0** -- Revision 40: `!` marks an instance, so `!C { ... }` is a value of `C` (a type when `C` IS-A `meta.type`)
  whether `C` is a constructor or an ordinary type; `!I ^ { ... }` refines a type a constructor built by values; `A & B
  & { ... }` composes, the one way to derive a record. This is the baseline itself.
- **V1** -- `!` removed from every type-producing form (`integer => integer_type { min: 0 }`,
  `age => integer ^ { min: 0 }`, which V0 refuses as a parse error), and kept only where a value follows; the operator
  still decides the body.
- **V2** -- an infix construction operator, `integer => integer_type <- { min: 0 }`, beside `^` and `&`; `!` only on
  values. tson-java proposed it as spec feedback and withdrew it: under one level the head's chain says whether an
  entry is a type, so there is no head-position ambiguity for it to remove. It stays a variant because the question
  here is load on a reader, which that argument does not settle.
- **V3** -- V2 with atom refinement also spelled `<-` (`small_int => integer <- { max: 10 }`), so one operator takes
  a body of values, whether it constructs or refines, `^` goes, and `&` alone takes field definitions.

**Naming:** N0, the `_type` suffix as it stands; N1, neutral names with the suffix removed; N2, names replaced by
meaningless but pronounceable tokens, measuring how much the names carry. **Ordering:** O0, declare-before-use, as the
bundled schemas are written; O1, entries shuffled.

**Hypotheses**, to be confirmed as Phase 2 hypotheses only if the profile supports them:

- **H1.** Classifying an entry as constructor, type or value is the dominant load of reading and writing a schema.
- **H2.** The `_type` convention carries most of that load, so N1 and N2 degrade accuracy more than any operator
  variant improves it.
- **H3.** An explicit operator (V2, V3) lowers classification error but raises generation error by adding grammar.
- **H4.** A specific, small set of `@doc` paragraphs is load-bearing, and the rest restates what the syntax says.
