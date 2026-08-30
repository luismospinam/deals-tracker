plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.luis.dealstracker"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    // Web server + server-rendered HTML
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // HTTP client for the source adapters
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Slickdeals RSS: the content:encoded payload is an HTML fragment
    implementation("org.jsoup:jsoup:1.18.1")

    // YAML config
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    // Postgres. Queries are raw SQL rather than an ORM: the ingest upsert
    // (ON CONFLICT DO UPDATE) and the price-history lateral joins are both
    // clearer and easier to verify written out directly.
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.0.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // Headless browser for Amazon -> Colombia shipping verification. The Java binding
    // bundles its own Node runtime, so the system Node (v16, EOL) is never used.
    implementation("com.microsoft.playwright:playwright:1.62.0")

    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("com.luis.dealstracker.AppKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// One-off helper: installs only the Chromium build Playwright needs, instead of the
// ~1.1GB every-browser default that Playwright.create() triggers.
tasks.register<JavaExec>("installChromium") {
    group = "browser"
    description = "Download only the Chromium build required by Playwright"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium", "--with-deps")
}

// Phase 1 explorer for the Amazon -> Colombia shipping check. Separate from the app's
// main class so it can be run without starting the web server or the poller.
tasks.register<JavaExec>("probe") {
    group = "verify"
    description = "Explore how amazon.com exposes shipping eligibility (see AmazonProbe)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.luis.dealstracker.verify.AmazonProbeKt")
    args = listOfNotNull(
        project.findProperty("asin")?.toString() ?: "B09C5RG6KV",
        project.findProperty("headed")?.let { "--headed" },
        project.findProperty("popover")?.let { "--popover" },
    )
}

// Phase 1, step 3: read eligibility with a session that already has Colombia set.
tasks.register<JavaExec>("verifyProbe") {
    group = "verify"
    description = "Read Amazon shipping eligibility using the saved Colombia session"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.luis.dealstracker.verify.AmazonProbe2Kt")
    args = (project.findProperty("asins")?.toString() ?: "B09C5RG6KV").split(",")
}

// Assisted (human-in-the-loop) Amazon eligibility check. Headed by design: the operator
// clears Amazon's human checks and sets the delivery country; the reading is automated
// around that. Needs no stdin -- it polls the session cookie for the country.
tasks.register<JavaExec>("assisted") {
    group = "verify"
    description = "Headed Amazon → Colombia check; you drive the browser when asked"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.luis.dealstracker.verify.AmazonAssistedKt")
    args = (project.findProperty("asins")?.toString() ?: "").split(",").filter { it.isNotBlank() }
}

// Deep re-check of one ASIN, to resolve a disagreement between the automated read and
// what a human sees. Reuses the persistent profile, so no setup step.
tasks.register<JavaExec>("recheck") {
    group = "verify"
    description = "Re-check one ASIN including its full offer listing"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.luis.dealstracker.verify.AmazonRecheckKt")
    args = listOf(project.findProperty("asin")?.toString() ?: "B09C5RG6KV")
}

tasks.register<JavaExec>("importProbe") {
    group = "verify"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.luis.dealstracker.verify.ImportProbeKt")
}
