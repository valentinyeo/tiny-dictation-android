package com.tinydictation.android.correction

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports contact names from the device to be added to the skip list.
 * Contact names should not be "corrected" by the spell checker.
 */
class ContactImporter(private val context: Context) {

    companion object {
        private const val TAG = "ContactImporter"
    }

    /**
     * Check if we have permission to read contacts.
     */
    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Import all contact names as individual words.
     * Names are split and each word is added separately.
     *
     * @return Set of unique name words to add to skip list
     */
    suspend fun importContactNames(): Set<String> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) {
            DebugLog.w("$TAG: No contacts permission")
            return@withContext emptySet()
        }

        val names = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        try {
            val projection = arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            )

            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex) ?: continue

                    // Split compound names into individual words
                    displayName.split(Regex("[\\s,.-]+")).forEach { word ->
                        val cleaned = word.trim().lowercase()
                        // Only add words with 2+ characters
                        if (cleaned.length >= 2 && cleaned.all { it.isLetter() }) {
                            names.add(cleaned)
                        }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Imported ${names.size} unique words from contacts in ${elapsed}ms")

        } catch (e: Exception) {
            DebugLog.e("$TAG: Error importing contacts - ${e.message}")
        }

        names
    }

    /**
     * Get a preview count of how many contact words would be imported.
     * Useful for settings UI.
     */
    suspend fun getContactWordCount(): Int = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext 0

        try {
            val names = mutableSetOf<String>()

            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex) ?: continue
                    displayName.split(Regex("[\\s,.-]+")).forEach { word ->
                        val cleaned = word.trim().lowercase()
                        if (cleaned.length >= 2 && cleaned.all { it.isLetter() }) {
                            names.add(cleaned)
                        }
                    }
                }
            }

            names.size
        } catch (e: Exception) {
            DebugLog.e("$TAG: Error counting contacts - ${e.message}")
            0
        }
    }
}
