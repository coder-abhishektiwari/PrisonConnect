package com.example.prisonconnect.domain.repository

import com.example.prisonconnect.domain.model.CallRoom
import kotlinx.coroutines.flow.Flow

/**
 * Repository handling Call Room operations and lifecycle.
 */
interface CallRepository {
    /**
     * Creates a new call room record.
     */
    suspend fun createRoom(roomData: Map<String, Any>): Result<Unit>

    /**
     * Updates the status of an existing call room.
     */
    suspend fun updateRoomStatus(roomId: String, status: String): Result<Unit>

    /**
     * Fetches the current state of a call room.
     */
    suspend fun getRoom(roomId: String): CallRoom?

    /**
     * Deletes a call room record.
     */
    suspend fun deleteRoom(roomId: String): Result<Unit>

    /**
     * Provides a stream of call room updates for signaling.
     */
    fun observeRoom(roomId: String): Flow<CallRoom?>
}
