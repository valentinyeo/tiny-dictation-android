package com.tinydictation.android.update

data class UpdatePackage(
    val packageName: String,
    val versionCode: Long,
    val certificateDigests: Set<String>,
)

object UpdateVerifier {
    fun verifyRelease(
        release: UpdateRelease,
        installed: UpdatePackage,
        supportedAbis: List<String>,
    ) {
        release.validate()
        require(release.packageName == installed.packageName) {
            "Manifest package name does not match the installed app"
        }
        require(release.versionCode > installed.versionCode) {
            "Manifest version code is not newer than the installed app"
        }
        require(release.abi in supportedAbis) { "Manifest ABI is not supported by this device" }
    }

    fun verify(
        release: UpdateRelease,
        installed: UpdatePackage,
        apk: UpdatePackage,
        actualSha256: String,
        actualSizeBytes: Long,
        supportedAbis: List<String>,
    ) {
        verifyRelease(release, installed, supportedAbis)
        require(actualSizeBytes in 1..UpdateRelease.MAX_APK_BYTES &&
            actualSizeBytes == release.sizeBytes
        ) { "APK size does not match the manifest or exceeds the size limit" }
        require(actualSha256.equals(release.sha256, ignoreCase = true)) {
            "APK SHA-256 does not match the manifest"
        }
        require(apk.packageName == installed.packageName) {
            "APK package name does not match the installed app"
        }
        require(apk.versionCode > installed.versionCode) {
            "APK version code is not newer than the installed app"
        }
        require(apk.versionCode == release.versionCode) {
            "APK version code does not match the manifest"
        }
        require(installed.certificateDigests.isNotEmpty() &&
            installed.certificateDigests == apk.certificateDigests
        ) { "APK signing certificates do not match the installed app" }
    }
}
