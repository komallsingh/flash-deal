
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

val dotenvValues = rootProject.file(".env").takeIf { it.isFile }
    ?.readLines()
    ?.mapIndexedNotNull { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) {
            return@mapIndexedNotNull null
        }

        val entry = line.removePrefix("export ").split("=", limit = 2)
        require(entry.size == 2 && entry[0].matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Invalid .env entry at line ${index + 1}"
        }

        val rawValue = entry[1].trim()
        val quoted = rawValue.startsWith('"') || rawValue.startsWith('\'')
        require(!quoted || (rawValue.length >= 2 && rawValue.last() == rawValue.first())) {
            "Unterminated quoted value in .env at line ${index + 1}"
        }
        val value = if (quoted) rawValue.substring(1, rawValue.length - 1) else rawValue

        entry[0] to value
    }
    ?.toMap()
    .orEmpty()
    .filterKeys { it in setOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "JWT_SECRET", "WEBHOOK_SECRET", "CLAIM_TTL_SECONDS") }

group = "com.nnomm"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
    applicationName = "flash-deal"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.statusPages)
    implementation(libs.logback.classic)
    // Database
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.java.jwt)
// Database migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

tasks.named<JavaExec>("run") {
    environment(dotenvValues)
    systemProperty("user.timezone", "Asia/Kolkata")
}

tasks.withType<Test>().configureEach {
    environment(dotenvValues)
    systemProperty("user.timezone", "Asia/Kolkata")
}
