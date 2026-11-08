package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.data.AlterationOperation.*

@Suppress("UNCHECKED_CAST")
data class SDataAlteration(
    val name: String,
    val key: String,
    val operation: AlterationOperation = AlterationOperation.SET,
    val value: Any? = null
) {
    // Compile-time constants
    companion object {
        private const val COLON = ':'
        private const val DOT = '.'

        fun set(name: String, key: String, value: Any?) = SDataAlteration(name, key, AlterationOperation.SET, value)
        fun add(name: String, key: String, value: Number) = SDataAlteration(name, key, AlterationOperation.ADD, value)
        fun remove(name: String, key: String) = SDataAlteration(name, key, AlterationOperation.REMOVE)
        fun listAdd(name: String, key: String, value: Any?) = SDataAlteration(name, key, AlterationOperation.LIST_ADD, value)
        fun listAddLast(name: String, key: String, value: Any?) = SDataAlteration(name, key, AlterationOperation.LIST_ADD_LAST, value)
        fun listRemoveIndex(name: String, key: String, index: Int) = SDataAlteration(name, key, AlterationOperation.LIST_REMOVE, index)
        fun mapSet(name: String, key: String, mapKey: String, value: Any?) =
            SDataAlteration(name, key, AlterationOperation.MAP_SET, mapOf("key" to mapKey, "value" to value))
        fun mapRemove(name: String, key: String, mapKey: String) =
            SDataAlteration(name, key, AlterationOperation.MAP_REMOVE, mapKey)
        fun listRemoveValue(name: String, key: String, value: Any?) = SDataAlteration(name, key, AlterationOperation.LIST_REMOVE_VALUE, value)
        fun listRemoveAll(name: String, key: String, value: Any?) = SDataAlteration(name, key, AlterationOperation.LIST_REMOVE_ALL, value)
        fun listClear(name: String, key: String) = SDataAlteration(name, key, AlterationOperation.LIST_CLEAR)
        fun mapClear(name: String, key: String) = SDataAlteration(name, key, AlterationOperation.MAP_CLEAR)
    }

    @Transient
    private var cachedPath: ParsedPath? = null

    private fun getParsedPath(): ParsedPath {
        if (cachedPath == null) {
            cachedPath = parsePath(key)
        }
        return cachedPath!!
    }

    /**
     * Applies to the item data map
     */
    fun applyTo(data: LinkedHashMap<String, Any?>) {
        val path = getParsedPath()

        when (operation) {
            SET -> setDeepData(path, value, data)
            ADD -> addDeepData(path, value as? Number, data)
            REMOVE -> removeDeepData(path, data)
            LIST_ADD -> listAddDeepData(path, value, data)
            LIST_ADD_LAST -> listAddLastDeepData(path, value, data)
            LIST_REMOVE -> {
                if (value is Int) listRemoveDeepData(path, value, data)
            }
            MAP_SET -> {
                if (value is Map<*, *>) {
                    val mapKey = value["key"] as? String
                    if (mapKey != null) mapSetDeepData(path, mapKey, value["value"], data)
                }
            }
            MAP_REMOVE -> {
                if (value is String) mapRemoveDeepData(path, value, data)
            }
            LIST_REMOVE_VALUE -> {
                listRemoveValueDeepData(path, value, data)
            }
            LIST_CLEAR -> {
                listClearDeepData(path, value, data)
            }
            MAP_CLEAR -> {
                mapClearDeepData(path, value, data)
            }
            LIST_REMOVE_ALL -> {
                listRemoveAllDeepData(path, value, data)
            }
        }
    }

    private fun setDeepData(path: ParsedPath, newValue: Any?, data: LinkedHashMap<String, Any?>) {
        when {
            path.depth == 0 -> {
                data[path.major] = newValue
            }
            path.depth == 1 -> {
                val majorMap = getOrCreateMap(data, path.major)
                majorMap[path.segments[0]] = newValue
            }
            else -> {
                val majorMap = getOrCreateMap(data, path.major)
                var current: Any? = majorMap

                for (i in 0 until path.depth - 1) {
                    val key = path.segments[i]
                    val next = (current as? MutableMap<String, Any?>)?.get(key)

                    current = when (next) {
                        is MutableMap<*, *> -> next
                        else -> {
                            val newMap = mutableMapOf<String, Any?>()
                            (current as MutableMap<String, Any?>)[key] = newMap
                            newMap
                        }
                    }
                }

                (current as MutableMap<String, Any?>)[path.segments[path.depth - 1]] = newValue
            }
        }
    }

    private fun navigateToValue(path: ParsedPath, data: LinkedHashMap<String, Any?>): Any? {
        return when {
            path.depth == 0 -> data[path.major]
            path.depth == 1 -> (data[path.major] as? Map<*, *>)?.get(path.segments[0])
            else -> {
                var current: Any? = data[path.major]
                for (i in 0 until path.depth) {
                    current = (current as? Map<*, *>)?.get(path.segments[i])
                    if (current == null) return null
                }
                current
            }
        }
    }

    private fun navigateToParentForRemoval(
        path: ParsedPath,
        data: LinkedHashMap<String, Any?>
    ): Pair<MutableMap<String, Any?>?, String>? {
        return when {
            path.depth == 0 -> data to path.major
            path.depth == 1 -> {
                val majorMap = data[path.major] as? MutableMap<String, Any?> ?: return null
                majorMap to path.segments[0]
            }
            else -> {
                val majorMap = data[path.major] as? MutableMap<String, Any?> ?: return null
                var current: Any? = majorMap

                for (i in 0 until path.depth - 1) {
                    current = (current as? Map<*, *>)?.get(path.segments[i]) ?: return null
                }

                (current as? MutableMap<String, Any?>) to path.segments[path.depth - 1]
            }
        }
    }

    private fun addDeepData(path: ParsedPath, value: Number?, data: LinkedHashMap<String, Any?>) {
        if (value == null) return

        val current = navigateToValue(path, data)
        // Missing target: treat it as 0 so a `+n` gem still works on an item whose
        // template never declared that variable. A non-numeric existing value is
        // left untouched (adding a number to a string is meaningless).
        if (current == null) {
            setDeepData(path, value, data)
            return
        }
        if (current is Number) {
            val newValue = when (current) {
                is Double -> current + value.toDouble()
                is Float -> current + value.toFloat()
                is Long -> current + value.toLong()
                is Int -> current + value.toInt()
                else -> return
            }
            setDeepData(path, newValue, data)
        }
    }

    private fun removeDeepData(path: ParsedPath, data: LinkedHashMap<String, Any?>) {
        when {
            path.depth == 0 -> data.remove(path.major)
            path.depth == 1 -> {
                (data[path.major] as? MutableMap<String, Any?>)?.remove(path.segments[0])
            }
            else -> {
                navigateToParentForRemoval(path, data)?.let { (parent, lastKey) ->
                    parent?.remove(lastKey)
                }
            }
        }
    }

    private fun listAddDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>

        if (list != null) {
            list.add(value)
        } else {
            setDeepData(path, mutableListOf(value), data)
        }
    }

    private fun listAddLastDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>

        if (list != null) {
            // addLast is a Java 21 SequencedCollection API, absent from the jvmTarget 8 compile surface; add appends to the end
            list.add(value)
        } else {
            setDeepData(path, mutableListOf(value), data)
        }
    }

    private fun listRemoveDeepData(path: ParsedPath, index: Int, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>
        if (list != null && index in list.indices) {
            list.removeAt(index)
        }
    }

    private fun listRemoveValueDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>
        if (list != null) {
            list.remove(value)
        }
    }

    private fun listRemoveAllDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>
        if (list != null) {
            list.removeAll { it == value }
        }
    }

    private fun listClearDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val list = navigateToValue(path, data) as? MutableList<Any?>
        list?.clear()
    }

    private fun mapClearDeepData(path: ParsedPath, value: Any?, data: LinkedHashMap<String, Any?>) {
        val map = navigateToValue(path, data) as? MutableMap<String, Any?>
        map?.clear()
    }

    private fun mapSetDeepData(
        path: ParsedPath,
        mapKey: String,
        mapValue: Any?,
        data: LinkedHashMap<String, Any?>
    ) {
        val map = navigateToValue(path, data) as? MutableMap<String, Any?>

        if (map != null) {
            map[mapKey] = mapValue
        } else {
            setDeepData(path, mutableMapOf(mapKey to mapValue), data)
        }
    }

    private fun mapRemoveDeepData(path: ParsedPath, mapKey: String, data: LinkedHashMap<String, Any?>) {
        val map = navigateToValue(path, data) as? MutableMap<String, Any?>
        map?.remove(mapKey)
    }

    private fun parsePath(location: String): ParsedPath {
        val colonIdx = location.indexOf(COLON)

        return if (colonIdx == -1) {
            ParsedPath(location, 0, emptyArray())
        } else {
            val major = location.substring(0, colonIdx)
            val subPath = location.substring(colonIdx + 1)

            if (subPath.isEmpty()) {
                ParsedPath(major, 0, emptyArray())
            } else {
                val segments = parseSegments(subPath)
                ParsedPath(major, segments.size, segments)
            }
        }
    }

    private fun parseSegments(subPath: String): Array<String> {
        var count = 1
        for (ch in subPath) {
            if (ch == DOT) count++
        }

        val segments = Array(count) { "" }
        var lastIdx = 0
        var segIdx = 0

        for (i in subPath.indices) {
            if (subPath[i] == DOT) {
                segments[segIdx] = subPath.substring(lastIdx, i)
                segIdx++
                lastIdx = i + 1
            }
        }

        segments[segIdx] = subPath.substring(lastIdx)
        return segments
    }

    private fun getOrCreateMap(
        data: LinkedHashMap<String, Any?>,
        key: String
    ): MutableMap<String, Any?> {
        val existing = data[key]
        return if (existing is MutableMap<*, *>) {
            existing as MutableMap<String, Any?>
        } else {
            mutableMapOf<String, Any?>().also { data[key] = it }
        }
    }
}

private data class ParsedPath(
    val major: String,
    val depth: Int,
    val segments: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedPath) return false
        return major == other.major && depth == other.depth && segments.contentEquals(other.segments)
    }

    override fun hashCode(): Int {
        var result = major.hashCode()
        result = 31 * result + depth
        result = 31 * result + segments.contentHashCode()
        return result
    }
}

fun List<SDataAlteration?>.applyAllTo(data: LinkedHashMap<String, Any?>) {
    for (alteration in this) {
        alteration?.applyTo(data)
    }
}


fun List<SDataAlteration?>.applyAlterations(data: LinkedHashMap<String, Any?>) {
    for (alteration in this) {
        alteration?.applyTo(data)
    }
}

fun LinkedHashMap<String, Any?>.applyAlterations(alterations: List<SDataAlteration?>) {
    for (alteration in alterations) {
        alteration?.applyTo(this)
    }
}

enum class AlterationOperation {
    SET, ADD, REMOVE, LIST_ADD, LIST_ADD_LAST, LIST_REMOVE, LIST_REMOVE_VALUE, LIST_REMOVE_ALL, LIST_CLEAR, MAP_SET, MAP_REMOVE, MAP_CLEAR
}
