allprojects {
    group = "io.ltr8"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all"))
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"

        // Same doclint policy as tson-java: everything except `missing`, which is noise on obvious
        // accessors and buries the errors that do matter. `reference`/`syntax`/`html`/`accessibility`
        // catch real breakage. addStringOption because Gradle has no first-class doclint setting --
        // it emits `-<key> <value>`, so the flag rides in the key and `-quiet` is filler.
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.0.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
