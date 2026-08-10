plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

group = "com.runninggu.tools"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "codex-orchestrate"
    mainClass = "com.runninggu.orchestrator.MainKt"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
