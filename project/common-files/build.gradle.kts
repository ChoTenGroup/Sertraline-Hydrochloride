taboolib { subproject = true }

val platform = providers.gradleProperty("platform").orElse("paper")
val versions = rootProject.libs.versions

tasks.withType<ProcessResources>().configureEach {
    filesMatching("META-INF/dependencies/*.json") {
        expand(
            "nashornVersion" to versions.nashorn.get(),
            "graaljsVersion" to versions.graalvm.get(),
            "jexlVersion" to versions.jexl.get(),
            "gsonVersion" to versions.gson.get(),
            "kotlincryptoVersion" to versions.kotlinCrypto.get(),
            "caffeineVersion" to (if (platform.get() == "legacy12") versions.caffeineLegacy.get() else versions.caffeine.get()),
            "fluxonVersion" to versions.fluxon.get(),
            "asmVersion" to versions.asm.get(),
            "adventureVersion" to versions.adventure.get(),
            "jacksonVersion" to versions.jackson.get(),
        )
    }
    if (platform.get() == "legacy12") exclude("META-INF/dependencies/graaljs.json")
}
