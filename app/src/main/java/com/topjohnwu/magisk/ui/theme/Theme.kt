package com.topjohnwu.magisk.ui.theme

import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

enum class Theme(
    val themeName: String,
    val themeRes: Int
) {

    Piplup(
        themeName = "Piplup",
        themeRes = R.style.ThemeFoundationMD2_Piplup
    ),
    PiplupAmoled(
        themeName = "AMOLED",
        themeRes = R.style.ThemeFoundationMD2_Amoled
    ),
    Rayquaza(
        themeName = "Rayquaza",
        themeRes = R.style.ThemeFoundationMD2_Rayquaza
    ),
    Zapdos(
        themeName = "Zapdos",
        themeRes = R.style.ThemeFoundationMD2_Zapdos
    ),
    Charmeleon(
        themeName = "Charmeleon",
        themeRes = R.style.ThemeFoundationMD2_Charmeleon
    ),
    Mew(
        themeName = "Mew",
        themeRes = R.style.ThemeFoundationMD2_Mew
    ),
    Salamence(
        themeName = "Salamence",
        themeRes = R.style.ThemeFoundationMD2_Salamence
    ),
    Fraxure(
        themeName = "Fraxure (Legacy)",
        themeRes = R.style.ThemeFoundationMD2_Fraxure
    ),
    Dynamic(
        themeName = "Material You",
        themeRes = R.style.Theme_Foundation
    );

    val isSelected get() = Config.themeOrdinal == ordinal

    fun select() {
        Config.themeOrdinal = ordinal
    }

    companion object {
        val selected get() = values().getOrNull(Config.themeOrdinal) ?: Piplup

        fun apply(activity: android.app.Activity) {
            val palette = if (selected == PiplupAmoled) Piplup else selected
            if (BuildConfig.STARDUST_UI) {
                activity.setTheme(R.style.Theme_Foundation)
                if (palette != Dynamic)
                    activity.theme.applyStyle(palette.themeRes, true)
                if (Config.amoled || selected == PiplupAmoled)
                    activity.theme.applyStyle(R.style.AmoledBlack, true)
            } else {
                activity.setTheme(selected.themeRes)
                if (Config.amoled || selected == PiplupAmoled)
                    activity.theme.applyStyle(R.style.AmoledBlack, true)
            }
        }
    }

}
