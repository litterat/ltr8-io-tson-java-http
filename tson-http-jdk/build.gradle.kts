plugins {
    id("java-library")
}

// The zero-external-dependency adapter: com.sun.net.httpserver ships in the JDK (module
// jdk.httpserver), so this module keeps tson-java's "no external runtime dependencies" rule and
// doubles as the reference the two third-party adapters are read against.
dependencies {
    api(project(":tson-http"))
}

// The demo server lives in its own source set, so it is compiled by `build` -- and so cannot rot against an
// API change -- without shipping in the published jar. The test source set can see it, which is how the
// smoke test drives the real demo rather than a copy of it.
val demo = sourceSets.create("demo") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
    // The three demos are one server. Their schemas are shared rather than copied per adapter, so a change
    // to the order schema cannot land in one and not the others -- and they are real .tn files rather than
    // Java text blocks, which is how every other schema in this repo lives.
    resources.srcDir(rootProject.file("demo/schemas"))
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
    mainClass.set("io.ltr8.tson.http.jdk.demo.OrderServer")
    args = listOf(project.findProperty("port")?.toString() ?: "8080")
}

// ReadmeTest executes the README's own examples, so the README is an input to this task. Without this,
// Gradle sees no change when it is edited, keeps the task UP-TO-DATE, and a stale claim ships with a green
// build -- which is the exact failure the test exists to prevent.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("README.md"))
        .withPropertyName("readme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// For the allocation harness: prints the demo runtime classpath so it can be run under JFR by hand.
tasks.register("printDemoClasspath") {
    val cp = demo.runtimeClasspath
    doLast { println(cp.asPath) }
}
