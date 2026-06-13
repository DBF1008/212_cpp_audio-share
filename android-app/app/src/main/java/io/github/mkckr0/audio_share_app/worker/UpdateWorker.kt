/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mkckr0.audio_share_app.BuildConfig
import io.github.mkckr0.audio_share_app.ui.MainActivity
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.Asset
import io.github.mkckr0.audio_share_app.model.Channel
import io.github.mkckr0.audio_share_app.model.LatestRelease
import io.github.mkckr0.audio_share_app.model.Notification
import io.github.mkckr0.audio_share_app.model.Util
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

internal const val KEY_SUPPRESS_MESSAGE = "SUPPRESS_MESSAGE"
internal const val UPDATE_GITHUB_API_BASE_URL = "https://api.github.com"
internal val updateApkAssetRegex = Regex("audio-share-app-[0-9.]*-release.apk")

/**
 * Outcome of an update check. Kept free of any Android dependency so the network and
 * version-comparison logic can be exercised by plain JVM unit tests.
 */
internal sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult
    data class UpdateAvailable(val release: LatestRelease, val apkAsset: Asset?) : UpdateCheckResult
}

/**
 * Shared [HttpClient] configuration so production code and tests parse responses and treat
 * HTTP errors identically (`expectSuccess = true` turns non-2xx responses into exceptions).
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun HttpClientConfig<*>.configureUpdateHttpClient() {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        })
    }
}

/**
 * Fetches the latest release from GitHub and decides what the worker should do.
 *
 * This intentionally *throws* on any failure (network error, non-2xx response, malformed
 * JSON, malformed version string). Callers are expected to surface those failures rather
 * than swallow them, which is exactly what [UpdateWorker] now does.
 */
internal suspend fun checkForUpdate(
    httpClient: HttpClient,
    baseUrl: String,
    currentVersion: String,
): UpdateCheckResult {
    val latestRelease: LatestRelease =
        httpClient.get("$baseUrl/repos/mkckr0/audio-share/releases/latest").body()

    if (!Util.isNewerVersion(latestRelease.tagName, currentVersion)) {
        return UpdateCheckResult.NoUpdate
    }

    val apkAsset = latestRelease.assets.find { it.name.matches(updateApkAssetRegex) }
    return UpdateCheckResult.UpdateAvailable(latestRelease, apkAsset)
}

/**
 * Checks GitHub for a newer release.
 *
 * Implemented as a [CoroutineWorker] so the entire chain — HTTP request, JSON parsing,
 * version comparison, notification and Toast — runs inside the coroutine WorkManager
 * manages. The worker only completes once that work finishes, so the reported [Result]
 * reflects what actually happened: success when the check completes, [Result.retry] when
 * it fails, and a propagated cancellation when WorkManager stops the worker.
 */
class UpdateWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val tag = javaClass.simpleName

    private var suppressMessage = false

    override suspend fun doWork(): Result {
        Log.d(tag, "UpdateWorker doWork")
        suppressMessage = inputData.getBoolean(KEY_SUPPRESS_MESSAGE, false)

        return try {
            val checkResult = withContext(Dispatchers.IO) {
                createHttpClient().use { client ->
                    checkForUpdate(
                        client,
                        UPDATE_GITHUB_API_BASE_URL,
                        "v${BuildConfig.VERSION_NAME}",
                    )
                }
            }

            // Toast must be posted from a thread with a Looper, so switch to the main
            // dispatcher. This still runs inside the worker's coroutine, so the worker
            // does not finish before the user feedback has been shown.
            withContext(Dispatchers.Main) {
                applyResult(checkResult)
            }

            Result.success()
        } catch (e: CancellationException) {
            // The worker was stopped/cancelled by WorkManager. Let it propagate so the run
            // is recorded as stopped instead of being silently reported as success.
            throw e
        } catch (e: Exception) {
            Log.e(tag, "check for update failed", e)
            Result.retry()
        }
    }

    private fun createHttpClient(): HttpClient = HttpClient {
        configureUpdateHttpClient()
    }

    private fun applyResult(result: UpdateCheckResult) {
        when (result) {
            UpdateCheckResult.NoUpdate ->
                showMessage(applicationContext.getString(R.string.label_no_update))

            is UpdateCheckResult.UpdateAvailable -> {
                val apkAsset = result.apkAsset
                if (apkAsset == null) {
                    showMessage(applicationContext.getString(R.string.label_has_an_update_1))
                } else {
                    notifyUpdateAvailable(result.release, apkAsset)
                }
            }
        }
    }

    private fun notifyUpdateAvailable(latestRelease: LatestRelease, apkAsset: Asset) {
        with(NotificationManagerCompat.from(applicationContext)) {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showMessage("No permission to post notification")
                return
            }

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            intent.putExtra("action", "update")
            intent.putExtra("apkAsset", apkAsset)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(
                applicationContext,
                Channel.UPDATE.id
            )
                .setSmallIcon(R.drawable.baseline_update)
                .setContentTitle(applicationContext.getString(R.string.label_has_an_update_2).format(latestRelease.tagName))
                .setContentText(applicationContext.getString(R.string.label_tap_notification_1))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notify(Notification.UPDATE.id, notification)
            showMessage(applicationContext.getString(R.string.label_tap_notification_2))
        }
    }

    private fun showMessage(message: String) {
        if (!suppressMessage) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
