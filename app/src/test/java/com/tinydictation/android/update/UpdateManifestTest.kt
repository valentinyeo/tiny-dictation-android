package com.tinydictation.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val raw = """
        {
          "schemaVersion": 1,
          "packageName": "com.tinydictation.android",
          "versionCode": 369,
          "versionName": "1.43.0",
          "abi": "arm64-v8a",
          "apkUrl": "https://example.com/tinydictation.apk",
          "sha256": "${"a".repeat(64)}",
          "sizeBytes": 1024,
          "notes": "The app can now update itself",
          "history": [{"versionName":"1.42.0","date":"2026-10-19","notes":"Earlier release"}]
        }
    """.trimIndent()

    @Test
    fun parsesValidManifestAndHistory() {
        val release = UpdateRelease.parse(raw)
        assertEquals("com.tinydictation.android", release.packageName)
        assertEquals(369L, release.versionCode)
        assertEquals("1.43.0", release.versionName)
        assertEquals("arm64-v8a", release.abi)
        assertEquals("https://example.com/tinydictation.apk", release.apkUrl)
        assertEquals("a".repeat(64), release.sha256)
        assertEquals(1024L, release.sizeBytes)
        assertEquals("The app can now update itself", release.notes)
        assertEquals(listOf(ReleaseNote("1.42.0", "2026-10-19", "Earlier release")), release.history)
    }

    @Test
    fun parsesPublishedManifestFixture() {
        val fixture = requireNotNull(javaClass.getResource("/update/published-manifest.json"))
            .readText()
        val release = UpdateRelease.parse(fixture)
        assertEquals(900L, release.versionCode)
        assertEquals("2030-01-02", release.date)
        assertEquals("https://updates.example.invalid/android-latest/TinyDictation-1.2.3-arm64.apk",
            release.apkUrl)
        assertEquals("0".repeat(64), release.sha256)
        assertEquals(listOf(ReleaseNote("1.2.2", "2029-12-31", "Synthetic previous release")),
            release.history)
    }

    @Test
    fun parsesHistoryWithoutDate() {
        val release = UpdateRelease.parse(raw.replace("\"date\":\"2026-10-19\",", ""))
        assertEquals("", release.date)
        assertEquals(listOf(ReleaseNote("1.42.0", "", "Earlier release")), release.history)
    }

    @Test
    fun skipsMalformedHistoryEntries() {
        val malformed = """null, 1, "bad", [], {}, {"versionName":"bad"},
            {"versionName":42,"notes":"bad"}, {"versionName":"bad","notes":null},
            {"versionName":"bad","notes":"bad","date":{}},
            {"versionName":"bad","notes":"bad","date":42},"""
        val release = UpdateRelease.parse(raw.replace("\"history\": [", "\"history\": [$malformed"))
        assertEquals(listOf(ReleaseNote("1.42.0", "2026-10-19", "Earlier release")), release.history)
    }

    @Test
    fun parsesNullDatesAndMissingOrMalformedHistory() {
        val release = UpdateRelease.parse(raw.replace("\"date\":\"2026-10-19\"", "\"date\":null"))
        assertEquals("", release.history.single().date)
        for (history in listOf("null", "{}", "42", "[]")) {
            val manifest = raw.replace(Regex("\"history\": \\[.*]"), "\"history\": $history")
            assertTrue(UpdateRelease.parse(manifest).history.isEmpty())
        }
        val withoutHistory = raw.substringBefore(",\n  \"history\"") + "\n}"
        assertTrue(UpdateRelease.parse(withoutHistory).history.isEmpty())
        assertEquals("", UpdateRelease.parse(raw.replace("\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"date\": null,")).date)
    }

    @Test
    fun rejectsHttpApkUrl() {
        rejects(raw.replace("https://", "http://"), "HTTPS")
    }

    @Test
    fun rejectsHttpManifestUrl() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.requireHttps("http://example.com/manifest.json")
        }
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun rejectsHostlessHttpsUrl() {
        rejects(raw.replace("https://example.com/", "https:///"), "HTTPS")
    }

    @Test
    fun rejectsBadSha256Format() {
        for (hash in listOf("a".repeat(63), "g".repeat(64), "")) {
            rejects(raw.replace("a".repeat(64), hash), "SHA-256")
        }
    }

    @Test
    fun normalizesUppercaseSha256() {
        assertEquals("a".repeat(64), UpdateRelease.parse(raw.replace("a".repeat(64),
            "A".repeat(64))).sha256)
    }

    @Test
    fun rejectsInvalidSizes() {
        for (size in listOf(0L, -1L, UpdateRelease.MAX_APK_BYTES + 1)) {
            rejects(raw.replace("1024", size.toString()), "size")
        }
    }

    @Test
    fun rejectsUnsupportedSchema() {
        rejects(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), "schema")
    }

    @Test
    fun rejectsFractionalVersionCode() {
        rejects(raw.replace("369", "369.5"), "versionCode")
    }

    @Test
    fun rejectsMissingPackageName() {
        rejects(raw.replace("\"packageName\": \"com.tinydictation.android\",", ""), "packageName")
    }

    private fun rejects(manifest: String, message: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.parse(manifest)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
    }
}
