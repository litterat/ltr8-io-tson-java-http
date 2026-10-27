# Experiments

Design explorations that are worth keeping in view but are not part of what this project ships. Each lives in
its own directory with the files a reader wants -- a schema, its examples, a README stating the question and
what was learned -- and, where it made claims about the resolver, the probe tests that checked them.

**An experiment is compiled and run, not archived.** Its probes join `tson-http`'s test source set from here
(see `tson-http/build.gradle.kts`), so `./gradlew build` keeps them passing and a change in the library that
moves the ground under one fails a test rather than leaving a README that is quietly no longer true. That is
the `demo/schemas/` rule applied to design work: a document nobody exercises stops being true without telling
anyone.

**An experiment is not a commitment.** Nothing in `tson-http`, the adapters or the demos depends on one, and a
probe here failing after a library change is information, not a regression -- the right response may be to
update the experiment's conclusions, or to delete it. When one graduates into the project proper, or into spec
feedback, its directory says where it went and the parts that no longer earn a build are removed.

| Experiment | Question | Where it went |
|---|---|---|
| [`rpc/`](rpc/) | What is the wire form of an interface -- a call and its return as templates closed per method -- and can TSON type a packet end to end with no code per service? | Not landed; first in the order of work after the descriptor. Its home when it lands is a transport-neutral `tson-service` module, not `tson-http`. The orders interface it calls is `tson-http`'s `OrdersExamples` fixture. |
| [`agent/`](agent/) | What does a plan of calls, and the agent it compiles to, need of an interface? | Future work, kept to press on `meta-service.tn`'s interface layer from a direction that is not HTTP. Not built toward. |
| [`one-level/`](one-level/) | What goes if schemas lose the meta ladder -- constructors and their instances in one document, resolved in passes -- and does the data/schema line blur with it? Measured against an API description and the agent sketches (`agent/`). | A design direction, adopted under "correctness over churn": the shape/value dependency model first (Revision 39), then the ladder removed (Revision 40). `data` is already gone in Revision 38. Probes pin the ladder as it stands and namespace types as Revision 38 defines them. |
