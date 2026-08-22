# tson-java-http

Integrating [TSON](https://tson.io) into a Java HTTP server — reading `application/tson` request bodies,
writing `application/tson` responses, mapping schema violations onto HTTP status codes, and resolving
`!!schema` URLs over HTTP.

Four modules:

| Module | What it is |
|---|---|
| `tson-http` | Server-agnostic core: media type and `Accept` negotiation, codec, status policy, TSON error body, HTTP-backed schema source, schema catalog. No external dependencies. |
| `tson-http-jdk` | Adapter for the JDK's own `com.sun.net.httpserver`, plus schema serving. No external dependencies. |
| `tson-http-javalin` | Adapter for [Javalin](https://javalin.io) 6. |
| `tson-http-helidon` | Adapter for [Helidon](https://helidon.io) 4 SE, plus `TsonMediaSupport` so plain handlers read and write TSON natively. |

**Status.** All four modules are built and tested (114 tests). Each adapter is driven over real HTTP, and
each proves the same loop: a schema served at its own identity path, fetched back over HTTP under policy, and
used to validate a posted document.

```java
// JDK
server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
    Order order = exchange.readObject(Order.class);   // validated, or 400 with every diagnostic
    exchange.respond(201, store(order));
}));

// Javalin
app.post("/orders", TsonHandler.asHandler(codec, tson -> tson.respond(201, store(tson.readObject(Order.class)))));

// Helidon, with TsonMediaSupport registered -- no TSON-specific code in the handler
routing.post("/orders", (req, res) -> res.status(201).send(store(req.content().as(Order.class))));
```

## Building

Requires JDK 25 and a checkout of [ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java) at
`../ltr8-io-tson-java`. That library publishes to mavenLocal only, so a checkout is needed either way.

```
./gradlew build                                            # consumes the sibling as an included build
./gradlew build -Ptson.path=/elsewhere/ltr8-io-tson-java   # if it lives somewhere else

# or against its published artifacts, after `./gradlew publishToMavenLocal` in that checkout
./gradlew build -Ptson.published=true
```

## Licence

Apache 2.0, as tson-java.
