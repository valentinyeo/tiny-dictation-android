package com.tinydictation.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateVerifierTest {
    private val sha = "a".repeat(64)
    private val installed = UpdatePackage("com.tinydictation.android", 368, setOf("cert-a"))
    private val apk = installed.copy(versionCode = 369)
    private val release = UpdateRelease(
        installed.packageName, 369, "1.43.0", "arm64-v8a", "https://example.com/update.apk",
        sha, 1024, "The app can now update itself"
    )
    private val abis = listOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun acceptsVerifiedNewerApk() {
        verify()
    }

    @Test
    fun regression2026_10_19_lowerOrEqualVersionCodeNeverInstalls() {
        for (version in listOf(367L, 368L)) {
            rejects("Manifest version code is not newer than the installed app") {
                verify(release.copy(versionCode = version), apk.copy(versionCode = version))
            }
        }
    }

    @Test
    fun rejectsApkWithLowerVersionCodeThanInstalled() {
        rejects("APK version code is not newer than the installed app") {
            verify(candidate = apk.copy(versionCode = 367))
        }
    }

    @Test
    fun rejectsApkWithEqualVersionCodeToInstalled() {
        rejects("APK version code is not newer than the installed app") {
            verify(candidate = apk.copy(versionCode = 368))
        }
    }

    @Test
    fun rejectsApkVersionDifferentFromManifest() {
        rejects("APK version code does not match the manifest") {
            verify(candidate = apk.copy(versionCode = release.versionCode + 1))
        }
    }

    @Test
    fun rejectsWrongManifestPackage() {
        rejects("Manifest package name does not match the installed app") {
            verify(release.copy(packageName = "another.app"))
        }
    }

    @Test
    fun rejectsWrongApkPackage() {
        rejects("APK package name does not match the installed app") {
            verify(candidate = apk.copy(packageName = "another.app"))
        }
    }

    @Test
    fun rejectsWrongSigningCertificate() {
        rejects("APK signing certificates do not match the installed app") {
            verify(candidate = apk.copy(certificateDigests = setOf("cert-b")))
        }
    }

    @Test
    fun rejectsExtraSigningCertificate() {
        rejects("APK signing certificates do not match the installed app") {
            verify(candidate = apk.copy(certificateDigests = setOf("cert-a", "cert-b")))
        }
    }

    @Test
    fun rejectsEmptySigningCertificates() {
        rejects("APK signing certificates do not match the installed app") {
            UpdateVerifier.verify(
                release, installed.copy(certificateDigests = emptySet()),
                apk.copy(certificateDigests = emptySet()), sha, 1024, abis
            )
        }
    }

    @Test
    fun rejectsSha256Mismatch() {
        rejects("APK SHA-256 does not match the manifest") {
            verify(hash = "b".repeat(64))
        }
    }

    @Test
    fun rejectsSizeMismatch() {
        for (size in listOf(0L, 1023L, 1025L, UpdateRelease.MAX_APK_BYTES + 1)) {
            rejects("APK size does not match the manifest or exceeds the size limit") {
                verify(size = size)
            }
        }
    }

    @Test
    fun rejectsUnsupportedAbi() {
        rejects("Manifest ABI is not supported by this device") {
            verify(release.copy(abi = "x86_64"))
        }
    }

    @Test
    fun validatesHttpsEvenForDirectlyConstructedRelease() {
        rejects("Update URL must use HTTPS with a valid host") {
            verify(release.copy(apkUrl = "http://example.com/update.apk"))
        }
    }

    private fun verify(
        manifest: UpdateRelease = release,
        candidate: UpdatePackage = apk,
        hash: String = sha,
        size: Long = 1024,
    ) = UpdateVerifier.verify(manifest, installed, candidate, hash, size, abis)

    private fun rejects(message: String, block: () -> Unit) {
        val exception = assertThrows(IllegalArgumentException::class.java, block)
        assertEquals(message, exception.message)
    }
}
