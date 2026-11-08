package io.github.zzzyyylllty.sertraline.data.external

import io.github.zzzyyylllty.sertraline.data.deepCopyMap

data class ExternalItemData(
    val source: String,
    val id: String,
    val metaData: LinkedHashMap<String, Any?>? = null
) {
    /** True deep copy — [metaData] is mutable and must not be shared between templates and builds. */
    fun deepCopy(): ExternalItemData = ExternalItemData(
        source = source,
        id = id,
        metaData = metaData?.let { deepCopyMap(it) },
    )
}