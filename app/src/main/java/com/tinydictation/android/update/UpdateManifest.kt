package com.tinydictation.android.update

import com.google.gson.JsonParser
import java.net.URI

data class ReleaseNote(val versionName: String, val date: String, val notes: String)

data class UpdateRelease(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val abi: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val history: List<ReleaseNote> = emptyList(),
    val date: String = "",
) {
    fun validate() {
        require(packageName.isNotBlank()) { "Invalid manifest package name" }
        require(versionCode > 0) { "Invalid manifest version code" }
        require(versionName.isNotBlank()) { "Invalid manifest version name" }
        require(abi.isNotBlank()) { "Invalid manifest ABI" }
        requireHttps(apkUrl)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid APK SHA-256 format" }
        require(sizeBytes in 1..MAX_APK_BYTES) { "Invalid APK size" }
    }

    companion object {
        const val MAX_APK_BYTES = 200L * 1024 * 1024

        fun requireHttps(rawUrl: String) {
            val uri = URI(rawUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null
            ) { "Update URL must use HTTPS with a valid host" }
        }

        fun parse(raw: String): UpdateRelease {
            val json = JsonParser.parseString(raw).asJsonObject
            fun text(name: String): String {
                val value = json.get(name)
                require(value != null && value.isJsonPrimitive &&
                    value.asJsonPrimitive.isString
                ) { "Invalid manifest $name" }
                return value.asString
            }
            fun number(name: String): Long {
                val value = json.get(name)
                require(value != null && value.isJsonPrimitive &&
                    value.asJsonPrimitive.isNumber
                ) { "Invalid manifest $name" }
                return requireNotNull(value.asString.toLongOrNull()) { "Invalid manifest $name" }
            }
            require(number("schemaVersion") == 1L) { "Unsupported update manifest schema" }
            return UpdateRelease(
                packageName = text("packageName"),
                versionCode = number("versionCode"),
                versionName = text("versionName"),
                abi = text("abi"),
                apkUrl = text("apkUrl"),
                sha256 = text("sha256").lowercase(),
                sizeBytes = number("sizeBytes"),
                notes = text("notes"),
                date = if (json.has("date") && !json.get("date").isJsonNull) {
                    text("date")
                } else "",
                history = json.get("history")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { entry ->
                        if (!entry.isJsonObject) return@mapNotNull null
                        val note = entry.asJsonObject
                        if (listOf("versionName", "notes").any { name ->
                            val value = note.get(name)
                            value == null || !value.isJsonPrimitive ||
                                !value.asJsonPrimitive.isString
                        }) return@mapNotNull null
                        val date = note.get("date")?.takeUnless { it.isJsonNull }
                        if (date != null && (!date.isJsonPrimitive ||
                            !date.asJsonPrimitive.isString)
                        ) return@mapNotNull null
                        ReleaseNote(
                            note.get("versionName").asString,
                            date?.asString.orEmpty(),
                            note.get("notes").asString,
                        )
                    }.orEmpty(),
            ).also { it.validate() }
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Current(val release: UpdateRelease) : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class PermissionRequired(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Installed(val message: String) : UpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : UpdateState
}
