plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module's public surface hands back tson types directly
    // (TsonValue, Diagnostic, TsonSchemaSource, the readers/writers), so a consumer depending on
    // just this module still needs them on its own compile classpath.
    //
    // The version is only consulted when consuming published artifacts (-Ptson.published=true); the
    // included build substitutes this coordinate with the sibling's own project and ignores it.
    api("io.ltr8:tson:${property("tson.version")}")
}

// Experiments -- design explorations that stay compiled and passing rather than rotting in a scratchpad. The
// files a reader wants (a schema, its examples, a README) live at the repo root under experiments/<name>/,
// and each experiment's probe tests join this module's test source set from there, on the demo/schemas
// pattern. An experiment is not a commitment: see experiments/README.md.
sourceSets["test"].java.srcDir(rootProject.file("experiments/meta-service/java"))
tasks.test {
    systemProperty("experiments.dir", rootProject.file("experiments").path)
}
