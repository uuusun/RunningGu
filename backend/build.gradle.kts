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
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val courseBundleFile = rootProject.projectDir.parentFile.resolve("data/courses.json")

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
