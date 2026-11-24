package app.voicemagic.ai

import app.voicemagic.BuildConfig
import app.voicemagic.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** What the cloud AI layer can currently do. Never fakes availability. */
sealed class AiStatus {
    /** No backend URL / token configured. */
    object NotConfigured : AiStatus()
    /** Backend configured but unreachable. */
    data class Unreachable(val detail: String) : AiStatus()
    /** Backend reachable but no AI provider (API key) is set up on the server. */
    data class BackendWithoutProvider(val credits: Int) : AiStatus()
    data class Ready(val credits: Int, val premium: Boolean) : AiStatus()
}

class AiException(val code: String, message: String) : Exception(message)

class ConvertResult(val wav: ByteArray, val creditsRemaining: Int?)

/** Cloud AI contract. Providers are replaceable: the app only talks to this interface. */
interface CloudAiClient {
    suspend fun status(): AiStatus
    /** Uploads one consented sample. Caller MUST have shown the cloud upload disclosure first. */
    suspend fun createProfile(name: String, sample: File): String
    suspend fun deleteProfile(cloudProfileId: String)
    suspend fun convert(cloudProfileId: String, source: File): ConvertResult
    suspend fun deleteAccountData()
}

/** Talks to this project's FastAPI backend (see /backend). Provider API keys live only on the server. */
class BackendClient(private val settings: SettingsRepository) : CloudAiClient {

    private suspend fun base(): String? = settings.backendUrl.first().takeIf { it.isNotBlank() }
    private suspend fun token(): String = settings.apiToken()

    private fun checkUrl(url: String) {
        val u = URL(url)
        val local = u.host == "localhost" || u.host == "10.0.2.2" || u.host == "127.0.0.1"
        if (u.protocol != "https" && !(BuildConfig.DEBUG && local)) {
            throw AiException("insecure_url", "Backend URL must use https://")
        }
    }

    override suspend fun status(): AiStatus = withContext(Dispatchers.IO) {
        val b = base()
        val t = token()
        if (b == null || t.isEmpty()) return@withContext AiStatus.NotConfigured
        try {
            checkUrl(b)
            val (code, body) = request("GET", "$b/v1/status", t)
            if (code == 401) return@withContext AiStatus.Unreachable("Invalid token")
            if (code !in 200..299) return@withContext AiStatus.Unreachable("HTTP $code")
            val j = JSONObject(String(body))
            val credits = j.optInt("credits", 0)
            if (j.optBoolean("ai_configured", false)) AiStatus.Ready(credits, j.optBoolean("premium", false))
            else AiStatus.BackendWithoutProvider(credits)
        } catch (e: AiException) {
            AiStatus.Unreachable(e.message ?: "error")
        } catch (e: Exception) {
            AiStatus.Unreachable(e.javaClass.simpleName)
        }
    }

    override suspend fun createProfile(name: String, sample: File): String = withContext(Dispatchers.IO) {
        val (b, t) = requireConfigured()
        val (code, body) = multipart("$b/v1/ai/profiles", t, mapOf("name" to name, "consent" to "true"), "sample" to sample)
        parseOrThrow(code, body).getString("profile_id")
    }

    override suspend fun deleteProfile(cloudProfileId: String) {
        withContext(Dispatchers.IO) {
            val (b, t) = requireConfigured()
            val (code, body) = request("DELETE", "$b/v1/ai/profiles/$cloudProfileId", t)
            if (code !in 200..299 && code != 404) throw errorFrom(code, body)
        }
    }

    override suspend fun convert(cloudProfileId: String, source: File): ConvertResult = withContext(Dispatchers.IO) {
        val (b, t) = requireConfigured()
        val boundary = "----vm" + UUID.randomUUID()
        val conn = open("POST", "$b/v1/ai/convert", t)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.outputStream.use { writeMultipart(it, boundary, mapOf("profile_id" to cloudProfileId), "audio" to source) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw errorFrom(code, bytes)
        ConvertResult(bytes, conn.getHeaderField("X-Credits-Remaining")?.toIntOrNull())
    }

    override suspend fun deleteAccountData() {
        withContext(Dispatchers.IO) {
            val (b, t) = requireConfigured()
            val (code, body) = request("DELETE", "$b/v1/me", t)
            if (code !in 200..299) throw errorFrom(code, body)
        }
    }

    // ---- http helpers
    private suspend fun requireConfigured(): Pair<String, String> {
        val b = base()
        val t = token()
        if (b == null || t.isEmpty()) throw AiException("not_configured", "Cloud AI is not configured")
        checkUrl(b)
        return b to t
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, audio/wav")
        }

    private fun request(method: String, url: String, token: String): Pair<Int, ByteArray> {
        val c = open(method, url, token)
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    private fun multipart(url: String, token: String, fields: Map<String, String>, file: Pair<String, File>): Pair<Int, ByteArray> {
        val boundary = "----vm" + UUID.randomUUID()
        val c = open("POST", url, token)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.doOutput = true
        c.outputStream.use { writeMultipart(it, boundary, fields, file) }
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    private fun writeMultipart(out: java.io.OutputStream, boundary: String, fields: Map<String, String>, file: Pair<String, File>) {
        val nl = "\r\n"
        fields.forEach { (k, v) ->
            out.write("--$boundary$nl".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$k\"$nl$nl$v$nl".toByteArray())
        }
        out.write("--$boundary$nl".toByteArray())
        out.write("Content-Disposition: form-data; name=\"${file.first}\"; filename=\"${file.second.name}\"$nl".toByteArray())
        out.write("Content-Type: audio/wav$nl$nl".toByteArray())
        file.second.inputStream().use { it.copyTo(out) }
        out.write("$nl--$boundary--$nl".toByteArray())
    }

    private fun parseOrThrow(code: Int, body: ByteArray): JSONObject {
        if (code !in 200..299) throw errorFrom(code, body)
        return JSONObject(String(body))
    }

    private fun errorFrom(code: Int, body: ByteArray): AiException {
        val j = runCatching { JSONObject(String(body)).optJSONObject("detail") }.getOrNull()
        val c = j?.optString("code").orEmpty().ifEmpty { "http_$code" }
        val m = j?.optString("message").orEmpty().ifEmpty { "HTTP $code" }
        return AiException(c, m)
    }
}
