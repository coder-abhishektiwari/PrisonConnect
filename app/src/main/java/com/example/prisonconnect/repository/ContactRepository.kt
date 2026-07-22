package com.example.prisonconnect.repository

import com.example.prisonconnect.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * Repository handling Contact-related data operations.
 */
interface ContactRepository {
    /**
     * Fetches all contacts associated with a specific inmate.
     */
    suspend fun getContactsForInmate(inmateId: String): List<Contact>

    /**
     * Provides a stream of contacts for real-time updates.
     */
    fun observeContacts(inmateId: String): Flow<List<Contact>>
}

class SupabaseContactRepository : ContactRepository {
    override suspend fun getContactsForInmate(inmateId: String): List<Contact> {
        return DbService.queryDocuments(
            table = "contacts",
            field = "associated_inmate_id",
            value = inmateId
        )
    }

    override fun observeContacts(inmateId: String): Flow<List<Contact>> {
        return DbService.queryFlow(
            table = "contacts",
            field = "associated_inmate_id",
            value = inmateId
        )
    }
}