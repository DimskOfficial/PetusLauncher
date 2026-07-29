plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation("com.google.code.gson:gson:2.11.0")

    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    // Optional integrations — resolved reflectively at runtime, never required.
    compileOnly("net.elytrium.limboapi:api:1.1.26")
}

tasks.shadowJar {
    archiveFileName = "petus-auth-velocity-${project.version}.jar"
    relocate("com.google.gson", "ru.petus.auth.libs.gson")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
