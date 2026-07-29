plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "ru.petus"
version = "3.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.web", "javafx.swing")
}

dependencies {
    // Modern flat theming for JavaFX (Primer/Nord/Dracula + CSS variables).
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    // Icon pack — crisp SVG-based icons instead of emoji.
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ru.petus.launcher.Bootstrap"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

// --------------------------------------------------------------------------
// Fat jar with the JavaFX artifacts of every desktop platform, so a single
// PetusLauncher.jar runs on Windows, Linux and macOS with a plain JRE 21.
//
// The classified JavaFX modules all declare the same Gradle capability, so they
// cannot live in one configuration. One configuration per platform keeps Gradle
// happy; shadow then merges them and drops the duplicated class files, while
// the platform specific natives (.dll/.so/.dylib) all survive because their
// file names differ.
// --------------------------------------------------------------------------
val javafxVersion = "21.0.5"
// linux-aarch64 is not published for every JavaFX release, so it is left out:
// Raspberry-class hardware can run the launcher with its own JavaFX runtime.
val javafxPlatforms = listOf("win", "linux", "mac", "mac-aarch64")
val javafxModules = listOf("base", "graphics", "controls", "media", "web", "swing")

val javafxBundles: List<Configuration> = javafxPlatforms.map { platform ->
    val name = "javafxBundle" + platform.split("-").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
    val configuration = configurations.create(name) {
        isTransitive = false
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    javafxModules.forEach { module ->
        dependencies.add(name, "org.openjfx:javafx-$module:$javafxVersion:$platform")
    }
    configuration
}

tasks.shadowJar {
    archiveFileName = "PetusLauncher-$version.jar"
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(project.configurations.runtimeClasspath.get()) + javafxBundles
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    manifest {
        attributes(
            "Implementation-Title" to "PetusLauncher",
            "Implementation-Version" to version,
            "Main-Class" to "ru.petus.launcher.Bootstrap",
        )
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
