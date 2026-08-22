# tson-java-http

Integrating [TSON](https://tson.io) into a Java HTTP server — reading `application/tson` request bodies,
writing `application/tson` responses, mapping schema violations onto HTTP status codes, and resolving
`!!schema` URLs over HTTP.

Four modules:

| Module | What it is |
|---|---|
| `tson-http` | Server-agnostic core: media type, codec, error mapping, HTTP-backed schema source. No external dependencies. |
| `tson-http-jdk` | Adapter for the JDK's own `com.sun.net.httpserver`. No external dependencies. |
| `tson-http-javalin` | Adapter for [Javalin](https://javalin.io) 6. |
| `tson-http-helidon` | Adapter for [Helidon](https://helidon.io) 4 SE, via its `MediaSupport` SPI. |

**Status: scaffold.** The build and module layout are in place; no Java source yet.

## Building

Requires JDK 25 and a checkout of [ltr8-io-tson-java](https://github.com/litterat/ltr8-io-tson-java) at
`../ltr8-io-tson-java` — that library has no published artifact yet, so it is consumed as a Gradle
included build.

```
./gradlew build
./gradlew build -Ptson.path=/elsewhere/ltr8-io-tson-java   # if it lives somewhere else
```

## Licence

Apache 2.0, as tson-java.
