plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module's public surface hands back tson types directly
    // (TsonValue, Diagnostic, TsonSchemaSource, the readers/writers), so a consumer depending on
    // just this module still needs them on its own compile classpath.
    api("io.ltr8:tson")
}
