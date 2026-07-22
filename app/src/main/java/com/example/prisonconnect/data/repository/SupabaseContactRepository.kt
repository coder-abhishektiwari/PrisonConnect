package com.example.prisonconnect.data.repository

import com.example.prisonconnect.domain.model.Contact
import com.example.prisonconnect.domain.repository.ContactRepository
import com.example.prisonconnect.data.remote.DbService
import kotlinx.coroutines.flow.Flow

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
