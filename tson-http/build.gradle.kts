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
