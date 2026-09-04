package io.github.hatake716.heiseicamera

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A capture date with exactly the precision returned by Google's official metadata API. */
data class CaptureDate(val year: Int, val month: Int? = null) {
    init {
        require(year in 1..9999)
        require(month == null || month in 1..12)
    }

    val display: String
        get() = if (month == null) "%04d".format(Locale.ROOT, year)
        else "%04d.%02d".format(Locale.ROOT, year, month)

    val isHeisei: Boolean
        get() = year in 1990..2018 ||
            (year == 1989 && month != null && month >= 2) ||
            (year == 2019 && month != null && month <= 4)

    val eraLabel: String
        get() = when {
            isHeisei && year == 1989 -> "平成元年"
            isHeisei -> "平成${year - 1988}年"
            year == 1989 && (month == null || month == 1) -> "平成か不明"
            year == 2019 && month == null -> "平成か不明"
            year < 1989 -> "平成以前"
            else -> "平成以降"
        }

    companion object {
        private val format = Regex("([0-9]{4})(?:-([0-9]{2}))?")

        fun parse(raw: String?): CaptureDate? {
            val match = raw?.let(format::matchEntire) ?: return null
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (year !in 1..9999 || (month != null && month !in 1..12)) return null
            return CaptureDate(year, month)
        }
    }
}

sealed interface MetadataResult {
    data class Success(
        val date: CaptureDate?,
        val copyright: String,
        val lat: Double?,
        val lng: Double?,
    ) : MetadataResult

    data class Failure(val message: String) : MetadataResult
}

class PanoramaMetadataClient(context: Context, private val apiKey: String) {
    private val applicationContext = context.applicationContext

    suspend fun fetch(panoId: String): MetadataResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext MetadataResult.Failure("撮影年月の確認には API キーの設定が必要です。")
        if (!PanoramaLinks.validPanoId(panoId)) {
            return@withContext MetadataResult.Failure("このストリートビューの情報を確認できません。")
        }
        var connection: HttpURLConnection? = null
        try {
            coroutineContext.ensureActive()
            val fingerprint = signingFingerprint()
                ?: return@withContext MetadataResult.Failure("撮影年月を確認するためのアプリ認証情報を取得できません。")
            val url = URL(
                "https://maps.googleapis.com/maps/api/streetview/metadata" +
                    "?pano=${encode(panoId)}&key=${encode(apiKey)}",
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Android-Package", applicationContext.packageName)
                setRequestProperty("X-Android-Cert", fingerprint)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext MetadataResult.Failure("撮影年月を取得できませんでした。通信状態と API 設定をご確認ください。")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2_048)
                val result = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 65_536) {
                        return@withContext MetadataResult.Failure("撮影情報の応答を読み取れませんでした。")
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            coroutineContext.ensureActive()
            parseMetadata(body, panoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Exception messages can contain API keys or panorama coordinates; never expose them.
            coroutineContext.ensureActive()
            MetadataResult.Failure("撮影年月を取得できませんでした。通信状態をご確認ください。")
        } finally {
            connection?.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFingerprint(): String? {
        val packageInfo = applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-1").digest(signer.toByteArray())
            .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal fun parseMetadata(body: String, requestedPanoId: String): MetadataResult {
    val response = JSONObject(body)
    if (response.optString("status") != "OK") {
        val message = when (response.optString("status")) {
            "ZERO_RESULTS", "NOT_FOUND" -> "この景色の撮影情報が見つかりませんでした。"
            "REQUEST_DENIED" -> "撮影年月の API 認証を確認してください。"
            "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" -> "撮影年月の取得上限に達しました。しばらくしてからお試しください。"
            else -> "撮影年月を取得できませんでした。"
        }
        return MetadataResult.Failure(message)
    }
    // Google can replace deleted IDs. Never label the displayed panorama with a replacement's date.
    if (response.optString("pano_id") != requestedPanoId) {
        return MetadataResult.Failure("表示中の景色と撮影情報が一致しないため、撮影年月を表示できません。")
    }
    val location = response.optJSONObject("location")
    val lat = location?.optDouble("lat")?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val lng = location?.optDouble("lng")?.takeIf { it.isFinite() && it in -180.0..180.0 }
    return MetadataResult.Success(
        date = CaptureDate.parse(response.optString("date")),
        copyright = response.optString("copyright"),
        lat = lat,
        lng = lng,
    )
}
