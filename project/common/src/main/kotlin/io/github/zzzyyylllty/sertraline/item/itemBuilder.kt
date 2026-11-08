package io.github.zzzyyylllty.sertraline.item

//import io.github.projectunified.uniitem.all.AllItemProvider
//import io.github.projectunified.uniitem.api.ItemKey
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.Sertraline.manager
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.manager.PRIVATE_OWNER_TAG
import io.github.zzzyyylllty.sertraline.manager.SubManagerType
import io.github.zzzyyylllty.sertraline.manager.isPrivateItemId
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.impl.getComponentsFilteredNMS
import io.github.zzzyyylllty.sertraline.item.gem.GEM_TAG
import io.github.zzzyyylllty.sertraline.item.gem.copyGemStateToTag
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.impl.setComponentNMS
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseMapNBT
import io.github.zzzyyylllty.sertraline.util.minimessage.cleanRemovedDataComponents
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import io.github.zzzyyylllty.sertraline.util.toUpperCase
import io.github.zzzyyylllty.sertraline.function.update.getExpectedRevision
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import com.cryptomorin.xseries.XMaterial
import org.bukkit.Bukkit
import taboolib.module.lang.asLangText
import taboolib.module.nms.ItemTag
import taboolib.module.nms.NMSItemTag.Companion.asBukkitCopy
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import taboolib.module.nms.getItemTag
import taboolib.platform.util.buildItem


fun itemSource(str: String,player: Player?): ItemStack {
    // material 字段最多两段：`命名空间:标识`；多余段属于配置错误，不能静默丢弃
    val split = str.split(":", limit = 2).toMutableList()
    val key = split.first()
    split.removeFirst()
    // 与 OmniItem / 配方解析共用同一份命名空间别名表。
    // 注意 material 字段的语法与 AbstractSItem 引用语法不同：裸名称（"STONE"）表示原版材质。
    val kind = if (str.contains(":")) SItemFactory.classify(key) else SItemFactory.SourceKind.MINECRAFT
    val item = try {
        when (kind) {
            SItemFactory.SourceKind.MINECRAFT -> XMaterial.valueOf(
                (if (split.isNotEmpty()) split[0] else if (str != "null") str else "GRASS_BLOCK").toUpperCase()
            ).parseItem()
            // material 字段不承载 Sertraline 物品引用：itemBridge 已用 removeById("sertraline") 摘掉
            // 该 provider，走外部分支必然返回 null 并静默变成草方块。这里显式取模板材质。
            SItemFactory.SourceKind.SERTRALINE -> split.firstOrNull()
                ?.let { itemMap[it]?.getMaterial() }
                ?.takeIf { it != Material.AIR }
                ?.let { ItemStack(it) }
                ?: run {
                    warningS("[itemSource] 'sertraline:$key' is not a usable material; use a Minecraft material instead")
                    null
                }
            SItemFactory.SourceKind.EXTERNAL -> {
                if (player != null) {
                    ExternalItemHelper.build(player, key, split.joinToString(":"))
                } else {
                    ExternalItemHelper.buildNoPlayer(key, split.joinToString(":"))
                }
            }
        }
    } catch (e: Exception) {
        severeS(console.asLangText("Error_External_ItemStack_Generation_Failed",str, e))
        e.printStackTrace()
        null
    }
    return item ?: run {
        devLog("Material is null, returning grass block")
        // 1.13+ enum name, not present on 1.12.2; XMaterial resolves by server version (1.12.2 -> GRASS)
        ItemStack(XMaterial.GRASS_BLOCK.parseMaterial() ?: Material.STONE)
    }
}

/**
 * 从 `itemdef` 段落里取基础材质。
 *
 * itemdef 不属于任何模块的 data（只有 itemdefAdapter 在构建阶段读取 config），
 * 因此这里也必须从 [ModernSItem.config] 读原始 YAML，而不是 getDeepData。
 * */
private fun itemdefMaterial(sItem: AbstractSItem): Any? =
    (sItem.config["itemdef"] as? Map<*, *>)?.get("material")

/**
 * Resolves the base stack a build starts from.
 *
 * The configured `material` / `itemdef.material` wins. When a definition declares none, the
 * item's own [AbstractSItem.getMaterial] is used — without that fallback the source string was
 * the literal `"null"`, `itemSource` failed and every such item silently came out as a grass
 * block (which is what Minecraft / external-plugin items did before they got a material key).
 */
private fun resolveItemSource(template: AbstractSItem, player: Player?): ItemStack {
    val configured = (template.getDeepData("xbuilder:material")
        ?: template.getDeepData("minecraft:material")
        ?: itemdefMaterial(template))
        ?.toString()
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    if (configured != null) return itemSource(configured, player)
    val material = template.getMaterial()
    if (material != Material.AIR) return ItemStack(material)
    devLog("Item '${template.key}' declares no material and has no fallback material; using a grass block")
    return ItemStack(XMaterial.GRASS_BLOCK.parseMaterial() ?: Material.STONE)
}

/** Warns that an item id could not be resolved; callers keep the item unchanged. */
private fun warnMissingTemplate(id: String, action: String) {
    warningS("[$action] Item '$id' does not exist; the operation was skipped and the item was left unchanged")
}

/**
 * Generates a specific item.
 *
 * @param template Sertraline ID
 * @param player Player
 * @param source Item source; leave empty to not override
 * @param amount Amount
 * @param overrideData Overrides specific item data; used to preserve item variables when rebuilding an item, generally not needed by developers. Leave empty to not override.
 * */
fun sertralineItemBuilder(template: String,player: Player?,source: ItemStack? = null,amount: Int = 1,overrideData: Map<String, Any?>? = null): ItemStack? {
    return sertralineItemBuilderInternal(template, player, source, amount, overrideData)?.rebuildBypassKeepData(player)
}

/**
 * Generates the specified item with var data.
 *
 * @param template SertralineID
 * @param player the player
 * @param source the item source; leave empty to not override
 * @param amount the amount
 * @param overrideData Overrides specific item data; used to preserve item variables when rebuilding an item, generally not needed by developers. Leave empty to not override.
 * @param vars Built-in item data variables (can be persisted to NBT)
 * @param context Runtime context (objects like Player, Event), accessible via the {context:xxx} tag; not persisted to NBT
 * */
fun sertralineVarItemBuilder(template: String,player: Player?,source: ItemStack? = null,amount: Int = 1,overrideData: Map<String, Any?>? = null, vars: Map<String, Any?>? = null, context: Map<String, Any?>? = null): ItemStack? {
    return sertralineItemBuilderInternal(template, player, source, amount, overrideData,vars = vars, context = context)?.rebuildBypassKeepData(player)
}

/**
 * To generate an item, please use [sertralineItemBuilder]
 * */
fun sertralineItemBuilderInternal(template: String, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null, rebuild: ItemStack? = null, vars: Map<String, Any?>? = null, context: Map<String, Any?>? = null): ItemStack? {
    val isPrivateTemplate = isPrivateItemId(template)
    val rebuildOwner = if (isPrivateTemplate) rebuild?.let(::privateOwnerUuid) else null
    val privateUuid = if (isPrivateTemplate) {
        rebuildOwner ?: player?.uniqueId?.toString() ?: run {
            try { manager.resolvePrivateUuid(null, null) } catch (_: Exception) { return null }
        }
    } else null

    val pTemplate: AbstractSItem
    if (isPrivateTemplate) {
        pTemplate = (manager.privateManager.getItem(privateUuid!!, template, SubManagerType.TEMPORARY)
            ?: manager.privateManager.getItem(privateUuid, template, SubManagerType.PERSISTENT)
            ?: return null).deepCopy()
    } else {
        pTemplate = (resolveItemTemplate(template) ?: return null).deepCopy()
    }
    overrideData?.let {
        it.forEach {
            pTemplate.setDeepData(it.key, it.value)
        }
    }
    vars?.let {
        val ovars = (pTemplate.getDeepData("sertraline:vars") as? Map<String, Any?>?)?.toMutableMap() ?: mutableMapOf()
        ovars.putAll(it)
        pTemplate.setDeepData("sertraline:vars", ovars)
    }
    context?.let {
        val ocontext = pTemplate.getDeepData("sertraline:context") as? MutableMap<String, Any?>? ?: mutableMapOf()
        ocontext.putAll(it)
        pTemplate.setDeepData("sertraline:context", ocontext)
        // keep-context: persist the matched context into a var so {var:xxx} still works after rebuilding
        pTemplate.getDeepData("sertraline:keep-context").asListEnhanced()?.let { keepContext ->
            if (keepContext.isNotEmpty()) {
                val vars = (pTemplate.getDeepData("sertraline:vars") as? Map<String, Any?>?)?.toMutableMap() ?: mutableMapOf()
                keepContext.forEach { key ->
                    ocontext[key]?.let { value -> vars[key] = value.toNbtPersistable() }
                }
                pTemplate.setDeepData("sertraline:vars", vars)
            }
        }
    }
    // On the rebuild path the template used to be resolved from the item stack, which reads it back
    // out of `itemMap` and therefore drops every override applied above. That silently reset the
    // build to the template defaults: a folded gem alteration is marked `persisted` and skipped on
    // later builds, so its bonus disappeared and the next gem was added to the template default
    // rather than the item's current value (100 + 25 instead of 115 + 25).
    // `pTemplate` is already a detached deep copy, so it is safe to mutate and it keeps the
    // overrides — which is also what [applyAlterationsToTemplate] needs to write into.
    //
    // === Alteration: 应用「模板修改 + 实例修改(NBT sertraline_alteration)」到物品数据，为宝石系统等修改数据的功能提供基底 ===
    // Folded before serialization so `{var:...}` placeholders render this build's values and not the
    // ones the incoming stack still carries.
    val appliedAlterations = applyAlterationsToTemplate(pTemplate, rebuild)
    val processedTemplate = itemSerializer(pTemplate, renderContextStack(rebuild, pTemplate), player)
    val itemSource = source ?: resolveItemSource(processedTemplate, player)
    val item = itemManager.processItem(processedTemplate, itemSource, player)
    item.amount = amount
    val ownerUuid = if (isPrivateItemId(processedTemplate.key)) {
        if (rebuild != null) rebuildOwner else privateUuid
    } else null

    if (processedTemplate.getDeepData("sertraline:no-sertraline-id") as? Boolean? ?: false) {
        val tag = item.getItemTag()
        tag.remove(PRIVATE_OWNER_TAG)
        writeAlterationRecordsToTag(tag, appliedAlterations)
        copyGemStateToTag(tag, rebuild)
        tag.saveTo(item)
        return item
    } else {
        val tag = item.getItemTag()
        // Stamp the *storage* key, not the bare key: non-Sertraline sources are registered as
        // `minecraft::<id>` / `<source>::<id>`, so the bare key could not be looked up again and
        // `rebuild()` failed with "Item ... Not Exist". Sertraline items have storageKey == key,
        // so their NBT is unchanged.
        tag["sertraline_id"] = processedTemplate.storageKey
        if (ownerUuid != null) tag[PRIVATE_OWNER_TAG] = ownerUuid else tag.remove(PRIVATE_OWNER_TAG)
        writeAlterationRecordsToTag(tag, appliedAlterations)
        copyGemStateToTag(tag, rebuild)
        // Write the type to NBT
        val typeData = processedTemplate.getDeepData("sertraline:type")
        if (typeData != null) {
            val typeId = when (typeData) {
                is io.github.zzzyyylllty.sertraline.data.Type -> typeData.id
                else -> typeData.toString()
            }
            tag["sertraline_type"] = typeId
        }
        // Write the revision to NBT
        val revision = getExpectedRevision(processedTemplate.key, ownerUuid = ownerUuid)
        if (revision > 0) tag["sertraline_revision"] = revision
        tag.saveTo(item)
        return item
    }
}

/**
 * Uses a temporary ModernSItem, for removing some data in recipes.
 * To generate an item, please use [sertralineItemBuilder]
 * */
fun sertralineItemBuilderTemporary(template: ModernSItem, player: Player?, source: ItemStack? = null, amount: Int = 1, overrideData: Map<String, Any?>? = null, rebuild: ItemStack? = null): ItemStack? {
    val pTemplate = template.deepCopy()
    val isPrivateTemplate = isPrivateItemId(pTemplate.key)
    val rebuildOwner = if (isPrivateTemplate) rebuild?.let(::privateOwnerUuid) else null
    val privateUuid = if (isPrivateTemplate) {
        rebuildOwner ?: player?.uniqueId?.toString() ?: run {
            try { manager.resolvePrivateUuid(null, null) } catch (_: Exception) { return null }
        }
    } else null
    overrideData?.let {
        it.forEach {
            pTemplate.setDeepData(it.key, it.value)
        }
    }
    // See sertralineItemBuilderInternal for why the build must start from `pTemplate` and why the
    // alterations are folded before serialization.
    // === Alteration: 与 sertralineItemBuilderInternal 一致，应用修改并写回 NBT ===
    val appliedAlterations = applyAlterationsToTemplate(pTemplate, rebuild)
    val processedTemplate = itemSerializer(pTemplate, renderContextStack(rebuild, pTemplate), player)
    val itemSource = source ?: resolveItemSource(processedTemplate, player)
    val item = itemManager.processItem(processedTemplate, itemSource, player)
    item.amount = amount
    val ownerUuid = if (isPrivateItemId(processedTemplate.key)) {
        if (rebuild != null) rebuildOwner else privateUuid
    } else null

    val tag = item.getItemTag()
    tag["sertraline_id"] = processedTemplate.storageKey
    if (ownerUuid != null) tag[PRIVATE_OWNER_TAG] = ownerUuid else tag.remove(PRIVATE_OWNER_TAG)
    writeAlterationRecordsToTag(tag, appliedAlterations)
    copyGemStateToTag(tag, rebuild)
    // Write the type to NBT
    val typeData = processedTemplate.getDeepData("sertraline:type")
    if (typeData != null) {
        val typeId = when (typeData) {
            is io.github.zzzyyylllty.sertraline.data.Type -> typeData.id
            else -> typeData.toString()
        }
        tag["sertraline_type"] = typeId
    }
    // Write the revision to NBT
    val revision = getExpectedRevision(processedTemplate.key, ownerUuid = ownerUuid)
    if (revision > 0) tag["sertraline_revision"] = revision
    tag.saveTo(item)
    return item
}

fun ItemStack.rebuild(player: Player?): ItemStack {

    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return this
    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    val regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, amount = this.amount, rebuild = this)
        ?: run { warnMissingTemplate(sID, "rebuild"); return this }
    val newTag = regen.getItemTag()
    carryKeptNbt(newTag, tag)
    newTag.saveTo(regen)
    var rewrited = regen
    var rewritedNMS = asNMSCopy(rewrited)

    val keepComp = SertralineSettings.rebuildKeepComponent
    if (!keepComp.isEmpty()) {
        val orgComponent = asNMSCopy(this).getComponentsFilteredNMS()
        keepComp.forEach {
            orgComponent[it]?.let { value -> rewritedNMS.setComponentNMS(it, value)?.let { rewritedNMS = it } }
        }
        rewrited = asBukkitCopy(rewritedNMS)
    }
    return rewrited
}

/**
 * Copies the `rebuild.keep-data` NBT nodes from [source] onto [target].
 *
 * Two nodes are deliberately skipped because the build pipeline already carries them, and
 * copying the *old* value back would undo what the build just computed:
 *
 * - [GEM_TAG] is rebuilt from primitives by [copyGemStateToTag]. Copying it here as well goes
 *   through `parseNBT()`, which yields a Gson `JsonArray` for the `used` list, and
 *   `ItemTag.toNBT()` rejects that with `IllegalStateException: Unsupported nbt: [...]`. The
 *   exception aborts the whole rebuild, so socketing a gem fails halfway: the weapon is already
 *   mutated while the gem is not consumed and no feedback is sent.
 * - `sertraline_data` holds the instance vars and is passed into the build as
 *   `overrideData["sertraline:vars"]`; the adapter then writes the merged result back. Copying
 *   the pre-build node over it would discard every alteration that was just folded in — a +15
 *   gem would look like it did nothing on socket, and unsocketing would still subtract 15,
 *   silently draining the stat by 15 per socket/unsocket cycle.
 *
 * Both entries stay supported in config so existing servers keep working; they are simply not
 * needed any more.
 */
private fun carryKeptNbt(target: ItemTag, source: ItemTag) {
    SertralineSettings.rebuildKeepData.forEach { key ->
        if (key == GEM_TAG || key == "sertraline_data") return@forEach
        // `source[key]` may be absent (an item built before the node existed). Writing null here
        // would call `ItemTag.set(key, null)` -> `removeDeep(key)`, deleting the value the build
        // just computed — that silently stripped e.g. `sertraline_revision` from every rebuilt item.
        // Moving the whole `ItemTagData` node also bypasses `parseNBT()`, whose LIST branch returns a
        // Gson `JsonArray`, and `ItemTagData.toNBT` rejects that with `Unsupported nbt`.
        val node = source[key] ?: return@forEach
        target.put(key, node)
    }
}

/**
 * A copy of [rebuild] whose persisted vars match [template].
 *
 * `${var:...}$` is resolved from the vars stored on the stack being processed, and on a rebuild that
 * stack still holds the values from before this build. Handing that stale stack to tag processing is
 * what made the rendered lore lag one rebuild behind: a +25 gem showed up in the NBT immediately but
 * only became visible in the lore after the next socket/unsocket.
 */
private fun renderContextStack(rebuild: ItemStack?, template: AbstractSItem): ItemStack? {
    if (rebuild == null) return null
    val stack = rebuild.clone()
    val vars = template.getDeepData("sertraline:vars") as? Map<String, Any?> ?: return stack
    val tag = stack.getItemTag(true)
    tag["sertraline_data"] = LinkedHashMap(vars)
    tag.saveTo(stack)
    return stack
}

fun ItemStack.rebuildBypassKeepData(player: Player?): ItemStack {

    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return this

    // Fast path: if there is no dynamics and no NBT instance data, rebuilding is redundant
    val template = itemTemplateForStack(this, player)
    if (template != null && template.getDeepData("sertraline:dynamics") == null) {
        val nbtData = tag["sertraline_data"]?.parseMapNBT()
        if (nbtData == null) {
            return this // First build, no dynamic content �?rebuild would not change the result
        }
    }

    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    val regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, amount = this.amount, rebuild = this)
        ?: run { warnMissingTemplate(sID, "rebuildBypassKeepData"); return this }

    return regen
}

fun ItemStack.rebuildLore(player: Player?) {

    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return
    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    val regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, rebuild = this) ?: run { warnMissingTemplate(sID, "rebuild"); return }
    PlatformCompat.setLore(this, PlatformCompat.getLore(regen))
}


/**
 * This will lost about 2%(3 in 76) Component data.
 * */
fun ItemStack.rebuildUnsafe(player: Player?) {

    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return
    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    var regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, rebuild = this) ?: run { warnMissingTemplate(sID, "rebuild"); return }

    var rewritedNMS = asNMSCopy(regen)
    val keepComp = SertralineSettings.rebuildKeepComponent
    if (!keepComp.isEmpty()) {
        val orgComponent = asNMSCopy(this).getComponentsFilteredNMS()
        keepComp.forEach {
            orgComponent[it]?.let { value -> rewritedNMS.setComponentNMS(it, value)?.let { rewritedNMS = it } }
        }
        regen = asBukkitCopy(rewritedNMS)
    }

    val newTag = regen.getItemTag()
    carryKeptNbt(newTag, tag)
    newTag.saveTo(regen)
    this.setItemMeta(regen.itemMeta)
}

fun ItemStack.rebuildName(player: Player?) {
    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return
    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    val regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, rebuild = this) ?: run { warnMissingTemplate(sID, "rebuild"); return }
    val name = PlatformCompat.getEffectiveName(regen)
    if (VersionHelper.isOrAbove12005()) {
        PlatformCompat.setDataComponent(this, "CUSTOM_NAME", name)
    } else {
        this.itemMeta?.let { meta ->
            PlatformCompat.setDisplayName(meta, name)
            this.setItemMeta(meta)
        }
    }
}

fun ItemStack.rebuildDisplay(player: Player?) {

    val tag = this.clone().getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return
    val overrideData = mutableMapOf<String, Any?>()
    overrideData["sertraline:vars"] = tag["sertraline_data"]?.parseMapNBT()
    val regen = sertralineItemBuilderInternal(sID, player,overrideData = overrideData, rebuild = this) ?: run { warnMissingTemplate(sID, "rebuild"); return }
    PlatformCompat.setLore(this, PlatformCompat.getLore(regen))
    val name = PlatformCompat.getEffectiveName(regen)
    if (VersionHelper.isOrAbove12005()) {
//        val component = if (VersionHelper.isOrAbove12104()) "ITEM_NAME" else "CUSTOM_NAME"
        val component =  "CUSTOM_NAME"
        PlatformCompat.setDataComponent(this, component, name)
    } else {
        this.itemMeta?.let { meta ->
            PlatformCompat.setDisplayName(meta, name)
            PlatformCompat.setLore(meta, PlatformCompat.getLore(regen))
            this.setItemMeta(meta)
        }
    }

}

/**
 * Convert a context value into a type persistable to NBT.
 * String/Number/Boolean/arrays stay as-is; Player/Entity become name; Map/Collection recurse;
 * other objects become strings when possible. Used by keep-context to store runtime context as a var.
 */
private fun Any?.toNbtPersistable(): Any? = when (this) {
    null -> null
    is String, is Number, is Boolean -> this
    is ByteArray, is IntArray, is LongArray -> this
    is Player -> name
    is Entity -> name
    is Map<*, *> -> entries.associate { it.key.toString() to it.value.toNbtPersistable() }
    is Collection<*> -> map { it.toNbtPersistable() }
    else -> toString()
}