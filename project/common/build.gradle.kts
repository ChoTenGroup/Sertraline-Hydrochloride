import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

taboolib { subproject = true }

val yamlTestRuntime by configurations.creating {
    isCanBeConsumed = false
}

tasks.withType<Test>().configureEach {
    // The 1.12 Bukkit API jar contains unrelocated SnakeYAML. Jackson needs 2.2 ahead of it.
    classpath = yamlTestRuntime + classpath
    minHeapSize = "32m"
    maxHeapSize = "128m"
    jvmArgs("-XX:+UseSerialGC")
    systemProperty("sertraline.root", rootProject.projectDir.absolutePath)
}

val platform: String = (findProperty("platform") ?: "paper") as String

dependencies {
    testImplementation(rootProject.libs.junit)
    // TabooLib supplies ASM 9.8 at runtime under org.objectweb.asm9.
    compileOnly(rootProject.libs.asm.core)
    yamlTestRuntime(rootProject.libs.snakeyaml)
    testRuntimeOnly("org.graalvm.js:js-language:${rootProject.libs.versions.graalvm.get()}")
    testRuntimeOnly("org.graalvm.truffle:truffle-runtime:${rootProject.libs.versions.graalvm.get()}")
    testRuntimeOnly("org.openjdk.nashorn:nashorn-core:${rootProject.libs.versions.nashorn.get()}")
    // Both legacy12 and spigot use the spigot source set (pure Bukkit API + fallback paths); only paper uses the paper source set
    if (platform != "paper") {
        compileOnly(project(":project:spigot", configuration = "spigotOutput"))
    } else {
        compileOnly(project(":project:spigot", configuration = "paperOutput"))
    }
}

configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

val versionSources = layout.buildDirectory.dir("generated/sources/sertralineVersion")
val pluginVersion = rootProject.libs.versions.sertraline
val taboolibVersion = rootProject.libs.versions.taboolib
val generateVersion by tasks.registering {
    inputs.property("pluginVersion", pluginVersion)
    inputs.property("taboolibVersion", taboolibVersion)
    outputs.dir(versionSources)
    doLast {
        val components = pluginVersion.get().split('.').map(String::toInt)
        require(components.size == 4) { "Sertraline release version must have four components" }
        val output = versionSources.get().file("BuildVersion.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("""
            package io.github.zzzyyylllty.sertraline.api

            internal object BuildVersion {
                const val MAJOR = ${components[0]}
                const val MINOR = ${components[1]}
                const val PATCH = ${components[2]}
                const val FIX = ${components[3]}
                const val TABOOLIB = "${taboolibVersion.get()}"
            }
        """.trimIndent() + "\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersion) }

val apiJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Package Sertraline classes for plugin development"
    archiveBaseName.set("Sertraline")
    archiveClassifier.set("api")
    from(sourceSets.main.get().output)
}

tasks.register("tabooBuildApi") {
    group = "build"
    description = "Build the Sertraline API JAR"
    dependsOn(apiJar)
}

// Classes absent from the legacy12 (v11200 = MC 1.12.2 API) compile surface -> exclude the corresponding files:
// SmithItemEvent/SmithingInventory (1.16+), TradeSelectEvent (1.14+), 1.12.2 itself has no smithing table;
// PlayerRiptideEvent / EntityPickupItemEvent (1.13+, separate files); MythicLib surface (io.lumine.*);
// Modern crafting system (RecipeChoice/Tag/RecipeManager are all 1.13+)
if (platform == "legacy12") {
    sourceSets["main"].kotlin.exclude(
        "**/listener/sertraline/itemSmithingRestriction.kt",
        "**/listener/sertraline/itemTradeRestriction.kt",
        "**/listener/action/itemActionRiptide.kt",
        "**/listener/action/itemActionPickup.kt",
        "**/listener/update/itemUpdatePickupListener.kt",
        "**/attribute/MythicLibAttributeProvider.kt",
        "**/util/dependencies/MMOUtil.kt",
        "**/recipe/ItemResolver.kt",
        "**/recipe/NMSRecipeFactory.kt",
        "**/util/SimpleRecipeHelper.kt",
        "**/config/loadRecipeFiles.kt",
        "**/listener/recipeBookListener.kt",
        "**/listener/sertraline/itemRecipe.kt"
    )
    sourceSets["test"].kotlin.exclude("**/recipe/ItemResolverChoiceTest.kt")
}
