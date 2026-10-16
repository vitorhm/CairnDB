plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "org.vmessias"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "org.vmessias.cairndb.cli.MainKt"
    applicationName = "cairndb"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.run.configure {
    standardInput = System.`in`
}