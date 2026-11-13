import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

taboolib { subproject = true }

tasks.withType<Test>().configureEach {
    minHeapSize = "32m"
    maxHeapSize = "128m"
    jvmArgs("-XX:+UseSerialGC")
}

val platform: String = (findProperty("platform") ?: "paper") as String

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(configurations.compileClasspath))
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

configurations {
    // The paper-api injected by the root allprojects is only used for Paper builds
    if (platform == "spigot") {
        getByName("compileOnly") {
            exclude(group = "io.papermc.paper", module = "paper-api")
        }
    }
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
}
