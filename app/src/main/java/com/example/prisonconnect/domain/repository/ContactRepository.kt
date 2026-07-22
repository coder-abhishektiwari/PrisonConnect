package com.example.prisonconnect.domain.repository

import com.example.prisonconnect.domain.model.Contact
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
