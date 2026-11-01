package nl.wdhertog.library.lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Een bron gaf een onverwacht antwoord of was niet bereikbaar. */
class LookupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * HTTP voor de open bronnen. Stuurt een herkenbare User-Agent mee (vereist door Wikimedia
 * en Open Library) en houdt per host een minimale tijd tussen verzoeken aan, zodat we
 * binnen de limieten van gratis diensten blijven.
 */
class LookupHttp(
    private val client: OkHttpClient = defaultClient(),
    private val minIntervalMillis: Map<String, Long> = mapOf("query.wikidata.org" to 1_000L, "openlibrary.org" to 300L),
) {
    private val lastRequest = mutableMapOf<String, Long>()
    private val throttle = Mutex()

    suspend fun get(url: String, accept: String? = null): String? =
        execute(Request.Builder().url(url).apply { accept?.let { header("Accept", it) } }.build())

    suspend fun postForm(url: String, form: Map<String, String>, accept: String): String? {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).post(body).header("Accept", accept).build())
    }

    /** Geeft de body terug, null bij 404, en gooit [LookupException] bij andere fouten. */
    private suspend fun execute(request: Request, attempt: Int = 0): String? {
        waitForTurn(request.url.host)
        val (code, body, retryAfter) = withContext(Dispatchers.IO) {
            try {
                client.newCall(request.newBuilder().header("User-Agent", USER_AGENT).build()).execute().use { response ->
                    Triple(response.code, if (response.isSuccessful) response.body.string() else null, response.header("Retry-After"))
                }
            } catch (e: IOException) {
                throw LookupException("${request.url.host} niet bereikbaar", e)
            }
        }
        return when {
            code in 200..299 -> body
            code == 404 -> null
            code == 429 && attempt < 2 -> {
                delay((retryAfter?.toLongOrNull()?.times(1_000) ?: 5_000L).coerceAtMost(30_000))
                execute(request, attempt + 1)
            }
            else -> throw LookupException("HTTP $code van ${request.url.host}")
        }
    }

    private suspend fun waitForTurn(host: String) {
        val interval = minIntervalMillis[host] ?: return
        throttle.withLock {
            val wait = (lastRequest[host] ?: 0L) + interval - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequest[host] = System.currentTimeMillis()
        }
    }

    companion object {
        const val USER_AGENT = "BibliotheekApp/0.1 (https://github.com/wdhertog/library)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
