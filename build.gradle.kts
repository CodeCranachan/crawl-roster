import org.flywaydb.gradle.task.FlywayMigrateTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("com.h2database:h2:2.1.214")
    }
}

plugins {
    val kotlin = "2.1.10"

    kotlin("multiplatform") version kotlin
    kotlin("plugin.serialization") version kotlin
    id("org.flywaydb.flyway") version "11.3.4"
    id("org.jooq.jooq-codegen-gradle") version "3.20.1"
    application
}

group = "org.codecranachan"
version = "2025.4.1"

object Versions {
    const val kotlin = "2.1.10"
    const val kotlinWrappers = "2025.3.10"
    const val kotlinCoroutines = "1.10.1"
    const val kotlinDateTime = "0.6.2"
    const val kotlinRedux = "0.6.0"
    const val kotlinxSerJson = "1.8.0"
    const val kotlinxHtml = "0.12.0"
    const val ktor = "3.1.1"
    const val jooq = "3.20.1"
    const val h2db = "2.1.214"
    const val flyway = "11.3.4"
    const val logback = "1.4.5"
    const val uuid = "0.8.2"
    const val jose4j = "0.9.6"
    const val discord4j = "3.3.0-M2"
    const val reactor = "3.7.3"
    const val chunk = "3.6.2"
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

kotlin {
    jvm {
        withJava()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            languageVersion.set(KotlinVersion.KOTLIN_2_1)

        }
    }
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
                devServer?.open = false
                devServer?.port = 9090
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinxSerJson}")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:${Versions.kotlinDateTime}")
                implementation("com.benasher44:uuid:${Versions.uuid}")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            kotlin {
                srcDir("src/jvmMain")
                srcDir("generated/jooq")
            }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:${Versions.kotlinxHtml}")

                implementation("io.projectreactor:reactor-core:${Versions.reactor}")
                implementation("com.discord4j:discord4j-core:${Versions.discord4j}")
                implementation("io.ktor:ktor-serialization-kotlinx-json:${Versions.ktor}")
                implementation("io.ktor:ktor-serialization-kotlinx-cbor:${Versions.ktor}")
                implementation("io.ktor:ktor-server-netty:${Versions.ktor}")
                implementation("io.ktor:ktor-server-content-negotiation:${Versions.ktor}")
                implementation("io.ktor:ktor-server-html-builder:${Versions.ktor}")
                implementation("io.ktor:ktor-server-sessions:${Versions.ktor}")
                implementation("io.ktor:ktor-server-auth:${Versions.ktor}")
                implementation("io.ktor:ktor-server-forwarded-header:${Versions.ktor}")
                implementation("org.bitbucket.b_c:jose4j:${Versions.jose4j}")
                implementation("io.ktor:ktor-client-core:${Versions.ktor}")
                implementation("io.ktor:ktor-client-cio:${Versions.ktor}")
                implementation("io.ktor:ktor-client-content-negotiation:${Versions.ktor}")
                implementation("org.jooq:jooq:${Versions.jooq}")
                implementation("com.h2database:h2:${Versions.h2db}")
                implementation("org.flywaydb:flyway-core:${Versions.flyway}")
                implementation("ch.qos.logback:logback-classic:${Versions.logback}")
                implementation("com.x5dev:chunk-templates:${Versions.chunk}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("com.willowtreeapps.assertk:assertk:0.25")
                implementation("io.projectreactor:reactor-test:${Versions.reactor}")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-serialization-kotlinx-json:${Versions.ktor}")
                implementation("io.ktor:ktor-client-core-js:${Versions.ktor}")
                implementation("io.ktor:ktor-client-content-negotiation:${Versions.ktor}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:${Versions.kotlinCoroutines}")
                implementation("org.reduxkotlin:redux-kotlin-js:${Versions.kotlinRedux}")
                implementation("org.reduxkotlin:redux-kotlin-thunk-js:${Versions.kotlinRedux}")

                implementation(project.dependencies.enforcedPlatform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:${Versions.kotlinWrappers}"))
                implementation("org.jetbrains.kotlin-wrappers:kotlin-browser")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-core")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-emotion")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-mui-material")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-mui-icons-material")
            }
        }
        val jsTest by getting
    }
}

application {
    mainClass.set("${project.group}.roster.ServerKt")
}

val flywayGeneratedDir = "${project.layout.projectDirectory}/generated/flyway"
val flywayJdbc = "jdbc:h2:file:${flywayGeneratedDir}/database"

tasks.flywayMigrate {
    doFirst {
        file(flywayGeneratedDir).mkdirs()
        delete(outputs.files)
    }
    url = flywayJdbc
    user = "sa"
    password = ""
    schemas = arrayOf("ROSTER")
    createSchemas = true

    val migrationsDir = project.kotlin.sourceSets["jvmMain"].resources.srcDirs
    locations = migrationsDir.map { "filesystem:${it.canonicalPath}" }.toTypedArray()
    migrationsDir.forEach(inputs::dir)
    outputs.dir(flywayGeneratedDir)
}

val jooqGeneratedDir = "${project.layout.projectDirectory}/generated/jooq"

jooq {
    configuration {
        jdbc {
            url = flywayJdbc
            user = "sa"
            password = ""
            driver = "org.h2.Driver"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            target {
                packageName = "${project.group}.roster.jooq"
                directory = jooqGeneratedDir
            }

            database {
                name = "org.jooq.meta.h2.H2Database"
                inputSchema = "ROSTER"
            }
        }
    }
}

tasks.jooqCodegen {
    val fw = tasks.named<FlywayMigrateTask>("flywayMigrate")
    inputs.files(fw.get().outputs.files)
    dependsOn(fw)
    doFirst { delete(outputs.files) }
}

tasks.named("compileKotlinJvm") {
    dependsOn(tasks.named("jooqCodegen"))
}

// include JS artifacts on production builds JAR we generate
tasks.getByName<Jar>("jvmJar") {
    val webpackTask =
        tasks.getByName<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>("jsBrowserProductionWebpack")
    // make sure JS gets compiled first
    dependsOn(webpackTask)
    // bring output file along into the JAR
    from(webpackTask.outputDirectory)
}