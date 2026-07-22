package com.example.prisonconnect.data.repository

import com.example.prisonconnect.domain.model.CallRoom
import com.example.prisonconnect.domain.repository.CallRepository
import com.example.prisonconnect.data.remote.DbService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay

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
        return kotlinx.coroutines.flow.flow {
            while (true) {
                emit(getRoom(roomId))
                delay(3000L)
            }
        }
    }
}
