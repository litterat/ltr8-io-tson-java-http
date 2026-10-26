# What an adapter owes the codec

The error boundary every adapter owns, the concurrency shape the demos run under, and why the three adapter test suites
are near-copies. Current form only. The codec side is `design/codec.md`; the demos are `design/demos.md`.

**Invariants**

- Negotiate `Accept` before the handler runs and hold the `Representation`; map every failure through
  `TsonHttpException.from` and nothing else; never overwrite a committed response; write what `problem()` returns
  without judgement of your own.
- A handler that returns without answering is a 500, never a silent 200.
- Every adapter shares one `TsonHttpCodec` across request threads, and the concurrency suites are the only tests that
  would notice a codec wrong under concurrency. Keep them green and add to them, not around them.
- The three adapter suites and the three schema-handler suites ask the same questions. A case added to one is added
  to all three.
- A difference between adapters is real only if it survives a test that depends on neither framework's buffering.

## What an adapter owes the codec

`tson-http-jdk` sets the pattern the other two adapters follow. An adapter is a translation layer and holds
no TSON knowledge of its own; what it *does* own is the error boundary, and that boundary has four jobs:

1. **Negotiate `Accept` before the handler runs**, through `TsonHttpCodec.negotiate`, and hold the
   `Representation` it returns for the rest of the request. Every route produces TSON, and JSON too where the
   codec was built `acceptingJson`, so a client that will take neither is told before the work is done — and
   only the boundary can make that unforgettable. The response and any problem are written in what it chose.
2. **Map a failure through `TsonHttpException.from`, and nothing else.** An adapter never re-decides a
   status. What `from` declines to classify is a fault in this server: catch the rethrow, make it a 500.
3. **Never overwrite a committed response.** Status and headers go out together, so after a handler has
   answered, a later failure cannot become a 500 — attempting it throws inside the boundary and loses the
   original. Log it and close instead. `TsonExchange.committed()` is how the boundary knows.
4. **Write what `TsonHttpException.problem()` returns, and make no judgement of your own.** An internal
   message can name a bound Java class, a path, an internal host or a query, and a client is not the audience —
   but **that is a rule about content, and it lives in `tson-http`**, because an adapter that decided it would
   be one security filter existing as three near-copies, free for one of them to drift lenient. There is no
   unredacted overload for the same reason. The exception goes to a `System.Logger` — which keeps that module
   dependency-free, since it may not take a logging dependency.

   **Not a rule about 5xx**, which is the shortcut it used to be and got a 501 wrong: that status says the
   request was fine and this server could not check it, so the violations the read *did* find are the client's
   to act on and are carried, with the detail saying the list is incomplete. Dropping them left a sender
   nothing to act on, so the next request was byte-for-byte the same one. A 500 (our wiring) and a 502/504
   (our dependency) do answer with status, type and title alone. The filter runs at two levels — the failure's
   own account, then each diagnostic — because a gap outranks a fetch failure, so a 501's list can carry a
   `SCHEMA_UNREACHABLE` naming a host beside violations that are the sender's. **Do not key it on
   `Diagnostic.Code.verdict()`**: that asks whether the document was judged, this asks whom the message is
   about, and they cut differently in both directions — `NOT_IMPLEMENTED` is not a verdict and is disclosed.
   A 4xx is the opposite throughout: its detail and diagnostics are the entire point.

A handler that returns without answering is a bug in the handler, so it is a 500 rather than a silent 200.

**A `com.sun.net.httpserver` with no executor is serial**, and that quietly defeated this project's own
concurrency test for the JDK adapter — `OrderServerConcurrencyTest` drove eight client threads at a server
running every handler on its dispatch thread, so two handlers never ran at once and a race in the shared codec
could not have been caught. The demo now sets a virtual-thread executor. **Virtual threads, not a pool**:
`HttpServer.stop()` does not shut down an executor you hand it, so a platform-thread pool keeps its
non-daemon threads alive and the JVM never exits — which cost a hung ten-minute run to discover.

**No lock is taken on the read or write path**, measured rather than assumed: 48,000 requests across 8 threads
produced 1416 contended monitor entries and not one of them has a tson frame. The `synchronized` methods that
exist are on the resolve and register paths, which run at startup on one thread. That is the "build during
startup, then share for reads" shape working.

**Reading the throughput numbers, because two wrong conclusions came out of them first.** The harnesses put
client and server in one JVM, so a single request stream already costs ~3.7 of 16 cores — client, server, GC
and JIT together. Scaling therefore tops out near 16/3.7 ≈ 4x, which is what both adapters show
(10.5k req/s on one thread, 43k on sixteen, at 14 of 16 cores — saturated). **That is a property of the
measurement, not of the library.** Two things to avoid repeating: comparing runs that differ in more than one
variable (a serial server at 8 client threads against a concurrent one at 8 is not a scaling curve), and
trusting a `ps` sample for CPU — use `OperatingSystemMXBean.getProcessCpuTime` over the timed region, which is
what showed the machine was saturated after a `ps` sample suggested it was at a fifth.

**A shared `HttpClient` costs 16–23%** at four threads and up, so a load generator that shares one measures
itself as much as the server. Both harnesses take a flag for a client per thread, built and warmed before the
timed region.

**Concurrency is tested, not assumed.** Every adapter shares one `TsonHttpCodec` across request threads, and
every other test in this repo is single-threaded — so a codec wrong under concurrency would pass all of them.
`TsonHttpCodecConcurrencyTest` and each adapter's
`demo/OrderServerConcurrencyTest` close that gap, and each task checks its own result: the failure worth
looking for is a crossed or torn value, not a call that threw. Writing them is what found a real race in
`DataBindContext`'s descriptor resolution, since fixed upstream. Keep them green, and add to them rather than
around them.

**The adapter test suites are near-copies on purpose.** `TsonJdkAdapterTest`, `TsonJavalinAdapterTest` and
`TsonHelidonAdapterTest` ask the same questions and assert the same answers, because three adapters over one
codec should be indistinguishable from a client's side and the only way to show that is to ask them the same
things. When adding a case to one, add it to all three. The same goes for the three schema-handler suites,
each of which ends with the same serve-then-fetch loop.

**Where they legitimately differ, and how to tell.** `com.sun.net.httpserver` chunks whatever it is given a
length of 0 for; Jetty holds a short response in its buffer, discovers the length, and sends a
`Content-Length` after all. Both are correct HTTP and a client must depend on neither — which is why the
streaming test uses a body past Jetty's buffer, so it asserts that the document was streamed rather than
asserting a framework's buffering habit. A difference that survives that treatment is worth writing down
here; one that does not is a bad test.


