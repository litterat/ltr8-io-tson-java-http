# experiments/ — design explorations kept compiling

`README.md` here indexes them and states the rule: an experiment is compiled and run, not archived, and is not a
commitment. Each directory holds its schema, examples and a README stating the question and what was learned; its
probe tests join `tson-http`'s test source set from `<experiment>/java`.

- A probe failing after a library change is information, not a regression. Update the conclusion or delete the
  experiment; do not patch the probe to pass.
- The orders protocol the experiments call is not an experiment: it is `tson-http`'s `OrdersExamples` test fixture,
  the shipped layer's own examples (`design/service-description.md`). An experiment reads it from the classpath and
  never copies it.
- `rpc/` is the protocol's wire form, next in the order of work and homed outside `tson-http` when it lands.
- **The agent sketches (`agent/`) are the draft of the LLM-facing projection** (`BACKLOG.md`, "An LLM-facing
  projection of a protocol"), which they are built toward. They press on the protocol layer from a direction that is
  not HTTP, so the layer is shaped by more than one consumer; a decision that is the projection's alone lands in the
  projection and never moves the descriptor.
- `cognitive-load/` is a protocol for measuring the syntax against LLMs, not code; it has no probe, and its item banks
  are the existing probes and the bundled schemas. Do not run it from a session on your own initiative; it costs
  model calls the user decides on.
