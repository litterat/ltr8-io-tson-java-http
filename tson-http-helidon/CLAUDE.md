# tson-http-helidon — adapter for Helidon 4.2.2 SE (`io.ltr8.tson.http.helidon`)

The same parallel types (`TsonContext`, `TsonHandler` with `asHandler`/`install`, `TsonSchemaHandler`) plus the one
thing no other adapter can offer: `TsonMediaSupport`, an implementation of Helidon's `MediaSupport` SPI. Register it
once and a plain handler reads and writes TSON through `req.content().as(Order.class)` and `res.send(order)` with no
TSON-specific code: same codec, same validation, same diagnostics. Over a codec built `acceptingJson` it claims and
writes JSON the same way: which bodies it claims is the codec's answer (`requireTsonBody`), what it writes is
`negotiate`'s. Read `design/adapters.md` first.

Rules that bite here:

- **`TsonHandler.install` is not optional with `TsonMediaSupport`.** The read happens inside Helidon's entity machinery
  before any handler runs, so without `install` a body that breaks its schema gets Helidon's own error page and the
  diagnostics are lost, and a non-TSON body is a 500 instead of a 415 (`UnsupportedTypeException`, raised when no reader
  claims the type). `install` maps that too, deciding which side failed by asking the codec about the *request's*
  content type: not readable is the client's 415, readable means the write side failed and is this server's 500.
- `install` negotiates and falls back to TSON where nothing is acceptable, as on Javalin.
- The schema route is registered with `any()`; the claim against the description names the declared template.
- The suite is a near-copy of `TsonJdkAdapterTest`; a case added here is added to the JDK and Javalin suites too.
