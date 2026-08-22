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

// The demo server lives in its own source set, so it is compiled by `build` -- and so cannot rot against an
// API change -- without shipping in the published jar. The test source set can see it, which is how the
// smoke test drives the real demo rather than a copy of it.
val demo = sourceSets.create("demo") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations["demoImplementation"].extendsFrom(configurations["api"], configurations["implementation"])
configurations["demoRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

sourceSets["test"].compileClasspath += demo.output
sourceSets["test"].runtimeClasspath += demo.output + demo.runtimeClasspath

tasks.named("build") { dependsOn("demoClasses") }

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs this adapter's demo order server on port 8080 (override with -Pport=...)."
    classpath = demo.runtimeClasspath
    mainClass.set("io.ltr8.tson.http.helidon.demo.OrderServer")
    args = listOf(project.findProperty("port")?.toString() ?: "8080")
}
