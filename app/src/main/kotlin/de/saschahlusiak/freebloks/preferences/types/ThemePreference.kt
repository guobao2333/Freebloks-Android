package de.saschahlusiak.freebloks.preferences.types

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import de.saschahlusiak.freebloks.theme.ThemeManager

class ThemePreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    init {
        val tm = ThemeManager.get(context)
        val themes = when(key) {
            "theme" -> tm.backgroundThemes
            "board_theme" -> tm.boardThemes
            else -> emptyList()
        }

        val entries = arrayOfNulls<String>(themes.size)
        val values = arrayOfNulls<String>(themes.size)

        for (i in themes.indices) {
            val theme = themes[i]

            entries[i] = theme.getLabel(context)
            values[i] = theme.name
        }

        this.entries = entries
        this.entryValues = values
    }
}
