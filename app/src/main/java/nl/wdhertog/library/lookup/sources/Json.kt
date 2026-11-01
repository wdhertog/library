package nl.wdhertog.library.lookup.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* Kleine hulpfuncties om losjes getypeerde JSON van externe bronnen te lezen. */

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

internal fun JsonElement?.int(key: String): Int? = str(key)?.toIntOrNull()

/** Eerste vier opeenvolgende cijfers als jaartal, bijv. "Sep 05, 2019" → 2019. */
internal fun yearIn(text: String?): Int? =
    text?.let { Regex("""(1[5-9]\d\d|20\d\d)""").find(it)?.value?.toInt() }

/** Paginatelling binnen redelijke grenzen; catalogi bevatten soms onzinwaarden. */
internal fun Int?.plausiblePages(): Int? = this?.takeIf { it in 1..5000 }
