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

    // Excel export. Real .xlsx rather than CSV so prices land in typed numeric cells:
    // COP and USD then sort and sum natively, and Excel can't reinterpret a title or
    // mangle an accented category on open. SXSSF streams rows, so memory stays flat.
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    // POI logs through log4j2-api. Without a binding it prints a StatusLogger error on
    // first use; this routes it into the logback config the rest of the app already uses.
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.24.3")

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
