package nl.wdhertog.library.importer

import android.content.Context

/**
 * Onthoudt de kolomtoewijzing per soort bestand (herkend aan de kopregel), zodat een
 * volgende import van hetzelfde bestand meteen goed staat.
 */
class ImportProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("import-profiles", Context.MODE_PRIVATE)

    fun load(header: List<String>): List<ColumnTarget>? {
        val saved = prefs.getString(key(header), null)?.split('|') ?: return null
        if (saved.size != header.size) return null
        return saved.map { name -> ColumnTarget.entries.firstOrNull { it.name == name } ?: ColumnTarget.IGNORE }
    }

    fun save(header: List<String>, targets: List<ColumnTarget>) {
        prefs.edit().putString(key(header), targets.joinToString("|") { it.name }).apply()
    }

    private fun key(header: List<String>) = header.joinToString("|") { it.trim().lowercase() }
}
