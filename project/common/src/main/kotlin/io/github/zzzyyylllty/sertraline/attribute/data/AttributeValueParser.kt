package io.github.zzzyyylllty.sertraline.attribute.data

import java.util.Locale

/**
 * Parses an item attribute value: `100` -> ADD, `"10%"` -> PERCENT 10, `"2x"` -> MULTIPLY 2.
 *
 * ChoTenTech's `c/m` and `a/s` suffixes are accepted too. Returns null when unparsable
 * (the old `choten:` path silently fell back to +1.0, which hid config typos).
 */
fun parseAttributeValue(raw: Any?): Pair<Double, ModifierType>? {
    if (raw == null) return null
    if (raw is Number) return raw.toDouble().takeIf { it.isFinite() }?.let { it to ModifierType.ADD }

    val text = raw.toString().trim()
    if (text.isEmpty()) return null

    // Leading form: "x2" / "X1.5"
    if (text.length > 1 && (text[0] == 'x' || text[0] == 'X')) {
        val value = text.drop(1).trim().toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return value to ModifierType.MULTIPLY
    }

    val (numberPart, type) = when (text.last()) {
        '%' -> text.dropLast(1) to ModifierType.PERCENT
        'c', 'C', 'm', 'M' -> text.dropLast(1) to ModifierType.PERCENT
        'a', 'A', 's', 'S' -> text.dropLast(1) to ModifierType.MULTIPLY
        'x', 'X' -> text.dropLast(1) to ModifierType.MULTIPLY
        else -> text to ModifierType.ADD
    }

    val value = numberPart.trim().toDoubleOrNull() ?: return null
    if (value.isNaN() || value.isInfinite()) return null
    return value to type
}

/** Formats a value for lore display, e.g. `+100`, `-50`, `+15%`, `x2`. Locale-independent. */
fun formatAttributeValue(type: AttributeType?, value: Double, modifierType: ModifierType): String {
    val rounded = if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        // Locale.ROOT: a comma decimal separator would leak into item lore on e.g. de_DE/ru_RU
        String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    }
    // Negative values already carry their sign; "+-50" would be the result of always prepending it
    val signed = if (rounded.startsWith("-")) rounded else "+$rounded"
    return when (modifierType) {
        ModifierType.ADD -> if (type?.isPercentage == true) "$signed%" else signed
        ModifierType.PERCENT -> "$signed%"
        ModifierType.MULTIPLY -> "x$rounded"
    }
}
