plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation("com.google.code.gson:gson:2.11.0")

    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks.shadowJar {
    archiveFileName = "petus-auth-paper-${project.version}.jar"
    relocate("com.google.gson", "ru.petus.auth.libs.gson")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
