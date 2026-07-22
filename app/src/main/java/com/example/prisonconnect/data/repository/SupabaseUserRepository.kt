package com.example.prisonconnect.data.repository

import com.example.prisonconnect.domain.model.User
import com.example.prisonconnect.domain.repository.UserRepository
import com.example.prisonconnect.data.remote.DbService
import kotlinx.coroutines.flow.Flow

class SupabaseUserRepository : UserRepository {
    override suspend fun getUserByPrisonerId(prisonerId: String): User? {
        val users = DbService.queryDocuments<User>(
            table = "users",
            field = "prisoner_id",
            value = prisonerId
        )
        return users.firstOrNull()
    }

    override suspend fun getUserById(userId: String): User? {
        return DbService.getDocument<User>(table = "users", id = userId)
    }

    override suspend fun updateBalance(userId: String, balanceSeconds: Long): Result<Unit> {
        return DbService.updateUserBalance(userId, balanceSeconds)
    }

    override fun observeUser(userId: String): Flow<User?> {
        return DbService.documentFlow<User>(table = "users", id = userId)
    }
}
