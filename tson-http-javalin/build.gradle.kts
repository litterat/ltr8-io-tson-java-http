plugins {
    id("java-library")
}

dependencies {
    api(project(":tson-http"))
    api("io.javalin:javalin:6.7.0")

    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}
