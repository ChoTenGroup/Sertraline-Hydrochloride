# Sertraline API: load definitions from a file

## Change

Added `Sertraline.api().loadItemsFromFile(file: File): Boolean` in `SertralineAPI.kt`.

- Accepts supported definition extensions: `yml`, `yaml`, `json`, `toml`, `tml`.
- Uses Sertraline's `loadItemFile` parser, so the same `ItemLoadEvent` processors are invoked.
- Loads directly into the live item registry; intended to be called after Sertraline's initial reload/config load and after item processors are registered.
- Reloading an ID replaces its item definition, rather than duplicating the entry.
- API version was bumped to `3.13.0.1` in `SertralineAPI.kt` to communicate the new API surface.

Example:

```kotlin
val loaded = Sertraline.api().loadItemsFromFile(File(dataFolder, "gun-examples/guns/ar_556.yml"))
```

## ChoTenTech integration

- Example files now live in `Bukkit/src/main/resources/gun-examples/` and are copied into `plugins/ChoTenTech/gun-examples/` on first use.
- `GunExampleItems.load()` calls the new API for each file.
- ChoTenTech loads the examples before `GunRegistry.reload()` during `/ctt reload`.
- A `SertralineReloadEvent` listener re-adds the ChoTenTech-owned definitions after Sertraline successfully replaces its registry.

## Build status

- `:project:common:jar`: BUILD SUCCESSFUL after adding the API.
- ChoTenTech `build -x test`: BUILD SUCCESSFUL after adding a compile-only platform compatibility class jar (`libs/sertraline-platform-compat.jar`) alongside the updated `common-3.13.0.0.jar`.
- YAML parsing validation: all five bundled example files parse successfully.
- Full Sertraline `build` could not finish because its nested `buildLegacy` Gradle daemon crashed with Oracle GraalVM JVMCI native compiler / memory failure while starting. This was not a Kotlin compilation error in the new API. The existing Premium Standard jar has not yet been rebuilt with this API.
- No server was started and no deployment was performed.
