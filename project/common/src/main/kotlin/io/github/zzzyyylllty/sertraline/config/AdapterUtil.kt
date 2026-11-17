package io.github.zzzyyylllty.sertraline.config

/**
 * Converts any value to a list of strings.
 * - null → null
 * - List<*> -> Flatten into a string list, splitting each element at newline characters.
 * - Other types -> Wrap as a single-element list.
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
