plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.five9th"
version = "0.0.5-SNAPSHOT"
description = "smagent"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-h2console")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property", "-java-parameters")
    }
}

// The `version` above is the single source of truth:
// - buildInfo() generates META-INF/build-info.properties from it at build time,
//   which /actuator/info serves as build.version (no auth, see SecurityConfig);
// - syncDocsVersion below propagates it into README.md and docs/API.md.
springBoot {
    buildInfo()
}

// Rewrites the <!-- gradle:version -->…<!-- /gradle:version --> span in each doc
// to project.version. Wired into processResources so any build/test/run keeps the
// docs in sync; fails loudly if a marker is removed.
val syncDocsVersion = tasks.register("syncDocsVersion") {
    description = "Rewrites the <!-- gradle:version -->…<!-- /gradle:version --> span in README.md and docs/API.md"
    val version = project.version.toString()
    val docFiles = listOf("README.md", "docs/API.md").map { layout.projectDirectory.file(it).asFile }
    inputs.property("version", version)
    outputs.files(docFiles)
    doLast {
        val marker = Regex("<!--\\s*gradle:version\\s*-->.*?<!--\\s*/gradle:version\\s*-->")
        val replacement = "<!-- gradle:version -->$version<!-- /gradle:version -->"
        for (file in docFiles) {
            val text = file.readText()
            check(marker.containsMatchIn(text)) {
                "${file.path} is missing the <!-- gradle:version -->…<!-- /gradle:version --> marker"
            }
            val updated = text.replaceFirst(marker, replacement)
            if (updated != text) file.writeText(updated)
        }
    }
}

tasks.processResources {
    dependsOn(syncDocsVersion)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
