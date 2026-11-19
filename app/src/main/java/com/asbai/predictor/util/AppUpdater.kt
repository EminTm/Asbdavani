package com.asbai.predictor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.asbai.predictor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String
)

object AppUpdater {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun getCurrentVersionCode(context: Context): Int {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        }.getOrDefault(1)
    }

    fun getCurrentVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
    }

    fun getConfiguredRepo(context: Context): String {
        return runCatching { context.getString(R.string.github_repo) }
            .getOrDefault("")
            .trim()
    }

    suspend fun checkForUpdate(context: Context): Result<AppUpdateInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val repo = getConfiguredRepo(context)
                if (repo.isBlank() || "/" !in repo) {
                    error("آدرس مخزن گیت‌هاب داخل نسخه فعلی ثبت نشده است.")
                }

                val url = "https://api.github.com/repos/$repo/releases/latest"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "AsbDavaniAI")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 404) return@runCatching null
                    if (!response.isSuccessful) {
                        error("خطا در بررسی آپدیت از گیت‌هاب (کد ${response.code})")
                    }

                    val body = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    val tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val remoteCode = Regex("(\\d+)$").find(tagName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@runCatching null

                    val currentCode = getCurrentVersionCode(context)
                    if (remoteCode <= currentCode) {
                        return@runCatching null
                    }

                    val assets = root["assets"]?.jsonArray ?: return@runCatching null
                    val apkAsset = assets.firstOrNull { el ->
                        val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        name.endsWith(".apk", ignoreCase = true)
                    } ?: assets.firstOrNull()

                    val downloadUrl = apkAsset?.jsonObject
                        ?.get("browser_download_url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()

                    if (downloadUrl.isBlank()) return@runCatching null

                    AppUpdateInfo(
                        versionCode = remoteCode,
                        versionName = tagName.removePrefix("v"),
                        downloadUrl = downloadUrl
                    )
                }
            }
        }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return@runCatching false
            }

            val apksDir = File(context.cacheDir, "apks").apply { mkdirs() }
            val outFile = File(apksDir, "update.apk")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "AsbDavaniAI")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("دانلود آپدیت ناموفق بود (کد ${response.code})")
                }
                val body = response.body ?: error("فایل آپدیت خالی بود.")
                val totalBytes = body.contentLength()
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }
    }
}
