package com.example.prisonconnect.repository

import com.example.prisonconnect.model.CallRoom
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

class SupabaseCallRepository : CallRepository {
    override suspend fun createRoom(roomData: Map<String, Any>): Result<Unit> {
        return DbService.insertRaw("call_rooms", roomData)
    }

    override suspend fun updateRoomStatus(roomId: String, status: String): Result<Unit> {
        return DbService.updateFieldsByColumn(
            table = "call_rooms",
            column = "room_id",
            columnValue = roomId,
            fields = mapOf("room_status" to status)
        )
    }

    override suspend fun getRoom(roomId: String): CallRoom? {
        return DbService.getDocumentByColumn<CallRoom>(
            table = "call_rooms",
            column = "room_id",
            value = roomId
        )
    }

    override suspend fun deleteRoom(roomId: String): Result<Unit> {
        return DbService.deleteByColumn(
            table = "call_rooms",
            column = "room_id",
            value = roomId
        )
    }

    override fun observeRoom(roomId: String): Flow<CallRoom?> {
        // Note: DbService.documentFlow uses 'id' (UUID), but we need to poll by 'room_id' (String)
        // I should add a queryFlow variant for single documents by column or improve DbService.
        return kotlinx.coroutines.flow.flow {
            while (true) {
                emit(getRoom(roomId))
                kotlinx.coroutines.delay(3000L)
            }
        }
    }
}