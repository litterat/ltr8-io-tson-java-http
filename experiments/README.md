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
| [`meta-service/`](meta-service/) | Can one meta layer describe an interface (methods), a web service (operations), and a service that is both -- and how does an operation refer to a method declared elsewhere? | Graduated: `meta-service.tn` ships in `tson-http`, replacing `meta-http.tn`. The reference problem became tson-java's `SPEC-FEEDBACK.md` entry *"a namespace should be a value"*, which landed in Revision 38. What stays probes the wire form, the agent layers and the map design's findings. |
| [`one-level/`](one-level/) | What goes if schemas lose the meta ladder -- constructors and their instances in one document, resolved in passes -- and does the data/schema line blur with it? Measured against an API description and the agent sketches. | A design direction, adopted under "correctness over churn": the shape/value dependency model first, then the ladder and `data` removed. Probes pin the ladder as it stands and namespace types as tson-java PR #753 landed them. |
