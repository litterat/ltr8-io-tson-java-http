# tson-http-javalin — adapter for Javalin 6.7.0 (`io.ltr8.tson.http.javalin`)

Jetty 11, `jakarta.*` namespace, consumed as the JPMS module `io.javalin`. The same three types as the JDK adapter,
deliberately: `TsonContext`, `TsonHandler` (with `asHandler`) and `TsonSchemaHandler`, plus `TsonHandler.install(app,
codec)`, which maps a `TsonHttpException` escaping a *plain* Javalin route to the same problem body. Read
`design/adapters.md` first.

Rules that bite here:

- `install` negotiates too, and falls back to TSON where nothing is acceptable: the failure being reported is not a 406
  and the client is still owed a body.
- The schema route needs Javalin's `/<path>` form, since an identity path has slashes that only the angle form matches
  across. The claim against the description still names the declared template `/{+schemaPath}`.
- Jetty holds a short response in its buffer and sends a `Content-Length` after all; a test asserting chunking or a
  length is asserting a framework habit, not HTTP.
- The suite is a near-copy of `TsonJdkAdapterTest`; a case added here is added to the JDK and Helidon suites too.
