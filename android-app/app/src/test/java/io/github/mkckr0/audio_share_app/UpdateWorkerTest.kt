package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.model.Asset
import io.github.mkckr0.audio_share_app.worker.UpdateWorker
import org.junit.Assert.*
import org.junit.Test

class UpdateWorkerTest {

    private fun asset(name: String) = Asset(
        name = name,
        browserDownloadUrl = "https://example.com/$name",
        size = 1_000_000L,
        contentType = "application/vnd.android.package-archive"
    )

    @Test
    fun findApkAsset_findsMatchingApk() {
        val assets = listOf(
            asset("audio-share-app-0.3.4-release.apk"),
            asset("audio-share-server-0.3.4-linux-amd64.tar.gz")
        )
        val result = UpdateWorker.findApkAsset(assets)
        assertNotNull(result)
        assertEquals("audio-share-app-0.3.4-release.apk", result!!.name)
    }

    @Test
    fun findApkAsset_returnsNullWhenNoApk() {
        val assets = listOf(
            asset("audio-share-server-0.3.4-linux-amd64.tar.gz"),
            asset("audio-share-server-0.3.4-windows-amd64.zip")
        )
        assertNull(UpdateWorker.findApkAsset(assets))
    }

    @Test
    fun findApkAsset_returnsNullForEmptyList() {
        assertNull(UpdateWorker.findApkAsset(emptyList()))
    }

    @Test
    fun findApkAsset_findsDifferentVersions() {
        val assets = listOf(
            asset("audio-share-app-1.0.0-release.apk")
        )
        val result = UpdateWorker.findApkAsset(assets)
        assertNotNull(result)
        assertEquals("audio-share-app-1.0.0-release.apk", result!!.name)
    }

    @Test
    fun findApkAsset_matchesMultiDigitVersions() {
        val assets = listOf(
            asset("audio-share-app-12.34.56-release.apk")
        )
        val result = UpdateWorker.findApkAsset(assets)
        assertNotNull(result)
    }

    @Test
    fun findApkAsset_returnsNullForDebugBuild() {
        val assets = listOf(
            asset("audio-share-app-0.3.4-debug.apk")
        )
        assertNull(UpdateWorker.findApkAsset(assets))
    }

    @Test
    fun findApkAsset_returnsNullForWrongPrefix() {
        val assets = listOf(
            asset("other-app-0.3.4-release.apk")
        )
        assertNull(UpdateWorker.findApkAsset(assets))
    }

    @Test
    fun findApkAsset_picksFirstMatchWhenMultipleApks() {
        val assets = listOf(
            asset("audio-share-app-0.3.4-release.apk"),
            asset("audio-share-app-0.3.5-release.apk")
        )
        val result = UpdateWorker.findApkAsset(assets)
        assertNotNull(result)
        assertEquals("audio-share-app-0.3.4-release.apk", result!!.name)
    }
}
