# experiments/ — design explorations kept compiling

`README.md` here indexes them and states the rule: an experiment is compiled and run, not archived, and is not a
commitment. Each directory holds its schema, examples and a README stating the question and what was learned; its
probe tests join `tson-http`'s test source set from `<experiment>/java`.

- A probe failing after a library change is information, not a regression. Update the conclusion or delete the
  experiment; do not patch the probe to pass.
- `meta-service/` graduated: `meta-service.tn` ships in `tson-http` (`design/service-description.md`). What stays
  probes the wire form (`rpc.tn`), the agent layers and the map design's findings.
- **The agent sketches (`agent.tn`, `agent-vm.tn`) are future work.** They press on the interface layer from a
  direction that is not HTTP so the layer is shaped by more than one consumer. Do not build toward them, and do not let
  a decision that is the agent's alone move the descriptor.
- `one-level/` is a design direction for later revisions; its probes pin the ladder as Revision 38 defines it.
