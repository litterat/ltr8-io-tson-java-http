plugins {
    id("java-library")
}

dependencies {
    api(project(":tson-http"))
    api("io.javalin:javalin:6.7.0")

    testImplementation("org.slf4j:slf4j-simple:2.0.17")
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
    mainClass.set("io.ltr8.tson.http.javalin.demo.OrderServer")
    args = listOf(project.findProperty("port")?.toString() ?: "8080")
}

dependencies {
    // Javalin logs through slf4j; without a binding the demo starts but says nothing about itself. Declared
    // after the demo source set exists, which is what creates this configuration.
    "demoRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}
