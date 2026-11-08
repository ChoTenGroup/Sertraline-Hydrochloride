taboolib {
    description {
        name("Sertraline")
        desc("An advanced item plugin. ChoTen item management plugin.")
        contributors {
            name("AkaCandyKAngel")
            name("jhqwqmc")
        }
        dependencies {
            name("ChoTenTech").optional(true)
            name("DylsemHokma").optional(true)
            name("MythicLib").optional(true)
            name("TrMenu").optional(true)
            name("Zaphkiel").optional(true)
            name("MMOItems").optional(true)
            name("SX-Item").optional(true)
            name("MythicMobs").optional(true)
            name("NeigeItems").optional(true)
            name("Chemdah").optional(true)
            name("CraftEngine").optional(true)
            name("ItemsAdder").optional(true)
            name("Oxaren").optional(true)
            name("MagicCosmetics").optional(true)
            name("packetevents").optional(true)
            name("PlaceholderAPI").optional(true)
        }
    }

    relocate("top.maplex.arim", "io.github.zzzyyylllty.sertraline.dep.arim")
    relocate("ink.ptms.um", "io.github.zzzyyylllty.sertraline.dep.um")
    relocate("com.alibaba", "io.github.zzzyyylllty.sertraline.dep.alibaba")
    relocate("kotlinx.serialization", "kotlinx.serialization181")
    relocate("io.github.projectunified.uniitem", "io.github.zzzyyylllty.sertraline.dep.uniitem")
    relocate("com.fasterxml.jackson", "io.github.zzzyyylllty.sertraline.dep.jackson")
//    relocate("com.mojang.datafixers", "io.github.zzzyyylllty.sertraline.dep.datafixers")
    relocate("io.netty.handler.codec.http", "io.github.zzzyyylllty.sertraline.dep.http")
    relocate("io.netty.handler.codec.rtsp", "io.github.zzzyyylllty.sertraline.dep.rtsp")
    relocate("io.netty.handler.codec.spdy", "io.github.zzzyyylllty.sertraline.dep.spdy")
    relocate("io.netty.handler.codec.http2", "io.github.zzzyyylllty.sertraline.dep.http2")
    relocate("org.tabooproject.fluxon", "io.github.zzzyyylllty.sertraline.dep.fluxon")
    relocate("com.github.benmanes.caffeine", "io.github.zzzyyylllty.sertraline.dep.caffeine")
    relocate("org.kotlincrypto", "io.github.zzzyyylllty.sertraline.dep.kotlincrypto")
    // FastStats ships as a self-contained SDK; relocate it so it can never clash with another
    // plugin that bundles a different FastStats version in the same class loader.
    relocate("dev.faststats", "io.github.zzzyyylllty.sertraline.dep.faststats")
//    relocate("org.objectweb.asm", "io.github.zzzyyylllty.sertraline.dep.asm")
}


val platform: String = (findProperty("platform") ?: "paper") as String
// Both legacy12 (MC 1.12.2) and spigot use the spigot source set of :project:spigot (pure Bukkit API + fallback paths)
val useSpigotOutput: Boolean = platform != "paper"

val platformOutput by configurations.creating

// Spigot only: bundle adventure (Paper provides it globally on the server; Spigot has no adventure —
// the runtime-downloaded copy would be relocated to dep.kyori, but plugin bytecode references the
// non-relocated net.kyori.*, so the original-namespace classes must be embedded directly into the Spigot jar)
val bundleAdventure by configurations.creating

dependencies {
    // Project dependency: ensures :project:spigot is configured before this project,
    // and takes exactly one of its jar artifacts based on platform (paper source set / spigot source set)
    "platformOutput"(project(":project:spigot", configuration = if (useSpigotOutput) "spigotOutput" else "paperOutput"))
    if (useSpigotOutput) {
        bundleAdventure(rootProject.libs.bundles.adventure)
    }
}

tasks {
    val taboolibMainTask = named("taboolibMainTask")

    // Platform classes directory Sync task (registered in :project:spigot): builds first, then expands into the main jar
    val platformClassesTask = if (useSpigotOutput) ":project:spigot:spigotPlatformClasses" else ":project:spigot:paperPlatformClasses"
    // Artifact suffix: paper → Standard / spigot → Spigot / legacy12 → Legacy
    val jarSuffix = when (platform) {
        "spigot" -> "Spigot"
        "legacy12" -> "Legacy"
        else -> "Standard"
    }

    jar {
        archiveFileName.set("${rootProject.name}-${rootProject.version}-Premium-$jarSuffix.jar")
        dependsOn(platformClassesTask)
        from(platformOutput)
        rootProject.subprojects.forEach { proj ->
            if (proj.name != "spigot") {
                from(proj.sourceSets["main"].output)
            }
        }
        if (useSpigotOutput) {
            from({ bundleAdventure.map { zipTree(it) } }) {
                exclude("META-INF/**", "module-info.class")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }
    }

    val freeJar by registering(Jar::class) {
        group = "build"
        description = "Generate FREE version jar by filtering premium classes"

        dependsOn(taboolibMainTask)
        dependsOn(jar)

        archiveFileName.set("${rootProject.name}-${version}-Free-$jarSuffix.jar")

        // Fix: use archiveFile instead of archivePath
        from(zipTree(jar.get().archiveFile)) {
            exclude("io/github/zzzyyylllty/sertraline/premium/**")
        }
    }

    named("build") {
        // paperweight dependsOn(reobfJar)
        dependsOn(freeJar)
    }

    // One command produces all three sets of jars: Paper, Spigot, and Legacy
    val buildAll by registering {
        group = "build"
        description = "Build all platform jars (Paper + Spigot + Legacy)"
        dependsOn(named("build"), named("buildSpigot"), named("buildLegacy"))
    }
}

// Convenience tasks: build the Spigot / Legacy platform jars with a single command (equivalent to ./gradlew build -Pplatform=xxx -x test).
// Cannot use GradleBuild: two GradleBuild tasks pointing at the same dir inside one Gradle session collide on the
// build path (:Sertraline) in the IncludedBuildRegistry, erroring with "Included build ... same as included build" (gradle/gradle#13522).
// Instead each spawns its own gradlew process via exec (--no-daemon ensures no queueing behind the busy current daemon); behavior is equivalent.
val gradlewScript: String = rootProject.file(
    if (System.getProperty("os.name").lowercase().contains("win")) "gradlew.bat" else "gradlew"
).absolutePath
// Inherit the JDK running the current Gradle daemon so the nested builds also run on JDK 21
val gradleJdkHome: String = System.getProperty("java.home")

fun registerNestedPlatformBuild(name: String, platform: String, description: String): TaskProvider<Exec> {
    return tasks.register(name, Exec::class) {
        group = "build"
        this.description = description
        workingDir = rootProject.projectDir
        commandLine(
            gradlewScript, "-p", rootProject.projectDir,
            "-Dorg.gradle.java.home=$gradleJdkHome",
            "-Pplatform=$platform", "build", "-x", "test",
            "--no-daemon", "--console=plain"
        )
    }
}

val buildSpigot = registerNestedPlatformBuild(
    "buildSpigot", "spigot", "Build Spigot platform jar (Sertraline-*-Spigot.jar)"
)
val buildLegacy = registerNestedPlatformBuild(
    "buildLegacy", "legacy12", "Build Legacy platform jar (Sertraline-*-Legacy.jar, MC 1.12.2 / Java 8)"
)