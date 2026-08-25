plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "ai.firmus.interop"
version = "1.0.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.apache.kafka:kafka-clients:3.9.0")

    // The synchronous driver, not the coroutine one, on purpose.
    //
    // A Kafka consumer group is a blocking, single-threaded ownership model: one thread owns
    // a set of partitions, and progress within a partition must stay ordered. Wrapping a
    // coroutine driver in runBlocking at every call site buys nothing but a suspend keyword,
    // and the moment someone "optimises" it into a launch{} the ordering guarantee that the
    // whole staleness design rests on is gone.
    implementation("org.mongodb:mongodb-driver-sync:5.2.1")

    // Kafka and the Mongo driver log through SLF4J. Without a binding they print a warning on
    // every start and then swallow everything, including the connection failures you most want
    // to see. Application logs do not go through this — see Logging.kt.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ai.firmus.interop.fhir.MainKt")
    // Containers get a cgroup memory limit, not a machine. Without this the JVM sizes the heap
    // from the host's RAM and the container is OOM-killed by the kernel rather than throwing
    // OutOfMemoryError, which loses the diagnostic entirely.
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// No shadow/shade plugin. Merging META-INF/services entries by hand is how a driver quietly
// loses a codec or a provider, and the failure surfaces at runtime as a ClassNotFoundException
// in production rather than at build time. `installDist` lays every dependency down as an
// intact jar with a generated start script, which is what the container actually needs.
tasks.named("build") {
    dependsOn("installDist")
}
