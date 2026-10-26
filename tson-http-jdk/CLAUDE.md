# tson-http-jdk — adapter for `com.sun.net.httpserver` (`io.ltr8.tson.http.jdk`)

Zero external dependencies, so it is both the "no framework needed" demo and the reference the other two adapters are
read against: when an adapter's behaviour looks odd, diff it against this one. Three types: `TsonExchange` (one request
and its response, in TSON terms), `TsonHandler` (what a route is written as; `asHttpHandler` wraps it in the error
boundary) and `TsonSchemaHandler` (serves schemas at their identity paths). Read `design/adapters.md` first; the demos
are `design/demos.md`.

Rules that bite here:

- An `HttpServer` with no executor runs every handler on its dispatch thread, which silently defeats a concurrency
  test. The demo sets a virtual-thread executor; never a platform pool, since `stop()` does not shut down an executor
  you hand it and the JVM never exits.
- `com.sun.net.httpserver` chunks whatever it is given a length of 0 for. A test must not depend on it; the streaming
  test uses a body past Jetty's buffer so it asserts streaming rather than a framework's habit.
- `TsonExchange.committed()` is how the boundary knows not to overwrite a response.
- The schema route is served from a `createContext("/")` prefix; the claim against the description names the declared
  template `/{+schemaPath}`, not the registered path.
- `ValidatorServer` (`src/demo`, with `validate-1.tn`, `validate-api-1.tn` and `validator.html` in `src/demo/resources`)
  is the one demo that takes the schema from the request. It is JDK-only on purpose, and its rules are in
  `design/demos.md`: a fresh `Tson` per request, nothing fetched, a non-conforming document is a 200.
- `ReadmeTest` here executes the README's `curl` examples against the real demo and the validator.
