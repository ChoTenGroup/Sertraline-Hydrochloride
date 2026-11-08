package io.github.zzzyyylllty.sertraline.config

/**
 * 将任意值转换为字符串列表。
 * - null → null
 * - List<*> → 展开为字符串列表，每个元素按换行符拆分
 * - 其他类型 → 包装为单元素列表
 */
fun Any?.asListEnhanced() : List<String>? {
    if (this == null) return null
    val thisList = if (this is List<*>) this else listOf(this)
    val list = mutableListOf<String>()
    for (string in thisList) {
        if (string == null) continue
        list.addAll(string.toString().split("\n","<br>", ignoreCase = true))
    }
    if (!list.isEmpty() && list.last() == "") list.removeLast()
    return list
}

fun Any?.asListedStringEnhanced() : String? {
    return this.asListEnhanced()?.joinToString("\n")
}
