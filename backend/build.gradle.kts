import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.runninggu"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("com.querydsl:querydsl-jpa::jakarta")
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.microsoft.playwright:playwright:1.61.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("playwright") {
    group = "verification"
    description = "Playwright 브라우저 바이너리를 설치하거나 관리한다"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
}

val courseBundleFile = rootProject.projectDir.parentFile.resolve("data/courses.json")
val contestSnapshotFile = rootProject.projectDir.parentFile.resolve("data/contest_snapshot.json")

tasks.processResources {
    doFirst {
        require(courseBundleFile.isFile) {
            "서버 코스 번들이 없습니다: ${courseBundleFile.absolutePath}"
        }
    }
    from(courseBundleFile) {
        into("data")
    }
}

tasks.register<JavaExec>("contestImport") {
    group = "application"
    description = "서버용 대회 snapshot을 검증하고 PostgreSQL에 적재한다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.runninggu.server.contest.ContestSnapshotImporterApplication"
    workingDir = rootProject.projectDir.parentFile
    project.findProperty("snapshotPath")?.toString()?.let { args(it) }
}

val contestImportJar = tasks.register<BootJar>("contestImportJar") {
    group = "build"
    description = "Gradle 없이 실행할 수 있는 대회 snapshot Importer JAR을 만든다"
    archiveClassifier.set("contest-import")
    mainClass.set("com.runninggu.server.contest.ContestSnapshotImporterApplication")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    classpath(sourceSets["main"].runtimeClasspath)
}

tasks.register<Sync>("ec2Artifact") {
    group = "build"
    description = "EC2 배포용 서버·Importer JAR과 대회 snapshot을 한 디렉터리에 모은다"
    dependsOn(tasks.named("bootJar"), contestImportJar)

    into(layout.buildDirectory.dir("ec2-artifact"))
    from(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile }) {
        rename { "runninggu-server.jar" }
    }
    from(contestImportJar.flatMap { it.archiveFile }) {
        rename { "runninggu-contest-import.jar" }
    }
    from(contestSnapshotFile) {
        into("data")
    }

    doFirst {
        require(contestSnapshotFile.isFile) {
            "서버 대회 snapshot이 없습니다: ${contestSnapshotFile.absolutePath}"
        }
    }
}
