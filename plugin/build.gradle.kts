import java.nio.file.FileSystems
import java.nio.file.Files

val platform: String = (findProperty("platform") ?: "paper") as String
val bundledTaboolibVersion = rootProject.libs.versions.taboolib

taboolib {
    description {
        name("Sertraline")
        if (platform != "legacy12") bukkitApi("1.21.4")
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

    relocate("ink.ptms.um", "io.github.zzzyyylllty.sertraline.dep.um")
    relocate("org.objectweb.asm", "org.objectweb.asm9")



    relocate("com.fasterxml.jackson", "io.github.zzzyyylllty.sertraline.dep.jackson")





    relocate("org.tabooproject.fluxon", "io.github.zzzyyylllty.sertraline.dep.fluxon")
    relocate("com.github.benmanes.caffeine", "io.github.zzzyyylllty.sertraline.dep.caffeine")
    relocate("org.kotlincrypto", "io.github.zzzyyylllty.sertraline.dep.kotlincrypto")
    // FastStats ships as a self-contained SDK; relocate it so it can never clash with another
    // plugin that bundles a different FastStats version in the same class loader.
    relocate("dev.faststats", "io.github.zzzyyylllty.sertraline.dep.faststats")

}


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
    val pluginArchive = named<Jar>("jar").flatMap { it.archiveFile }
    val taboolibMainTask = named("taboolibMainTask") {
        inputs.property("descriptorTaboolibVersion", bundledTaboolibVersion)
        inputs.property("descriptorPlatform", platform)
        doLast {
            FileSystems.newFileSystem(pluginArchive.get().asFile.toPath(), emptyMap<String, Any>()).use { archive ->
                val descriptor = archive.getPath("/plugin.yml")
                val timestamp = Files.getLastModifiedTime(descriptor)
                val content = Files.readAllLines(descriptor, Charsets.UTF_8)
                    .filterNot { platform == "legacy12" && it.startsWith("api-version:") }
                    .map { line ->
                        if (line.contains("Powered by TabooLib")) "# Powered by TabooLib " + bundledTaboolibVersion.get()
                        else line
                    }
                Files.write(descriptor, content, Charsets.UTF_8)
                Files.setLastModifiedTime(descriptor, timestamp)
            }
        }
    }

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
        dependsOn(named("build"), ":project:common:check", ":project:spigot:check", named("buildSpigot"), named("buildLegacy"))
    }
}

// Variant builds use their own output and project cache directories.
val wrapper = rootProject.file(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
val offline = gradle.startParameter.isOffline
val useMavenLocal = providers.gradleProperty("useMavenLocal").isPresent

fun registerPlatformBuild(name: String, target: String) = tasks.register<Exec>(name) {
    group = "build"
    description = "Build and test the $target variant"
    workingDir(rootProject.projectDir)
    val command = mutableListOf(
        wrapper.absolutePath, "-Pplatform=$target", "--project-cache-dir",
        rootProject.file(".gradle/$target").absolutePath, "build", "--console=plain",
    )
    if (offline) command.add("--offline")
    if (useMavenLocal) command.add("-PuseMavenLocal")
    commandLine(command)
    mustRunAfter(tasks.named("build"))
}

val buildSpigot = registerPlatformBuild("buildSpigot", "spigot")
val buildLegacy = registerPlatformBuild("buildLegacy", "legacy12")
buildLegacy.configure { mustRunAfter(buildSpigot) }
