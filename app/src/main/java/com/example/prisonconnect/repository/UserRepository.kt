package com.example.prisonconnect.repository

import com.example.prisonconnect.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository handling User-related data operations.
 */
interface UserRepository {
    /**
     * Fetches a user by their prisoner ID.
     */
    suspend fun getUserByPrisonerId(prisonerId: String): User?

    /**
     * Fetches a user by their internal UUID.
     */
    suspend fun getUserById(userId: String): User?

    /**
     * Updates the user's balance in the remote database.
     */
    suspend fun updateBalance(userId: String, balanceSeconds: Long): Result<Unit>

    /**
     * Provides a stream of user data for real-time balance updates.
     */
    fun observeUser(userId: String): Flow<User?>
}

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