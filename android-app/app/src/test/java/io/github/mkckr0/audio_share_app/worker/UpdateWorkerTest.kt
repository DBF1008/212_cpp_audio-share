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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the update-check pipeline that [UpdateWorker] now awaits.
 *
 * Before the fix, `UpdateWorker.doWork()` launched the GitHub request in a detached
 * `MainScope().launch` coroutine and returned `Result.success()` immediately, so a failed
 * request, a malformed response, or a stopped worker were all silently reported as success.
 *
 * The fix moves the work into [checkForUpdate], which is awaited inside the worker's
 * coroutine. These tests verify the behaviour the worker depends on:
 *  - failures (non-2xx responses, malformed JSON) propagate as exceptions instead of being
 *    swallowed — the worker maps them to `Result.retry()`;
 *  - successful responses produce the correct [UpdateCheckResult] so the worker can report
 *    `Result.success()` and act accordingly.
 *
 * The same [configureUpdateHttpClient] block used in production is applied here, so the
 * parsing and error-handling behaviour under test matches the real client. Driving the
 * full [androidx.work.CoroutineWorker] (which touches `Context`, `Toast` and notifications)
 * would require an Android runtime; this suite covers the network/decision logic that used
 * to leak out of WorkManager's control.
 */
class UpdateWorkerTest {

    private val baseUrl = "https://api.github.com"
    private val currentVersion = "v0.3.4"

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return HttpClient(engine) { configureUpdateHttpClient() }
    }

    @Test
    fun checkForUpdate_serverError_throwsInsteadOfSwallowing() {
        val client = clientReturning("rate limit exceeded", HttpStatusCode.InternalServerError)
        assertThrows(Exception::class.java) {
            runBlocking { checkForUpdate(client, baseUrl, currentVersion) }
        }
    }

    @Test
    fun checkForUpdate_malformedJson_throwsInsteadOfSwallowing() {
        val client = clientReturning("{ this is not valid json")
        assertThrows(Exception::class.java) {
            runBlocking { checkForUpdate(client, baseUrl, currentVersion) }
        }
    }

    @Test
    fun checkForUpdate_sameVersion_returnsNoUpdate() {
        val client = clientReturning(
            """
            {
              "name": "v0.3.4",
              "tag_name": "v0.3.4",
              "assets": []
            }
            """.trimIndent()
        )

        val result = runBlocking { checkForUpdate(client, baseUrl, currentVersion) }

        assertEquals(UpdateCheckResult.NoUpdate, result)
    }

    @Test
    fun checkForUpdate_newerVersionWithApk_returnsUpdateAvailable() {
        val client = clientReturning(
            """
            {
              "name": "v0.9.9",
              "tag_name": "v0.9.9",
              "assets": [
                {
                  "name": "audio-share-app-0.9.9-release.apk",
                  "browser_download_url": "https://example.com/audio-share-app-0.9.9-release.apk",
                  "size": 12345,
                  "content_type": "application/vnd.android.package-archive"
                }
              ]
            }
            """.trimIndent()
        )

        val result = runBlocking { checkForUpdate(client, baseUrl, currentVersion) }

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v0.9.9", result.release.tagName)
        assertEquals("audio-share-app-0.9.9-release.apk", result.apkAsset?.name)
    }

    @Test
    fun checkForUpdate_newerVersionWithoutMatchingApk_returnsUpdateAvailableWithNullAsset() {
        val client = clientReturning(
            """
            {
              "name": "v0.9.9",
              "tag_name": "v0.9.9",
              "assets": [
                {
                  "name": "audio-share-server-windows.zip",
                  "browser_download_url": "https://example.com/audio-share-server-windows.zip",
                  "size": 999,
                  "content_type": "application/zip"
                }
              ]
            }
            """.trimIndent()
        )

        val result = runBlocking { checkForUpdate(client, baseUrl, currentVersion) }

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        result as UpdateCheckResult.UpdateAvailable
        assertNull(result.apkAsset)
    }
}
