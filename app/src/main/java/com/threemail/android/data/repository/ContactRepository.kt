package com.threemail.android.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.threemail.android.domain.model.Contact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contact autocomplete for the composer. Reads from the system
 * ContactsContract; never caches the permission state so a revocation in OS
 * settings is picked up on the next call.
 */
@Singleton
class ContactRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Reads the current OS-level permission state. Callers should not cache. */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns up to [limit] contacts whose email or display name matches
     * [query]. Returns an empty list when the permission is missing or
     * [query] is shorter than two characters.
     */
    suspend fun search(query: String, limit: Int = 15): List<Contact> {
        val trimmed = query.trim()
        if (trimmed.length < 2 || !hasPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            val selection =
                "${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$trimmed%", "%$trimmed%")
            val sortOrder =
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC LIMIT $limit"
            val grouped = LinkedHashMap<Long, Contact>()
            try {
                resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    sortOrder
                )?.use { cursor ->
                    val cidIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                    val addrIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    while (cursor.moveToNext()) {
                        if (cidIdx < 0 || addrIdx < 0) continue
                        val cid = cursor.getLong(cidIdx)
                        val name = cursor.getString(nameIdx).orEmpty()
                        val email = cursor.getString(addrIdx)?.takeIf { it.isNotBlank() }
                            ?: continue
                        grouped[cid] = grouped[cid]?.let { existing ->
                            if (email in existing.emails) existing
                            else existing.copy(emails = existing.emails + email)
                        } ?: Contact(id = cid, displayName = name, emails = listOf(email))
                    }
                }
            } catch (_: SecurityException) {
                return@withContext emptyList()
            }
            grouped.values.toList()
        }
    }
}
