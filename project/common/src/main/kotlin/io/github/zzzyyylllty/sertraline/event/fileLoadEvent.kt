package io.github.zzzyyylllty.sertraline.event

import io.github.zzzyyylllty.sertraline.config.LoadContext
import org.bukkit.event.player.PlayerInteractEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.type.BukkitProxyEvent
import java.io.File

/**
 * File loading pipeline events.
 *
 * Naming convention: XxxFileLoadPreEvent is called before that category's
 * loading pipeline starts, XxxFileLoadPostEvent after it finishes
 * (on failure [AbstractFileLoadPostEvent.error] is non-null).
 *
 * A non-null [LoadContext] means the reload candidate-build phase: listeners
 * should read/write the context's candidate tables instead of the live
 * registries. Null means the legacy entry point (in-place live update).
 */

/** Base class of per-category file loading events. */
abstract class AbstractFileLoadEvent(
    /** Load context; null for the legacy live entry point. */
    val context: LoadContext?,
) : BukkitProxyEvent()

/** Base class of file loading Post events; [error] is non-null when the pipeline threw. */
abstract class AbstractFileLoadPostEvent(
    context: LoadContext?,
    val error: Throwable? = null,
) : AbstractFileLoadEvent(context) {
    val success: Boolean get() = error == null
}

// ==================== All ====================

/**
 * Called before the whole config file loading pipeline (reload candidate-build
 * phase) starts, i.e. before any category loader (templates/mappings/tiers/items/...).
 */
class SertralineAllFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)

/**
 * Called after the whole config file loading pipeline completes.
 * [published] is true when the candidates were successfully published to the
 * live registries; false means the candidate build failed and was discarded
 * (live registries keep their last-good state).
 */
class SertralineAllFileLoadPostEvent(
    context: LoadContext?,
    val published: Boolean,
    error: Throwable? = null,
) : AbstractFileLoadPostEvent(context, error)

// ==================== Item ====================

class SertralineItemFileLoadPreEvent(
    context: LoadContext?,
    /** Whether this round runs in incremental loading mode. */
    val incremental: Boolean,
) : AbstractFileLoadEvent(context)

class SertralineItemFileLoadPostEvent(
    context: LoadContext?,
    /** Entry count after loading (candidate context or live registry). */
    val itemCount: Int,
    error: Throwable? = null,
) : AbstractFileLoadPostEvent(context, error)

// ==================== Recipe ====================

class SertralineRecipeFileLoadPreEvent() : AbstractFileLoadEvent(null)

class SertralineRecipeFileLoadPostEvent(
    /** Number of parsed candidate recipes; 0 with non-null [error] when prepare failed. */
    val candidateCount: Int,
    error: Throwable? = null,
) : AbstractFileLoadPostEvent(null, error)

// ==================== Mapping ====================

class SertralineMappingFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineMappingFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== Tier ====================

class SertralineTierFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineTierFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== Type ====================

class SertralineTypeFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineTypeFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== Level ====================

class SertralineLevelFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineLevelFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== LoreFormat ====================

class SertralineLoreFormatFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineLoreFormatFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== CraftingStation ====================

class SertralineCraftingStationFileLoadPreEvent(context: LoadContext?) : AbstractFileLoadEvent(context)
class SertralineCraftingStationFileLoadPostEvent(context: LoadContext?, error: Throwable? = null) : AbstractFileLoadPostEvent(context, error)

// ==================== Single file ====================

/**
 * Called before a single item file is parsed; fired once per matched file.
 */
class SertralineItemSingleFileLoadPreEvent(
    val file: File,
    context: LoadContext?,
) : AbstractFileLoadEvent(context)

class SertralineItemSingleFileLoadPostEvent(
    val file: File,
    context: LoadContext?,
    /** Whether this file was parsed successfully. */
    val loaded: Boolean,
    error: Throwable? = null,
) : AbstractFileLoadPostEvent(context, error)
