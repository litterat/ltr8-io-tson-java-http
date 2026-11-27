plugins {
    id("java-library")
}

dependencies {
    // api, not implementation -- this module's public surface hands back tson types directly (Tson on
    // TsonServiceSchema.describedBy, TypeRef throughout Route and the bound records), so a consumer depending on just
    // this module still needs them on its own compile classpath.
    //
    // The version is only consulted when consuming published artifacts (-Ptson.published=true); the included
    // build substitutes each coordinate with the sibling's own project and ignores it.
    api("io.ltr8:tson:${property("tson.version")}")
    // The bind layer, named directly rather than reached through io.ltr8:tson: TsonBindings hands back a
    // DataBindContext. tson-java's own module, so the no-external-dependency rule holds.
    api("io.ltr8:ltr8-bind:${property("tson.version")}")
    // The binding annotations, named directly: the bound records carry @Typename and @Field, and Http and Resource
    // hold an AnnotatedMap in their components. ltr8-bind declares it `implementation`, so naming bind does not
    // bring it.
    api("io.ltr8:ltr8-annotation:${property("tson.version")}")
    // The atom families, named directly: the join asks AtomParsers for an atom's family and AtomType.boundTo whether
    // it produces a method's class. In no signature here, so implementation; tson-java's own module.
    implementation("io.ltr8:tson-atom:${property("tson.version")}")
}

// io.ltr8.tson.service.internal is not exported, so it is not documented: what protocol and http share is no API.
// Its sources stay on the source path, so the documented packages that import it still resolve.
tasks.javadoc {
    exclude("io/ltr8/tson/service/internal/**")
    (options as StandardJavadocDocletOptions).addStringOption("-source-path", file("src/main/java").path)
}
