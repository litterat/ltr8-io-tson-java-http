rootProject.name = "tson-java-http"

// The tson-java library is consumed as an *included build*, not a published artifact -- that repo has
// no `maven-publish` today (see UPSTREAM.md #1), so a composite build is the only way to depend on it
// without vendoring jars. Gradle substitutes `io.ltr8:tson` and friends with the included build's own
// projects, so a change there is picked up by a plain `./gradlew build` here with no publish step.
//
// The path is overridable (`-Ptson.path=...`, or `tson.path` in gradle.properties) so CI, which checks
// the two repos out side by side under different names, can point at wherever it landed.
val tsonPath = providers.gradleProperty("tson.path").getOrElse("../ltr8-io-tson-java")
includeBuild(tsonPath) {
    name = "tson-java"
}

gradle.projectsEvaluated {
    allprojects {
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-Xlint:-module")
        }
    }
}

include("tson-http")
include("tson-http-jdk")
include("tson-http-javalin")
include("tson-http-helidon")
