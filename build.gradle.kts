import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Platform property: paper (default, high-version API + Java 21) / spigot / legacy12 (MC 1.12.2 API surface + Java 8).
// platform is a configuration-time property; a single build process takes only one value; the Spigot and Legacy sides are handled by the :plugin nested build.
val platform: String = (findProperty("platform") ?: "paper") as String

plugins {
    java
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.taboolib)
    kotlin("plugin.serialization") version "2.0.0"
    // paperweight id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("maven-publish")
    signing
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "io.izzel.taboolib")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // paperweight apply(plugin = "io.papermc.paperweight.userdev")
    apply(plugin = "maven-publish")
    apply(plugin = "com.vanniktech.maven.publish")

    group = "io.github.zzzyyylllty.sertraline"
    version = rootProject.version


    configurations {
        all {
            exclude(group = "com.mojang.datafixers")
        }
    }

    taboolib {
        env {
            debug = false
            forceDownloadInDev = false
            // repoCentral = "https://maven.aliyun.com/repository/central" — removed, use default
            repoTabooLib = "https://repo.tabooproject.org/repository/releases"
            fileLibs = "libraries"
            fileAssets = "assets"
            enableIsolatedClassloader = false
            install(
                Basic, Bukkit, BukkitHook, BukkitNMSUtil, Database, Kether,
                CommandHelper, BukkitNMSItemTag, JavaScript, BukkitUI,
                BukkitUtil, Jexl, Metrics, DatabasePlayer, BukkitNMS,
                PtcObject
            )
            disableOnUnsupportedVersion = false
            disableOnSkippedVersion = false
        }
        version {
            taboolib = rootProject.libs.versions.taboolib.get()
            coroutines = "1.7.3"
            skipKotlin = false
            skipKotlinRelocate = false
            skipTabooLibRelocate = false

        }
    }

    repositories {
        mavenLocal()
        mavenCentral()


        maven("https://repo-momi.gtemc.cn/")
        // TabooLib
        maven {
            url = uri("https://repo.tabooproject.org/repository/releases/")
        }
        maven("https://repo1.maven.org/maven2")
        maven("https://jitpack.io")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")

        // Paper related
        maven("https://repo.papermc.io/repository/maven-public/")

        // Plugin dependency repositories
        maven("https://repo.auxilor.io/repository/maven-public/")
        maven("https://nexus.phoenixdevt.fr/repository/maven-public/")
        maven("https://mvn.lumine.io/repository/maven-public/") // MythicMobs
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://repo.codemc.io/repository/maven-public/")
        maven("https://repo.dmulloy2.net/repository/public/")

        // Other repositories
        maven("https://jitpack.io")
        maven("https://repo.gtemc.net/releases/")
        maven {
            url = uri("https://nexus.maplex.top/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
        maven("https://repo.skriptlang.org/releases")
        maven("https://repo.destroystokyo.com/repository/maven-public/")

        // FastStats metrics SDK (bStats-like usage tracking)
        maven("https://repo.faststats.dev/releases")
    }

    dependencies {
        // TabooLib dependencies
        // taboo("com.github.cryptomorin:XSeries:13.6.0+26.1")   .
        taboo("io.github.almighty-satan:XSeries:13.6.0+26.1")

        taboo("cn.gtemc:itembridge:1.0.31")

        taboo(platform(rootProject.libs.kotlincrypto.bom))
        taboo(rootProject.libs.kotlincrypto.sha2)
        taboo(rootProject.libs.kotlin.stdlib)

        // Platform API: ink.ptms.core's universal = full Spigot API (org.bukkit+org.spigotmc),
        // mapped = Mojang-mapped NMS classes. Fully equivalent to org.spigotmc:spigot-api.
        // Paper-only API (io.papermc.paper.*) is declared only in the paper source set of :project:spigot.
        // legacy12: MC 1.12.2 core v11200 (universal/mapped subtypes only exist since 1.17.1; v11200 only has the default type)
        if (platform == "legacy12") {
            compileOnly("ink.ptms.core:v11200:11200")
        } else {
            compileOnly("ink.ptms.core:v12104:12104:universal")
            compileOnly("ink.ptms.core:v12104:12104:mapped")
        }

        // Transitive dependencies of the original API jar (paper-api/spigot-api both depend on guava), now declared explicitly
        compileOnly("com.google.guava:guava:33.3.1-jre")

        // Compile-time dependencies
        compileOnly("ink.ptms.chemdah:api:1.1.17")
        compileOnly("ink.ptms:nms-all:1.0.0")
//        compileOnly("com.willfp:eco:6.77.2")
        compileOnly("com.github.SkriptLang:Skript:2.14.3")

        taboo("ink.ptms:um:1.2.1")
        // MythicLib no longer supports 1.12.2 (1.7.1 is Java 11 bytecode), dropped for legacy12
        if (platform != "legacy12") {
            compileOnly(rootProject.libs.mythiclibdist)
            compileOnly("io.lumine:Mythic-Dist:5.6.1")
        }
        compileOnly(rootProject.libs.placeholderapi)
        compileOnly(rootProject.libs.packeteventsspigot)
//        compileOnly(rootProject.libs.datafixerupper)
        compileOnly(rootProject.libs.fluxoncore)
        compileOnly(rootProject.libs.netty.all)
        // caffeine 3.x requires Java 11; legacy12 uses 2.9.3 (Java 8)
        compileOnly(if (platform == "legacy12") rootProject.libs.caffeineLegacy else rootProject.libs.caffeine)
        compileOnly(rootProject.libs.gson)
        compileOnly(rootProject.libs.bundles.graalvm)
        compileOnly(rootProject.libs.bundles.jackson)
        taboo("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

        // private dependency
        // not affect use
        compileOnly(files("$rootDir/libs/ChoTenTech-1.0.0-api.jar"))


        // EmbianComponent: paper/spigot use 1.2.2; legacy12 uses the Java 8 bytecode version (data-component runtime version guard, packaged but not invoked)
        taboo(files("$rootDir/libs-public/EmbianComponent-${if (platform == "legacy12") "1.2.2-java8" else "1.2.2"}.jar"))
//        taboo("com.github.zzzyyylllty:EmbianComponent:1.2.0")

        // sparrow-minimessage: Java 21 bytecode (major 65); not bundled for legacy12 (Java 8), compile-only, force-disabled at runtime by FastMiniMessage
        if (platform == "legacy12") {
            compileOnly(files("$rootDir/libs-public/sparrow-minimessage-0.5.jar"))
        } else {
            taboo(files("$rootDir/libs-public/sparrow-minimessage-0.5.jar"))
        }

        // FastStats (https://docs.faststats.dev/java) — bStats-like usage/error tracking.
        // The default artifact is Java 17 bytecode; for legacy12 (Java 8) FastStats publishes an
        // API-identical backport under the `dev.faststats.metrics.j8` group id (same package names,
        // major version 52, same NoSuchMethodError guards for Paper/Folia-only calls), so all three
        // platforms bundle a working SDK.
        // `config` is a runtime-scope transitive of `bukkit`, declared explicitly so the shading
        // step always has it (BukkitContext reads plugins/faststats/config.properties through it).
        if (platform == "legacy12") {
            taboo(rootProject.libs.faststats.j8.bukkit)
            taboo(rootProject.libs.faststats.j8.config)
        } else {
            taboo(rootProject.libs.faststats.bukkit)
            taboo(rootProject.libs.faststats.config)
        }


        // Runtime dependencies
        implementation(rootProject.libs.bundles.reflex)
        implementation(rootProject.libs.bundles.asm)
        implementation(rootProject.libs.bundles.adventure)
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xjvm-default=all",
                    "-Xskip-prerelease-check",
                    "-Xallow-unstable-dependencies"
                ) + if (platform == "legacy12") listOf("-Xjdk-release=8") else emptyList()
            )
            // legacy12 targets Java 8 (MC 1.12.2 servers can only run Java 8); other platforms target Java 21
            jvmTarget.set(if (platform == "legacy12") JvmTarget.JVM_1_8 else JvmTarget.JVM_21)
        }
    }

    tasks.withType<JavaCompile> {
        options.release.set(if (platform == "legacy12") 8 else 21)
        options.encoding = "UTF-8"
    }

    // Sources jar
    val sourcesJar by tasks.registering(Jar::class) {
        from(sourceSets.main.get().allJava)
        archiveClassifier.set("sources")
    }

    // Javadoc jar
    val javadocJar by tasks.registering(Jar::class) {
        from(tasks.javadoc)
        archiveClassifier.set("javadoc")
    }

}

// Special config for the common-files project
project(":project:common-files") {
    tasks.withType<ProcessResources>().configureEach {
        filesMatching("**/*.json") {
            expand(
                "nashornVersion" to rootProject.libs.versions.nashorn.get(),
                "graaljsVersion" to rootProject.libs.versions.graalvm.get(),
                "jexlVersion" to rootProject.libs.versions.jexl.get(),
                "gsonVersion" to rootProject.libs.versions.gson.get(),
                "kotlincryptoVersion" to rootProject.libs.versions.kotlinCrypto.get(),
                // legacy12 downloads caffeine 2.9.3 at runtime (3.x requires Java 11)
                "caffeineVersion" to (if (platform == "legacy12") rootProject.libs.versions.caffeineLegacy.get() else rootProject.libs.versions.caffeine.get()),
                "fluxonVersion" to rootProject.libs.versions.fluxon.get(),
                "datafixerupperVersion" to rootProject.libs.versions.datafixerupper.get(),
                "asmVersion" to rootProject.libs.versions.asm.get(),
//                "uniItemVersion" to rootProject.libs.versions.uniItem.get(),
                "adventureVersion" to rootProject.libs.versions.adventure.get(),
                "jacksonVersion" to rootProject.libs.versions.jackson.get(),
                "arimVersion" to rootProject.libs.versions.arim.get()
            )
        }
        // legacy12 (Java 8): downloading DFU 8 (Java 17+ bytecode) and GraalJS (Java 11+) at runtime would crash immediately,
        // so these JSON files are simply not packaged; the runtime won't download if no dependency descriptor is found
        if (platform == "legacy12") {
            exclude("**/dependencies/datafixerupper.json", "**/dependencies/graaljs.json")
        }
    }
}

//publishing {
//    publications {
//        create<MavenPublication>("mavenJava") {
//            from(components["java"])
//
//            // Configure POM file information; required by the Maven repository
//            pom {
//                name.set("Sertraline")
//                description.set("A concise description.")
//                url.set("https://github.com/zzzyyylllty/Sertraline-Hydrochloride")
//                licenses {
//                    license {
//                        name.set("MIT License")
//                        url.set("https://mit-license.org/")
//                    }
//                }
//                developers {
//                    developer {
//                        id.set("zzzyyylllty")
//                        name.set("AkaCandyKAngel")
//                        email.set("3631901756@qq.com")
//                    }
//                }
//                scm {
//                    url.set("https://github.com/zzzyyylllty/Sertraline-Hydrochloride")
//                }
//            }
//        }
//    }
//
//    repositories {
//        maven {
//            name = "OSSRH"
//            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
//            credentials {
//                username = System.getenv("CENTRAL_TOKEN_USERNAME")
//                password = System.getenv("CENTRAL_TOKEN_PASSWORD")
//            }
//        }
//    }
//
//}

//
//signing {
//    sign(publishing.publications["maven"])
//}

// By default `./gradlew build` produces three sets of jars: Paper, Spigot, and Legacy.
// platform is a configuration-time property that takes a single value per build process; the Spigot/Legacy sides are handled by the :plugin nested build;
// the nested build itself is platform=spigot/legacy12, so this dependency doesn't hold there — naturally non-recursive.
tasks.named("build") {
    if (platform == "paper") {
        dependsOn(":plugin:buildSpigot", ":plugin:buildLegacy")
    }
}

// Build only the current platform jar (paper → Standard); does not trigger Spigot/Legacy nested builds
tasks.register("buildStandard") {
    group = "build"
    description = "Build only the Standard (Paper) platform jar, skip Spigot/Legacy nested builds"
    dependsOn(":plugin:build")
}