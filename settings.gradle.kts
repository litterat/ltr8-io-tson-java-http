rootProject.name = "tson-java-http"

// How the tson-java library is consumed. Two ways, because they are good at different things.
//
// **Included build (the default).** Gradle substitutes `io.ltr8:tson` with the sibling checkout's own
// project, so an edit or a `git pull` there is picked up by a plain `./gradlew build` with no publish step.
// That matters during co-development, which is what this project is: the sibling moves, and a stale ~/.m2
// would silently give this build the old behaviour with nothing to warn anyone.
//
// **Published artifacts (`-Ptson.published=true`).** Consumes `io.ltr8:tson:<version>` from mavenLocal,
// after `./gradlew publishToMavenLocal` in the sibling. Slower to pick up a change and easy to leave stale
// -- but it is the only path that exercises the published POM, the module metadata and the real jars, which
// is exactly what a consuming project should prove works. CI runs it.
//
// Both need the sibling on disk: tson-java publishes to mavenLocal only, deliberately (packaging, not
// release), so there is no remote to fetch from. `tson.path` is overridable for a checkout elsewhere.
val consumePublished = providers.gradleProperty("tson.published").map(String::toBoolean).getOrElse(false)

if (!consumePublished) {
    val tsonPath = providers.gradleProperty("tson.path").getOrElse("../ltr8-io-tson-java")
    includeBuild(tsonPath) {
        name = "tson-java"
    }
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
