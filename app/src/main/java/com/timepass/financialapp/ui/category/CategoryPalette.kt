package com.timepass.financialapp.ui.category

/**
 * The colours a category can take.
 *
 * These are the same hues the seeded categories use, so a user-created category sits
 * visually alongside the built-in ones. The colour matters beyond decoration: it drives
 * the pie chart slices and the accent stripe on every transaction row.
 */
object CategoryPalette {

    val COLORS: List<String> = listOf(
        "#E91E63", "#F06292", "#9C27B0", "#BA68C8",
        "#5B4BC4", "#3F51B5", "#2196F3", "#03A9F4",
        "#00BCD4", "#009688", "#4CAF50", "#8BC34A",
        "#CDDC39", "#FFC107", "#FF9800", "#FF5722",
        "#F44336", "#D32F2F", "#795548", "#607D8B",
        "#9E9E9E", "#4DB6AC", "#66BB6A", "#4FC3F7"
    )

    /**
     * A stable default for a new category, derived from its name so that two categories
     * created in a row don't land on the same colour.
     */
    fun defaultFor(name: String): String =
        COLORS[(name.hashCode() and Int.MAX_VALUE) % COLORS.size]

    /**
     * Emoji offered when creating or editing a category. Chosen for the kinds of spending
     * this app tracks rather than being a general emoji keyboard.
     */
    val EMOJI: List<String> = listOf(
        "👤",
        "👨",
        "👩",
        "👪",
        "✈️",
        "🚗",
        "🚌",
        "🏥",
        "💊",
        "🏠",
        "🏡",
        "🏦",
        "🏛️",
        "💰",
        "💵",
        "💳",
        "📈",
        "📉",
        "🧾",
        "📦",
        "🚨",
        "⏳",
        "🔁",
        "🍔",
        "🍕",
        "☕",
        "🛒",
        "⛽",
        "📱",
        "💡",
        "🎓",
        "🎁",
        "🎬",
        "🏋️",
        "🐾",
        "🔧",
        "🔖",
        "⭐"
    )

    /** Up to two letters to show inside the category avatar. */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '/', '_').filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }
}
