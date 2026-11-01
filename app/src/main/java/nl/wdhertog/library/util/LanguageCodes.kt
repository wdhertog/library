package nl.wdhertog.library.util

import java.util.Locale

/** Zet driedelige taalcodes uit bibliotheekcatalogi (ISO 639-2, bijv. "dut", "ger") om naar ISO 639-1 ("nl", "de"). */
object LanguageCodes {

    /** Bibliografische codes (639-2/B) die afwijken van de terminologische (639-2/T) die Java kent. */
    private val bibliographic = mapOf(
        "dut" to "nl", "ger" to "de", "fre" to "fr", "chi" to "zh", "cze" to "cs", "gre" to "el",
        "per" to "fa", "rum" to "ro", "slo" to "sk", "wel" to "cy", "arm" to "hy", "baq" to "eu",
        "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
        "alb" to "sq", "tib" to "bo", "fry" to "fy",
    )

    private val terminological: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { code ->
            runCatching { Locale.forLanguageTag(code).isO3Language }.getOrDefault("")
        }
    }

    fun toIso1(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.ROOT)?.removePrefix("/languages/") ?: return null
        return when (c.length) {
            2 -> c
            3 -> bibliographic[c] ?: terminological[c]
            else -> null
        }
    }
}
