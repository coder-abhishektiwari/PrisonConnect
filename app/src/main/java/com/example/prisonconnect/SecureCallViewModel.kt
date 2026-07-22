package com.example.prisonconnect

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.repository.DbService
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for polling-based room status monitoring.
 *
 * This ViewModel is retained for backward compatibility but may be
 * redundant with [com.example.prisonconnect.webrtc.BaseCallViewModel] which
 * provides similar functionality with a more robust implementation.
 *
 * Uses polling with LiveData instead of StateFlow for compatibility with
 * existing consumers.
 *
 * @deprecated Consider migrating to [com.example.prisonconnect.webrtc.BaseCallViewModel]
 *             for new implementations.
 */
@Deprecated("Use BaseCallViewModel for room status monitoring instead")
class SecureCallViewModel : ViewModel() {

    private val logger = Logger("SecureCallVM")

    private val _roomStatus = MutableLiveData<String?>()
    val roomStatus: LiveData<String?> = _roomStatus

    private var pollingJob: Job? = null

    companion object {
        /** Polling interval in milliseconds. */
        private const val POLL_INTERVAL_MS = 3000L
    }

    /**
     * Starts polling the call room status for the given room ID.
     *
     * @param roomId The call room ID to monitor
     */
    fun startListening(roomId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val status = DbService.getDocument<Map<String, String>>(
                        table = "call_rooms",
                        id = roomId
                    )
                    _roomStatus.postValue(status?.get("room_status"))
                } catch (_: Exception) {
                    // Silently retry on next poll cycle
                    logger.d("Room status poll failed, will retry")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        logger.d("Started polling for room: $roomId")
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}