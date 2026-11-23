import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val platform: String = (findProperty("platform") ?: "paper") as String

taboolib { subproject = true }

// Platform adapter layer:
//   main   source set = shared common contract (PaperEventHooks, pure Bukkit types)
//   paper  source set = Paper implementation (PlatformCompat, calls Paper API directly)
//   spigot source set = Spigot implementation (PlatformCompat, pure Bukkit API, zero reflection)
// The two PlatformCompat classes share the same name and signature; exactly one is packed, never both in one jar.
// legacy12 builds with jvmTarget=8 while paper-api requires JVM 21, so its variant cannot resolve and the paper source set is not created at all.
val hasPaperSourceSet = platform == "paper"

sourceSets {
    if (hasPaperSourceSet) {
        create("paper") { kotlin.srcDir("src/paper/kotlin") }
    }
    create("spigot") { kotlin.srcDir("src/spigot/kotlin") }
}

dependencies {
    testImplementation(rootProject.libs.junit)
    testImplementation(sourceSets["spigot"].output)

    // main source set (PaperEventHooks) uses only Bukkit types
    if (platform == "legacy12") compileOnly("ink.ptms.core:v11200:11200")
    else compileOnly("ink.ptms.core:v12104:12104:universal")

    // paper source set: paper-api + main common classes
    if (hasPaperSourceSet) {
        "paperCompileOnly"(rootProject.libs.paperapi)
        "paperCompileOnly"(sourceSets["main"].output)
    }

    // spigot source set: Spigot-equivalent API (ink.ptms.core) + main common classes + adventure (this API has no adventure, must be declared explicitly)
    // legacy12: MC 1.12.2 core v11200 (no universal/mapped sub-types)
    if (platform == "legacy12") {
        "spigotCompileOnly"("ink.ptms.core:v11200:11200")
    } else {
        "spigotCompileOnly"("ink.ptms.core:v12104:12104:universal")
        "spigotCompileOnly"("ink.ptms.core:v12104:12104:mapped")
    }
    // action bar needs the Bungee chat API (transitive dependency of spigot-api, provided by the server at runtime)
    "spigotCompileOnly"("net.md-5:bungeecord-chat:1.20-R0.2")
    "spigotCompileOnly"(sourceSets["main"].output)
    "spigotCompileOnly"(rootProject.libs.bundles.adventure)
    // data component is handled by EmbianComponent (NMS reflection path; Spigot has no Paper API available);
    // legacy12 uses the Java 8 bytecode version (packaged only, calls are blocked by the version guard on the common side at runtime)
    "spigotCompileOnly"(files("$rootDir/libs-public/EmbianComponent-${if (platform == "legacy12") "1.2.2-java8" else "1.2.2"}.jar"))
}

configurations {
    testImplementation {
        extendsFrom(getByName("spigotCompileOnly"))
    }
    create("paperOutput")
    create("spigotOutput")
}

// Platform class directories: the same-named class PlatformCompat in the paper/spigot source sets is strictly one-or-the-other when synced into the directory.
// The artifact is a Provider<Directory>; consumers expand it via from() by directory (consistent with other submodules' main source sets),
// and resolution happens at execution time, avoiding the afterEvaluate conflict caused by configuration-time resolution.
val paperPlatformClasses by tasks.registering(Sync::class) {
    if (hasPaperSourceSet) {
        from(sourceSets["main"].output)
        from(sourceSets["paper"].output)
    }
    into(layout.buildDirectory.dir("platform/paper"))
}

val spigotPlatformClasses by tasks.registering(Sync::class) {
    from(sourceSets["main"].output)
    from(sourceSets["spigot"].output)
    into(layout.buildDirectory.dir("platform/spigot"))
}

artifacts {
    add("paperOutput", paperPlatformClasses.map { it.destinationDir }) {
        builtBy(paperPlatformClasses)
    }
    add("spigotOutput", spigotPlatformClasses.map { it.destinationDir }) {
        builtBy(spigotPlatformClasses)
    }
}
