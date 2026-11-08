package io.github.zzzyyylllty.sertraline.recipe

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.*
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import io.github.zzzyyylllty.sertraline.util.assembleCBClassCompat
import io.github.zzzyyylllty.sertraline.util.assembleMCClass
import io.github.zzzyyylllty.sertraline.util.getClazz
import io.github.zzzyyylllty.sertraline.util.getClazzCompat
import io.github.zzzyyylllty.sertraline.util.getDeclaredFieldCompat
import io.github.zzzyyylllty.sertraline.util.getDeclaredMethodCompat
import io.github.zzzyyylllty.sertraline.util.getMethod
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.Tag
import java.lang.reflect.Field

/**
 * NMS recipe factory.
 * Automatically detects the Minecraft version (1.21.4 / 26.1.2),
 * manipulates RecipeManager via reflection, supporting dual-version constructor signatures.
 */
object NMSRecipeFactory {

    private const val NAMESPACE = "sertraline"

    /**
     * Normalizes a recipe id into the path component of its `NamespacedKey` / resource location.
     *
     * Must be identical everywhere a recipe path is derived. `SimpleRecipeHelper.registerRecipe`
     * already lower-cased and sanitized the path, while [register] used the raw id and
     * `unregisterAll` used the normalized `NamespacedKey` — so an id containing uppercase letters,
     * spaces or any other character outside `[a-z0-9/._-]` was registered under one key and
     * unregistered under a different one, never actually being removed on reload.
     */
    fun normalizeRecipePath(raw: String): String =
        raw.removePrefix("$NAMESPACE:")
            .lowercase()
            .replace(Regex("[^a-z0-9/._-]"), "_")

    // ==================== Version detection ====================

    enum class MinecraftVersion { v1_21_4, v26_1_2 }

    /** Determines the version by checking whether the Identifier class exists */
    private val minecraftVersion: MinecraftVersion by lazy {
        if (runCatching { Class.forName("net.minecraft.resources.Identifier") }.isSuccess)
            MinecraftVersion.v26_1_2
        else
            MinecraftVersion.v1_21_4
    }

    // ==================== Reflection cache: ResourceLocation (1.21.4) / Identifier (26.1.2) ====================

    private val clazzResourceLocation: Class<*> by lazy {
        when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> getClazzCompat(
                "net.minecraft.resources.ResourceLocation",
                "net.minecraft.resources.MinecraftKey"
            )
            MinecraftVersion.v26_1_2 -> getClazz("net.minecraft.resources.Identifier")
        }
    }
    private val methodRLFromNamespaceAndPath by lazy {
        getDeclaredMethodCompat(
            clazzResourceLocation,
            listOf("fromNamespaceAndPath", "a"),
            null,
            String::class.java,
            String::class.java
        )
    }

    private fun createResourceLocation(namespace: String, path: String): Any {
        return methodRLFromNamespaceAndPath.invoke(null, namespace, path)
    }

    // ==================== Reflection cache: ResourceKey ====================

    private val clazzResourceKey by lazy { getClazz(assembleMCClass("resources.ResourceKey")) }
    private val methodResourceKeyCreate by lazy {
        getDeclaredMethodCompat(
            clazzResourceKey,
            listOf("create", "a"),
            null,
            clazzResourceKey,
            clazzResourceLocation
        )
    }

    // RECIPE registry key — 1.21.4: Registries.RECIPE; 26.1.2: same
    private val recipeRegistryKey: Any by lazy {
        getDeclaredFieldCompat(clazzRegistries, "RECIPE", "bk").get(null)
    }

    private fun createRecipeResourceKey(namespace: String, path: String): Any {
        val location = createResourceLocation(namespace, path)
        return methodResourceKeyCreate.invoke(null, recipeRegistryKey, location)
    }

    // ==================== Reflection cache: ItemStack (NMS) ====================

    private val clazzCraftItemStack by lazy {
        getClazzCompat(
            "org.bukkit.craftbukkit.inventory.CraftItemStack",
            assembleCBClassCompat("inventory.CraftItemStack")
        )
    }
    private val methodAsNMSCopy by lazy {
        clazzCraftItemStack.getDeclaredMethod("asNMSCopy", org.bukkit.inventory.ItemStack::class.java)
    }
    private val methodAsBukkitCopy by lazy {
        clazzCraftItemStack.getDeclaredMethod("asCraftMirror", getClazz(assembleMCClass("world.item.ItemStack")))
    }

    private fun toNMSStack(bukkit: org.bukkit.inventory.ItemStack): Any {
        return methodAsNMSCopy.invoke(null, bukkit)
    }

    // ==================== Reflection cache: Ingredient ====================

    private val clazzIngredient by lazy {
        getClazzCompat(
            assembleMCClass("world.item.crafting.Ingredient"),
            assembleMCClass("world.item.crafting.RecipeItemStack")
        )
    }
    private val clazzNMSItemStack by lazy { getClazz(assembleMCClass("world.item.ItemStack")) }
    private val clazzNMSItem by lazy { getClazz(assembleMCClass("world.item.Item")) }
    private val clazzItemLike by lazy {
        getClazzCompat(
            assembleMCClass("world.level.ItemLike"),
            assembleMCClass("world.level.IMaterial")
        )
    }
    private val methodGetItem by lazy {
        getDeclaredMethodCompat(clazzNMSItemStack, listOf("getItem", "h"))
    }

    // Ingredient.of(ItemLike...) — varargs (the same in both 1.21.4 and 26.1.2)
    private val methodIngredientOfStacks by lazy {
        val arrayClass = java.lang.reflect.Array.newInstance(clazzItemLike, 0).javaClass
        getDeclaredMethodCompat(clazzIngredient, listOf("of", "a"), null, arrayClass)
    }

    // RecipeItemStack.ofStacks(List<ItemStack>) — exact ingredient: preserves full components, shown in the recipe book by component;
    // crafting tests require completely identical components (mismatch risk on placeholder items, only used when exact is explicitly set)
    private val methodIngredientOfStacksExact by lazy {
        getDeclaredMethodCompat(clazzIngredient, listOf("ofStacks", "a"), null, List::class.java)
    }

    // Ingredient.of(TagKey<Item>) — neither 1.21.4 platform has this method,
    // so instead go through Registry.getTagOrEmpty(TagKey) → HolderSet.direct(List) → of(HolderSet)
    private val clazzTagKey by lazy { getClazz("net.minecraft.tags.TagKey") }
    private val clazzRegistries by lazy { getClazz("net.minecraft.core.registries.Registries") }
    private val builtInRegistriesClass by lazy { getClazz("net.minecraft.core.registries.BuiltInRegistries") }
    private val clazzHolderSet by lazy { getClazz("net.minecraft.core.HolderSet") }
    private val clazzModernRegistry by lazy {
        getClazzCompat("net.minecraft.core.IRegistry", "net.minecraft.core.Registry")
    }
    private val itemRegistry by lazy {
        getDeclaredFieldCompat(builtInRegistriesClass, "ITEM", "g").get(null)
    }
    private val methodGetTagOrEmpty by lazy {
        getDeclaredMethodCompat(clazzModernRegistry, listOf("getTagOrEmpty", "c"), null, clazzTagKey)
    }
    private val methodHolderSetDirect by lazy {
        getDeclaredMethodCompat(clazzHolderSet, listOf("direct", "a"), null, List::class.java)
    }
    private val methodIngredientOfHolderSet by lazy {
        getDeclaredMethodCompat(clazzIngredient, listOf("of", "a"), null, clazzHolderSet)
    }

    private fun toNMSItems(nmsStacks: List<Any>): List<Any> {
        return nmsStacks.map { methodGetItem.invoke(it) }
    }

    private fun createItemLikeArray(items: List<Any>): Any {
        val array = java.lang.reflect.Array.newInstance(clazzItemLike, items.size)
        items.forEachIndexed { i, item -> java.lang.reflect.Array.set(array, i, item) }
        return array
    }

    /**
     * Creates an NMS Ingredient.
     * - For a Tag, uses Ingredient.of(TagKey)
     * - For a concrete item, uses Ingredient.of(ItemLike...) (matches the Item type)
     */
    fun createIngredient(ingredient: RecipeIngredient): Any {
        return when (ingredient) {
            is RecipeIngredient.Item -> {
                val stacks = resolveItemStacks(ingredient.itemId, ingredient.amount)
                if (stacks.isEmpty()) throw IllegalArgumentException("No items resolved for: ${ingredient.itemId}")
                val nmsStacks = stacks.map { toNMSStack(it) }
                if (ingredient.exact) {
                    // Exact ingredient: full components (including lore, etc.) will show in the recipe book and crafting interface
                    return methodIngredientOfStacksExact.invoke(null, nmsStacks)
                }
                val items = toNMSItems(nmsStacks)
                methodIngredientOfStacks.invoke(null, createItemLikeArray(items))
            }
            is RecipeIngredient.Tag -> {
                val tagKey = resolveTagKey(ingredient.tagId)
                @Suppress("UNCHECKED_CAST")
                val holders = methodGetTagOrEmpty.invoke(itemRegistry, tagKey) as Iterable<Any>
                val holderList = ArrayList<Any>()
                for (holder in holders) holderList.add(holder)
                if (holderList.isEmpty()) throw IllegalArgumentException("Tag has no items: ${ingredient.tagId}")
                val holderSet = methodHolderSetDirect.invoke(null, holderList)
                methodIngredientOfHolderSet.invoke(null, holderSet)
            }
            is RecipeIngredient.Choice -> {
                val stacks = ingredient.options.flatMap { resolveIngredientStacks(it) }
                if (stacks.isEmpty()) throw IllegalArgumentException("Empty choice ingredient")
                val nmsStacks = stacks.map { toNMSStack(it) }
                val items = toNMSItems(nmsStacks)
                methodIngredientOfStacks.invoke(null, createItemLikeArray(items))
            }
        }
    }

    private fun resolveIngredientStacks(ingredient: RecipeIngredient): List<ItemStack> {
        return when (ingredient) {
            is RecipeIngredient.Item -> resolveItemStacks(ingredient.itemId, ingredient.amount)
            is RecipeIngredient.Tag -> resolveTagStacks(ingredient.tagId, ingredient.amount)
            is RecipeIngredient.Choice -> ingredient.options.flatMap { resolveIngredientStacks(it) }
        }
    }

    private fun resolveItemStacks(itemId: String, amount: Int): List<ItemStack> {
        val ref = resolveRecipeReference(itemId) ?: return emptyList()
        return when (ref.kind) {
            RecipeSourceKind.SERTRALINE -> {
                Sertraline.itemMap[ref.id] ?: return emptyList()
                val stack = sertralineItemBuilder(ref.id, null) ?: return emptyList()
                stack.amount = amount
                listOf(stack)
            }
            RecipeSourceKind.MINECRAFT -> {
                val mat = ref.material ?: return emptyList()
                listOf(ItemStack(mat, amount))
            }
            RecipeSourceKind.EXTERNAL -> {
                val stack = ExternalItemHelper.buildNoPlayer(ref.provider, ref.id) ?: return emptyList()
                stack.amount = amount
                listOf(stack)
            }
        }
    }

    private fun resolveTagKey(tagId: String): Any {
        val normalized = normalizeTagKey(tagId)
        val (namespace, path) = normalized.split(":", limit = 2)
        val location = createResourceLocation(namespace, path)
        // TagKey.create(Registries.ITEM, location/identifier)
        val methodTagKeyCreate = getDeclaredMethodCompat(
            clazzTagKey,
            listOf("create", "a"),
            null,
            clazzResourceKey,
            clazzResourceLocation
        )

        val itemRegistryKey = getDeclaredFieldCompat(clazzRegistries, "ITEM", "K").get(null)

        return methodTagKeyCreate.invoke(null, itemRegistryKey, location)
    }

    private fun resolveTagStacks(tagId: String, amount: Int): List<ItemStack> {
        val normalized = normalizeTagKey(tagId)
        val bukkitTag = resolveBukkitTag(normalized) ?: return emptyList()
        return bukkitTag.values.map { ItemStack(it, amount) }
    }

    private fun parseId(id: String): Pair<String, String> {
        val idx = id.indexOf(':')
        return if (idx == -1) "minecraft" to id.lowercase()
        else id.substring(0, idx).lowercase() to id.substring(idx + 1)
    }

    private fun normalizeTagKey(key: String): String {
        var n = key.trim()
        if (n.startsWith('#')) n = n.substring(1)
        if (n.startsWith("tag:")) n = "minecraft:" + n.substring(4)
        if (!n.contains(":")) n = "minecraft:$n"
        return n
    }

    private fun resolveBukkitTag(normalized: String): Tag<Material>? {
        return try {
            val key = NamespacedKey.fromString(normalized) ?: return null
            @Suppress("DEPRECATION")
            org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material::class.java)
        } catch (_: Exception) { null }
    }

    // ==================== Reflection cache: RecipeHolder ====================

    private val clazzRecipeHolder by lazy { getClazz("net.minecraft.world.item.crafting.RecipeHolder") }

    private val constructorRecipeHolder by lazy {
        clazzRecipeHolder.constructors.firstOrNull()
            ?: throw IllegalStateException("Cannot find RecipeHolder constructor")
    }

    private val methodHolderId by lazy {
        getDeclaredMethodCompat(clazzRecipeHolder, listOf("id", "a"))
    }

    // ==================== Reflection cache: RecipeManager / RecipeMap ====================

    private val clazzRecipeManager by lazy {
        getClazzCompat(
            assembleMCClass("world.item.crafting.RecipeManager"),
            assembleMCClass("world.item.crafting.CraftingManager")
        )
    }
    private val clazzMinecraftServer by lazy { getClazz("net.minecraft.server.MinecraftServer") }
    private val methodGetServer by lazy {
        clazzMinecraftServer.getDeclaredMethod("getServer")
    }
    private val methodGetRecipeManager by lazy {
        getDeclaredMethodCompat(clazzMinecraftServer, listOf("getRecipeManager", "aI"))
    }

    // RecipeManager.recipes field — always RecipeMap type on 1.21.4+
    private val fieldRecipes: Field by lazy {
        getDeclaredFieldCompat(clazzRecipeManager, "recipes", "e", type = clazzRecipeMap)
    }

    // RecipeMap — immutable, must be rebuilt via create()
    private val clazzRecipeMap by lazy { getClazz(assembleMCClass("world.item.crafting.RecipeMap")) }
    private val methodRecipeMapCreate by lazy {
        getDeclaredMethodCompat(clazzRecipeMap, listOf("create", "a"), null, Iterable::class.java)
    }
    private val methodRecipeMapValues by lazy {
        getDeclaredMethodCompat(clazzRecipeMap, listOf("values", "a"))
    }

    // CraftingManager.finalizeRecipeLoading() — rebuilds the display/listener mappings (f/g/h/i)
    // from the RecipeMap and triggers PlayerList.reloadRecipes() to refresh online players' recipe books.
    // If only field e is rebuilt, the recipe is registered (/recipe give works) but the recipe book
    // display query (displaysForRecipe) cannot find the recipe in listeners, so it is not shown.
    private val methodFinalizeRecipeLoading by lazy {
        getDeclaredMethodCompat(clazzRecipeManager, listOf("finalizeRecipeLoading", "a"))
    }

    // ==================== Reflection cache: player recipe book (RecipeBookServer) ====================
    // Bukkit's Player.discoverRecipe was removed in spigot-api 1.21.4, so only the NMS unlock path is available:
    // EntityPlayer.getRecipeBookServer() → RecipeBookServer.add(Collection<RecipeHolder<?>>, EntityPlayer)
    // (obf: a — the identically named b is remove, do not use; add internally fires the craft event, triggers advancement, sends the Add packet)

    private val clazzCraftPlayer by lazy {
        getClazzCompat("org.bukkit.craftbukkit.entity.CraftPlayer", assembleCBClassCompat("entity.CraftPlayer"))
    }
    private val clazzEntityPlayer by lazy {
        getClazzCompat("net.minecraft.server.level.EntityPlayer", "net.minecraft.server.level.ServerPlayer")
    }
    private val clazzRecipeBookServer by lazy {
        getClazzCompat("net.minecraft.stats.RecipeBookServer", "net.minecraft.stats.ServerRecipeBook")
    }
    private val methodGetHandle by lazy {
        // getHandle is declared on the CraftEntity parent class; getDeclaredMethodCompat cannot find it, must look up the inherited method
        getMethod(clazzCraftPlayer, clazzEntityPlayer, 0)
            ?: throw IllegalStateException("Cannot find CraftPlayer.getHandle()")
    }
    private val methodGetRecipeBookServer by lazy {
        // Constrain by return type: without it the signature fallback in getDeclaredMethodCompat
        // can match an unrelated no-arg method on ServerPlayer, whose return value may be null
        // (observed on 1.21.11: NPE "Cannot invoke Object.getClass() because o is null" at the add() invoke)
        getDeclaredMethodCompat(clazzEntityPlayer, listOf("getRecipeBookServer", "J"), clazzRecipeBookServer)
    }
    private val methodRecipeManagerByKey by lazy {
        getDeclaredMethodCompat(clazzRecipeManager, listOf("byKey", "b"), null, clazzResourceKey)
    }
    private val methodRecipeBookAdd by lazy {
        getDeclaredMethodCompat(
            clazzRecipeBookServer,
            listOf("add", "a"),
            null,
            Collection::class.java,
            clazzEntityPlayer
        )
    }

    /**
     * Resolves the player's recipe book server.
     * Prefers the accessor by name; if it is missing (renamed/relocated on some versions) or returns null,
     * falls back to a name-agnostic field lookup by type on the ServerPlayer hierarchy.
     */
    private fun resolveRecipeBookServer(entityPlayer: Any): Any {
        val byMethod = runCatching { methodGetRecipeBookServer.invoke(entityPlayer) }.getOrNull()
        if (byMethod != null) return byMethod
        return runCatching {
            var clazz: Class<*>? = clazzEntityPlayer
            while (clazz != null) {
                val field = clazz.declaredFields.firstOrNull { clazzRecipeBookServer.isAssignableFrom(it.type) }
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(entityPlayer)
                }
                clazz = clazz.superclass
            }
            throw NoSuchFieldException("recipe book server field in ${clazzEntityPlayer.name} hierarchy")
        }.getOrNull() ?: throw IllegalStateException("Cannot resolve ServerPlayer recipe book server")
    }

    /**
     * Unlocks the given recipes in a player's recipe book (same path as advancement unlock).
     * Makes recipes visible on player join without requiring a prior craft.
     * @return the number of recipes successfully unlocked and sent
     */
    fun unlockForPlayer(player: org.bukkit.entity.Player, recipeIds: Collection<String>): Int {
        if (recipeIds.isEmpty()) return 0
        return try {
            val entityPlayer = methodGetHandle.invoke(player)
            val recipeBook = resolveRecipeBookServer(entityPlayer)
            val manager = methodGetRecipeManager.invoke(methodGetServer.invoke(null))
            val holders = ArrayList<Any>(recipeIds.size)
            for (id in recipeIds) {
                val (namespace, path) = parseId(id)
                val key = createRecipeResourceKey(namespace, path)
                @Suppress("UNCHECKED_CAST")
                val optional = methodRecipeManagerByKey.invoke(manager, key) as java.util.Optional<*>
                if (optional.isPresent) holders.add(optional.get())
            }
            if (holders.isEmpty()) return 0
            methodRecipeBookAdd.invoke(recipeBook, holders, entityPlayer) as Int
        } catch (e: Exception) {
            warningS("Failed to unlock recipe book for ${player.name}: ${e.message}")
            // Reflection/NMS internals: message alone is insufficient (e.g. "Cannot invoke ... because "o" is null");
            // the stack trace is required to locate the failing NMS call
            e.printStackTrace()
            0
        }
    }

    // ==================== 26.1.2-specific: CommonInfo / BookInfo / ItemStackTemplate ====================

    // Recipe.CommonInfo(boolean showNotification)
    private val clazzCommonInfo by lazy { getClazz("net.minecraft.world.item.crafting.Recipe\$CommonInfo") }
    private val constructorCommonInfo by lazy {
        clazzCommonInfo.getDeclaredConstructor(Boolean::class.javaPrimitiveType)
    }

    // CraftingRecipe.CraftingBookInfo(CraftingBookCategory, String group)
    private val clazzCraftingBookInfo by lazy { getClazz("net.minecraft.world.item.crafting.CraftingRecipe\$CraftingBookInfo") }
    private val constructorCraftingBookInfo by lazy {
        clazzCraftingBookInfo.getDeclaredConstructor(clazzCraftingBookCategory, String::class.java)
    }

    // AbstractCookingRecipe.CookingBookInfo(CookingBookCategory, String group)
    private val clazzCookingBookInfo by lazy { getClazz("net.minecraft.world.item.crafting.AbstractCookingRecipe\$CookingBookInfo") }
    private val constructorCookingBookInfo by lazy {
        clazzCookingBookInfo.getDeclaredConstructor(clazzCookingBookCategory, String::class.java)
    }

    // ItemStackTemplate.fromNonEmptyStack(ItemStack) — NMS ItemStack → ItemStackTemplate
    private val clazzItemStackTemplate by lazy { getClazz("net.minecraft.world.item.ItemStackTemplate") }
    private val methodFromNonEmptyStack by lazy {
        clazzItemStackTemplate.getDeclaredMethod("fromNonEmptyStack", clazzNMSItemStack)
    }

    private fun createCommonInfo(showNotification: Boolean): Any {
        return constructorCommonInfo.newInstance(showNotification)
    }

    private fun createCraftingBookInfo(group: String): Any {
        return constructorCraftingBookInfo.newInstance(enumCraftingMISC, group)
    }

    private fun createCookingBookInfo(group: String): Any {
        return constructorCookingBookInfo.newInstance(enumCookingMISC, group)
    }

    private fun toItemStackTemplate(nmsStack: Any): Any {
        return methodFromNonEmptyStack.invoke(null, nmsStack)
    }

    // ==================== Recipe category enums ====================

    private val clazzCraftingBookCategory by lazy { getClazz(assembleMCClass("world.item.crafting.CraftingBookCategory")) }
    private val enumCraftingMISC: Any by lazy {
        clazzCraftingBookCategory.enumConstants.first { it.toString() == "MISC" }
    }

    private val clazzCookingBookCategory by lazy { getClazz(assembleMCClass("world.item.crafting.CookingBookCategory")) }
    private val enumCookingMISC: Any by lazy {
        clazzCookingBookCategory.enumConstants.first { it.toString() == "MISC" }
    }

    // ==================== Register / unregister ====================

    fun register(recipeData: RecipeData): Boolean {
        return try {
            // Same normalization as SimpleRecipeHelper.registerRecipe(): the key stored in
            // registeredRecipes is derived from the normalized path, and unregisterAll passes that
            // key back to unregister(), so both sides must agree.
            val path = normalizeRecipePath(recipeData.id)
            val recipeKey = createRecipeResourceKey(NAMESPACE, path)
            val recipeObj = buildNMSRecipe(recipeData) ?: return false
            val holder = constructorRecipeHolder.newInstance(recipeKey, recipeObj)
            injectRecipe(recipeKey, holder)
            true
        } catch (e: Exception) {
            severeS("NMS recipe registration failed for ${recipeData.id}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    fun unregister(namespace: String, path: String): Boolean {
        return try {
            val recipeKey = createRecipeResourceKey(namespace, path)
            val server = methodGetServer.invoke(null)
            val manager = methodGetRecipeManager.invoke(server)
            val recipeMap = fieldRecipes.get(manager)
            val existingValues = methodRecipeMapValues.invoke(recipeMap) as Collection<*>

            val filtered = existingValues.filter { holder ->
                val holderId = methodHolderId.invoke(holder)
                holderId != recipeKey
            }

            if (filtered.size == existingValues.size) return false

            val newRecipeMap = methodRecipeMapCreate.invoke(null, filtered)
            fieldRecipes.set(manager, newRecipeMap)
            methodFinalizeRecipeLoading.invoke(manager)
            true
        } catch (e: Exception) {
            warningS("NMS recipe unregister failed for $namespace:$path: ${e.message}")
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectRecipe(recipeKey: Any, holder: Any) {
        val server = methodGetServer.invoke(null)
        val manager = methodGetRecipeManager.invoke(server)
        val recipeMap = fieldRecipes.get(manager)

        val existingValues = methodRecipeMapValues.invoke(recipeMap) as Collection<Any>

        // Filter out the old recipe with the same key to avoid ImmutableMap.Builder throwing a duplicate-key exception
        val filtered = existingValues.filter { existing ->
            val existingId = methodHolderId.invoke(existing)
            existingId != recipeKey
        }

        val allValues = ArrayList<Any>(filtered.size + 1)
        allValues.addAll(filtered)
        allValues.add(holder)

        val newRecipeMap = methodRecipeMapCreate.invoke(null, allValues as Collection<Any>)
        fieldRecipes.set(manager, newRecipeMap)
        // Rebuild the display/listener maps and refresh player recipe books, otherwise the injected recipe is not shown in the recipe book
        methodFinalizeRecipeLoading.invoke(manager)
    }

    // ==================== Build dispatch ====================

    private fun buildNMSRecipe(recipe: RecipeData): Any? {
        return when (recipe) {
            is RecipeData.Shaped -> buildShaped(recipe)
            is RecipeData.Shapeless -> buildShapeless(recipe)
            is RecipeData.Cooking -> buildCooking(recipe)
            is RecipeData.Stonecutting -> buildStonecutting(recipe)
            is RecipeData.Smithing -> buildSmithing(recipe)
        }
    }

    // ---- Shaped ----

    private val clazzShapedRecipe by lazy {
        getClazzCompat(
            assembleMCClass("world.item.crafting.ShapedRecipe"),
            assembleMCClass("world.item.crafting.ShapedRecipes")
        )
    }
    private val clazzShapedRecipePattern by lazy { getClazz(assembleMCClass("world.item.crafting.ShapedRecipePattern")) }
    private val methodShapedPatternOf by lazy {
        getDeclaredMethodCompat(clazzShapedRecipePattern, listOf("of", "a"), null, Map::class.java, List::class.java)
    }

    private fun createShapedPattern(ingredientMap: Any?, pattern: List<String>): Any {
        val dataResult = methodShapedPatternOf.invoke(null, ingredientMap, pattern)
        // of() returns ShapedRecipePattern directly on both 1.21.4 / 26.1.2, not a DataResult
        return try {
            val resultMethod = dataResult::class.java.getMethod("result")
            val optional = resultMethod.invoke(dataResult) as java.util.Optional<*>
            optional.orElseThrow { RuntimeException("ShapedRecipePattern.of returned empty") }
        } catch (_: NoSuchMethodException) {
            dataResult
        }
    }

    private fun buildShaped(recipe: RecipeData.Shaped): Any? {
        val result = buildNMSResult(recipe.result) ?: return null
        val ingredientMap = java.util.LinkedHashMap<Char, Any>()
        recipe.key.forEach { (c, ing) -> ingredientMap[c] = createIngredient(ing) }
        val pattern = createShapedPattern(ingredientMap, recipe.pattern)

        return when (minecraftVersion) {
            // ShapedRecipe has both a 5-parameter (with showNotification) and a 4-parameter constructor,
            // constructors.first() order is not guaranteed, must select precisely by parameter count
            MinecraftVersion.v1_21_4 -> clazzShapedRecipe.constructors
                .first { it.parameterCount == 5 }
                .newInstance(
                    recipe.group ?: "",
                    enumCraftingMISC,
                    pattern,
                    result,
                    recipe.showNotification
                )
            MinecraftVersion.v26_1_2 -> clazzShapedRecipe.constructors
                .first { it.parameterCount == 4 }
                .newInstance(
                    createCommonInfo(recipe.showNotification),
                    createCraftingBookInfo(recipe.group ?: ""),
                    pattern,
                    result  // result is already ItemStackTemplate in 26.1.2
                )
        }
    }

    // ---- Shapeless ----

    private val clazzShapelessRecipe by lazy {
        getClazzCompat(
            assembleMCClass("world.item.crafting.ShapelessRecipe"),
            assembleMCClass("world.item.crafting.ShapelessRecipes")
        )
    }
    private val constructorShapeless by lazy { clazzShapelessRecipe.constructors.first() }

    private fun buildShapeless(recipe: RecipeData.Shapeless): Any? {
        val result = buildNMSResult(recipe.result) ?: return null
        val ingredients = recipe.ingredients.map { createIngredient(it) }

        return when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> constructorShapeless.newInstance(
                recipe.group ?: "",
                enumCraftingMISC,
                result,
                java.util.ArrayList(ingredients)
            )
            MinecraftVersion.v26_1_2 -> constructorShapeless.newInstance(
                createCommonInfo(recipe.showNotification),
                createCraftingBookInfo(recipe.group ?: ""),
                result,  // result is already ItemStackTemplate in 26.1.2
                java.util.ArrayList(ingredients)
            )
        }
    }

    // ---- Cooking ----

    private fun buildCooking(recipe: RecipeData.Cooking): Any? {
        val result = buildNMSResult(recipe.result) ?: return null
        val ingredient = createIngredient(recipe.ingredient)

        val recipeClass = when (recipe.type) {
            RecipeType.FURNACE -> getClazzCompat(
                assembleMCClass("world.item.crafting.SmeltingRecipe"),
                assembleMCClass("world.item.crafting.FurnaceRecipe")
            )
            RecipeType.BLASTING -> getClazzCompat(
                assembleMCClass("world.item.crafting.BlastingRecipe"),
                assembleMCClass("world.item.crafting.RecipeBlasting")
            )
            RecipeType.SMOKING -> getClazzCompat(
                assembleMCClass("world.item.crafting.SmokingRecipe"),
                assembleMCClass("world.item.crafting.RecipeSmoking")
            )
            RecipeType.CAMPFIRE -> getClazzCompat(
                assembleMCClass("world.item.crafting.CampfireCookingRecipe"),
                assembleMCClass("world.item.crafting.RecipeCampfire")
            )
            else -> return null
        }

        val constructor = recipeClass.constructors.first()
        return when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> constructor.newInstance(
                recipe.group ?: "",
                enumCookingMISC,
                ingredient,
                result,
                recipe.experience,
                recipe.cookingTime
            )
            MinecraftVersion.v26_1_2 -> constructor.newInstance(
                createCommonInfo(true),
                createCookingBookInfo(recipe.group ?: ""),
                ingredient,
                result,
                recipe.experience,
                recipe.cookingTime
            )
        }
    }

    // ---- Stonecutting ----

    private val clazzStonecutterRecipe by lazy {
        getClazzCompat(
            assembleMCClass("world.item.crafting.StonecutterRecipe"),
            assembleMCClass("world.item.crafting.RecipeStonecutting")
        )
    }
    private val constructorStonecutter by lazy { clazzStonecutterRecipe.constructors.first() }

    private fun buildStonecutting(recipe: RecipeData.Stonecutting): Any? {
        val result = buildNMSResult(recipe.result) ?: return null
        val ingredient = createIngredient(recipe.ingredient)

        return when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> constructorStonecutter.newInstance(
                recipe.group ?: "",
                ingredient,
                result
            )
            MinecraftVersion.v26_1_2 -> constructorStonecutter.newInstance(
                createCommonInfo(true),
                ingredient,
                result
            )
        }
    }

    // ---- Smithing ----

    private val methodOptionalOf by lazy {
        java.util.Optional::class.java.getDeclaredMethod("of", Any::class.java)
    }

    private fun wrapOptional(ingredient: Any): Any {
        return methodOptionalOf.invoke(null, ingredient)
    }

    private fun buildSmithing(recipe: RecipeData.Smithing): Any? {
        val result = buildNMSResult(recipe.result) ?: return null

        return when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> buildSmithing21(recipe, result)
            MinecraftVersion.v26_1_2 -> buildSmithing26(recipe, result)
        }
    }

    // 1.21.4: (Optional<Ingredient> template, Optional<Ingredient> base, Optional<Ingredient> addition, ItemStack result)
    private fun buildSmithing21(recipe: RecipeData.Smithing, result: Any): Any? {
        val template = wrapOptional(createIngredient(recipe.template))
        val base = wrapOptional(createIngredient(recipe.base))
        val addition = wrapOptional(createIngredient(recipe.addition))

        return when (recipe.type) {
            // Smithing recipe classes likewise have multiple constructors with/without extra parameters; select by parameter count
            RecipeType.SMITHING_TRANSFORM -> {
                val clazz = getClazz(assembleMCClass("world.item.crafting.SmithingTransformRecipe"))
                clazz.constructors.first { it.parameterCount == 4 }.newInstance(template, base, addition, result)
            }
            RecipeType.SMITHING_TRIM -> {
                val clazz = getClazz(assembleMCClass("world.item.crafting.SmithingTrimRecipe"))
                clazz.constructors.first { it.parameterCount == 3 }.newInstance(template, base, addition)
            }
            else -> null
        }
    }

    // 26.1.2:
    //   SmithingTransformRecipe(CommonInfo, Optional<Ingredient> template, Ingredient base, Optional<Ingredient> addition, ItemStackTemplate)
    //   SmithingTrimRecipe(CommonInfo, Ingredient, Ingredient, Ingredient, Holder<TrimPattern>) — not supported yet
    private fun buildSmithing26(recipe: RecipeData.Smithing, result: Any): Any? {
        return when (recipe.type) {
            RecipeType.SMITHING_TRANSFORM -> {
                val template = wrapOptional(createIngredient(recipe.template))
                val base = createIngredient(recipe.base)  // 26.1.2: base is not Optional
                val addition = wrapOptional(createIngredient(recipe.addition))
                val clazz = getClazz(assembleMCClass("world.item.crafting.SmithingTransformRecipe"))
                clazz.constructors.first { it.parameterCount == 5 }.newInstance(
                    createCommonInfo(true),
                    template,
                    base,
                    addition,
                    result
                )
            }
            RecipeType.SMITHING_TRIM -> {
                // 26.1.2 requires Holder<TrimPattern>, not supported yet
                warningS("SmithingTrim is not supported on Minecraft 26.1.2 via NMS injection")
                null
            }
            else -> null
        }
    }

    // ---- Result ----

    private fun buildNMSResult(result: RecipeResult): Any? {
        val bukkitStack = buildBukkitResult(result) ?: return null
        val nmsStack = toNMSStack(bukkitStack)
        return when (minecraftVersion) {
            MinecraftVersion.v1_21_4 -> nmsStack
            MinecraftVersion.v26_1_2 -> toItemStackTemplate(nmsStack)
        }
    }

    private fun buildBukkitResult(result: RecipeResult): ItemStack? {
        val ref = resolveRecipeReference(result.itemId) ?: return null
        return when (ref.kind) {
            RecipeSourceKind.SERTRALINE ->
                sertralineItemBuilder(ref.id, null)?.apply { amount = result.count }
            RecipeSourceKind.MINECRAFT -> {
                val mat = ref.material ?: return null
                ItemStack(mat, result.count)
            }
            RecipeSourceKind.EXTERNAL ->
                ExternalItemHelper.buildNoPlayer(ref.provider, ref.id)?.apply { amount = result.count }
        }
    }
}
