plugins {
    id("java-library")
}

dependencies {
    api(project(":tson-http"))
    api(platform("io.helidon:helidon-dependencies:4.2.2"))
    api("io.helidon.webserver:helidon-webserver")

    // Helidon's own content negotiation goes through the MediaSupport SPI; the adapter registers a
    // TSON MediaSupport rather than hand-coding read/write on each route.
    api("io.helidon.http.media:helidon-http-media")
}
