package com.timepass.financialapp.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import com.timepass.financialapp.R

/** Accent colour the app paints its chrome with. */
enum class AccentColor(
    val key: String,
    val themeRes: Int,
    val labelRes: Int,
    val swatchRes: Int
) {
    GREEN("green", R.style.Theme_FinancialApp, R.string.accent_green, R.color.accent_green),
    BLUE("blue", R.style.Theme_FinancialApp_Blue, R.string.accent_blue, R.color.accent_blue),
    INDIGO("indigo", R.style.Theme_FinancialApp_Indigo, R.string.accent_indigo, R.color.accent_indigo),
    TEAL("teal", R.style.Theme_FinancialApp_Teal, R.string.accent_teal, R.color.accent_teal),
    PURPLE("purple", R.style.Theme_FinancialApp_Purple, R.string.accent_purple, R.color.accent_purple),
    ROSE("rose", R.style.Theme_FinancialApp_Rose, R.string.accent_rose, R.color.accent_rose),
    CRIMSON("crimson", R.style.Theme_FinancialApp_Crimson, R.string.accent_crimson, R.color.accent_crimson),
    AMBER("amber", R.style.Theme_FinancialApp_Amber, R.string.accent_amber, R.color.accent_amber),
    OLIVE("olive", R.style.Theme_FinancialApp_Olive, R.string.accent_olive, R.color.accent_olive),
    CYAN("cyan", R.style.Theme_FinancialApp_Cyan, R.string.accent_cyan, R.color.accent_cyan),
    BROWN("brown", R.style.Theme_FinancialApp_Brown, R.string.accent_brown, R.color.accent_brown),
    SLATE("slate", R.style.Theme_FinancialApp_Slate, R.string.accent_slate, R.color.accent_slate);

    companion object {
        fun fromKey(key: String?): AccentColor =
            entries.firstOrNull { it.key == key } ?: GREEN
    }
}

/** Light/dark preference, mapped onto AppCompat's night modes. */
enum class ThemeMode(val key: String, val nightMode: Int, val labelRes: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
