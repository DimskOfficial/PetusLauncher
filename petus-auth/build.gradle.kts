plugins {
    java
    id("com.gradleup.shadow") version "8.3.6" apply false
}

subprojects {
    apply(plugin = "java")

    group = "ru.petus"
    version = rootProject.property("pluginVersion") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://maven.elytrium.net/repo/")
        maven("https://repo.jonesdev.xyz/releases/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    tasks.withType<ProcessResources>().configureEach {
        filesMatching(listOf("velocity-plugin.json", "plugin.yml")) {
            expand("version" to project.version)
        }
    }
}
