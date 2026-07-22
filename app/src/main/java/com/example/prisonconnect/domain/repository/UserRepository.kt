package com.example.prisonconnect.domain.repository

import com.example.prisonconnect.domain.model.User
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
