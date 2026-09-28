import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "srtm"
description = "srtm-backend: rentas municipales on wasichai (core, workflow, documents, views, forms, pages, gis)"

val wasichaiVersion = "0.2.0"

dependencies {
    implementation(platform("wasichai:wasichai-bom:$wasichaiVersion"))
    implementation("wasichai:wasichai-spring-boot-starter")
    implementation("wasichai:wasichai-spring-boot-starter-workflow")
    implementation("wasichai:wasichai-spring-boot-starter-documents")
    implementation("wasichai:wasichai-spring-boot-starter-views")
    implementation("wasichai:wasichai-spring-boot-starter-forms")
    implementation("wasichai:wasichai-spring-boot-starter-pages")
    // lotes of the catastro fiscal and of the predios, the domicilio's point. needs PostGIS (compose.yml)
    implementation("wasichai:wasichai-spring-boot-starter-gis")

    // srtm.emision: html from thymeleaf (standalone, no mvc: the app is webflux) to pdf with openhtmltopdf,
    // merged with pdfbox
    implementation(libs.thymeleaf)
    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.openhtmltopdf.slf4j)
    implementation(libs.pdfbox)

    // WasichaiIntegrationTest; brings spring-boot-starter-test, webflux-test and testcontainers
    testImplementation("wasichai:wasichai-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// ktlint reads .editorconfig; same tool version as wasichai
ktlint {
    version.set("1.7.1")
}

// unit tests always run; container-backed ones go through integrationTest
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // the smoke tests are all integration-tagged: an empty unit run is expected
    failOnNoDiscoveredTests.set(false)
}

// WASICHAI_TEST_DB_* (if set) point the suite at an external db instead of testcontainers
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs tests tagged 'integration' against a PostgreSQL container."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // gis needs PostGIS: testcontainers starts this image instead of plain postgres:18. an external test db
    // (WASICHAI_TEST_DB_*) must have the postgis extension available
    systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6")
    // the app logs to stdout; stderr carries only what a test reports, like PuApiTest's timing of 100 PUs
    testLogging {
        events("standard_error")
    }
    shouldRunAfter(tasks.named("test"))
}

// one runnable jar with a fixed name
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

// an app, not a library: no plain jar next to the boot jar
tasks.named<Jar>("jar") {
    enabled = false
}
