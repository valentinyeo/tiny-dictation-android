package com.tinydictation.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.tinydictation.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val operation = Mutex()
    private val _state = MutableStateFlow<UpdateState>(
        preferences.getString(KEY_INSTALL_RESULT, null)?.let { message ->
            if (preferences.getBoolean(KEY_INSTALL_SUCCESS, false)) UpdateState.Installed(message)
            else UpdateState.Failed(message)
        } ?: UpdateState.Idle
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val lastInstallResult: String? get() = preferences.getString(KEY_INSTALL_RESULT, null)

    fun check(force: Boolean = false) {
        scope.launch {
            try {
                checkForUpdate(force)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("TinyDictationUpdate", "Update check failed", exception)
            }
        }
    }

    suspend fun checkForUpdate(force: Boolean = false) {
        if (!operation.tryLock()) return
        try {
            if (_state.value is UpdateState.Installing ||
                _state.value is UpdateState.PermissionRequired
            ) return
            val last = preferences.getLong(KEY_LAST_CHECK, 0)
            if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
            _state.value = UpdateState.Checking
            val release = fetchManifest()
            val installed = installedPackage()
            require(release.packageName == installed.packageName) {
                "Manifest package name does not match the installed app"
            }
            require(release.abi in Build.SUPPORTED_ABIS) {
                "Manifest ABI is not supported by this device"
            }
            _state.value = if (release.versionCode > installed.versionCode) {
                val lastNotified = preferences.getLong(KEY_LAST_NOTIFIED, 0)
                if (release.versionCode > lastNotified && UpdateNotifier.show(context, release)) {
                    preferences.edit().putLong(KEY_LAST_NOTIFIED, release.versionCode).apply()
                }
                UpdateState.Available(release)
            } else {
                UpdateState.Current(release)
            }
            preferences.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _state.value = UpdateState.Failed(exception.userMessage())
            throw exception
        } finally {
            operation.unlock()
        }
    }

    fun install(release: UpdateRelease) {
        if (!operation.tryLock()) return
        if (_state.value is UpdateState.Installing) {
            operation.unlock()
            return
        }
        scope.launch {
            var apk: File? = null
            try {
                UpdateVerifier.verifyRelease(release, installedPackage(), Build.SUPPORTED_ABIS.toList())
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _state.value = UpdateState.PermissionRequired(release)
                    return@launch
                }
                _state.value = UpdateState.Downloading(release, 0f)
                preferences.edit().remove(KEY_INSTALL_RESULT).apply()
                apk = download(release)
                verifyApk(apk, release)
                _state.value = UpdateState.Installing
                stageInstall(apk)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.value = UpdateState.Failed(exception.userMessage(), release)
            } finally {
                apk?.delete()
                operation.unlock()
            }
        }
    }

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri(),
    )

    fun resumeAfterPermission(release: UpdateRelease) {
        if (context.packageManager.canRequestPackageInstalls()) install(release)
        else _state.value = UpdateState.Failed("Allow app installs to download and update", release)
    }

    fun reportInstallResult(success: Boolean, message: String) {
        preferences.edit().putString(KEY_INSTALL_RESULT, message)
            .putBoolean(KEY_INSTALL_SUCCESS, success).commit()
        _state.value = if (success) UpdateState.Installed(message) else UpdateState.Failed(message)
    }

    private fun fetchManifest(): UpdateRelease {
        val connection = openHttps(BuildConfig.UPDATE_MANIFEST_URL)
        return connection.useConnection { input ->
            val raw = input.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= MAX_MANIFEST_BYTES) {
                        "Update manifest is too large"
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            UpdateRelease.parse(raw)
        }
    }

    private fun download(release: UpdateRelease): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "tinydictation.apk.partial")
        val target = File(directory, "tinydictation.apk")
        partial.delete()
        target.delete()
        try {
            val connection = openHttps(release.apkUrl)
            connection.useConnection { input ->
                val declaredLength = connection.contentLengthLong
                require(declaredLength < 0 || declaredLength == release.sizeBytes) {
                    "APK size from server does not match the manifest"
                }
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= release.sizeBytes &&
                            copied <= UpdateRelease.MAX_APK_BYTES
                        ) { "APK size exceeds the manifest or size limit" }
                        output.write(buffer, 0, count)
                        _state.value = UpdateState.Downloading(
                            release, (copied.toFloat() / release.sizeBytes).coerceIn(0f, 1f)
                        )
                    }
                }
            }
            require(partial.length() == release.sizeBytes) { "APK size check failed: incomplete download" }
            require(partial.renameTo(target)) { "Could not finalize the APK download" }
            return target
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, release: UpdateRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
            ?: error("APK package check failed: downloaded file is not a valid APK")
        UpdateVerifier.verify(
            release, installedPackage(), packageValues(archive), digest.digest().hex(),
            apk.length(), Build.SUPPORTED_ABIS.toList()
        )
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): UpdatePackage = packageValues(
        context.packageManager.getPackageInfo(context.packageName, signingFlags())
    )

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun packageValues(info: PackageInfo): UpdatePackage {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return UpdatePackage(
            info.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong(),
            signatures.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
            }.toSet(),
        )
    }

    private fun stageInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("tinydictation.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (exception: Exception) {
            installer.abandonSession(sessionId)
            throw exception
        }
    }

    private fun openHttps(rawUrl: String): HttpURLConnection {
        var url = URL(rawUrl)
        repeat(6) {
            UpdateRelease.requireHttps(url.toString())
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "tinydictation/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Update redirect is missing its URL")
                    url = URL(url, location)
                    connection.disconnect()
                } else {
                    require(status in 200..299) { "Update server returned HTTP $status" }
                    return connection
                }
            } catch (exception: Exception) {
                connection.disconnect()
                throw exception
            }
        }
        error("Too many update redirects")
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (java.io.InputStream) -> T): T =
        try {
            inputStream.use(block)
        } finally {
            disconnect()
        }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun Exception.userMessage(): String {
        Log.e("TinyDictationUpdate", "Update failed", this)
        return message?.takeIf(String::isNotBlank) ?: "Update failed: ${javaClass.simpleName}"
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_LAST_NOTIFIED = "last_notified_version"
        const val KEY_INSTALL_RESULT = "install_result"
        const val KEY_INSTALL_SUCCESS = "install_success"
        const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        const val NETWORK_TIMEOUT_MS = 30_000
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
