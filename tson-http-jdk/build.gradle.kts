plugins {
    id("java-library")
}

// The zero-external-dependency adapter: com.sun.net.httpserver ships in the JDK (module
// jdk.httpserver), so this module keeps tson-java's "no external runtime dependencies" rule and
// doubles as the reference the two third-party adapters are read against.
dependencies {
    api(project(":tson-http"))
}
