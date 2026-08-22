# tson-java-http

Integrating [TSON](https://tson.io) into a Java HTTP server — reading `application/tson` request bodies,
writing `application/tson` responses, mapping schema violations onto HTTP status codes, and resolving
`!!schema` URLs over HTTP.

Four modules:

| Module | What it is |
|---|---|
| `tson-http` | Server-agnostic core: media type and `Accept` negotiation, codec, status policy, TSON error body, HTTP-backed schema source. No external dependencies. |
| `tson-http-jdk` | Adapter for the JDK's own `com.sun.net.httpserver`, plus schema serving. No external dependencies. |
| `tson-http-javalin` | Adapter for [Javalin](https://javalin.io) 6. |
| `tson-http-helidon` | Adapter for [Helidon](https://helidon.io) 4 SE, via its `MediaSupport` SPI. |

**Status.** `tson-http` and `tson-http-jdk` are built and tested; the Javalin and Helidon adapters are not
started.

```java
server.createContext("/orders", TsonHandler.asHttpHandler(codec, exchange -> {
    exchange.requireMethod("POST");
    Order order = exchange.readObject(Order.class);   // validated, or 400 with every diagnostic
    exchange.respond(201, store(order));
}));
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
