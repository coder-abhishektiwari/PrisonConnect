package com.example.prisonconnect.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.domain.model.CallRoom
import com.example.prisonconnect.domain.repository.CallRepository
import com.example.prisonconnect.data.repository.SupabaseCallRepository
import com.example.prisonconnect.domain.repository.UserRepository
import com.example.prisonconnect.data.repository.SupabaseUserRepository
import com.example.prisonconnect.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Shared ViewModel logic for both Audio and Video calls.
 *
 * Manages:
 * - Call room lifecycle (observation, status updates, deletion)
 * - Call timer and balance tracking
 * - Periodic balance sync with the server
 *
 * Subclasses ([CallViewModel]) extend this with signaling-specific logic.
 */
abstract class BaseCallViewModel : ViewModel() {

    protected open val TAG = "BaseCallViewModel"
    protected open val logger = Logger(TAG)

    /** Repository for call room operations. Protected for subclass access. */
    protected val callRepository: CallRepository = SupabaseCallRepository()

    /** Repository for user data operations. Protected for subclass access. */
    protected val userRepository: UserRepository = SupabaseUserRepository()

    // Call state
    private val _callRoom = MutableStateFlow<CallRoom?>(null)
    val callRoom: StateFlow<CallRoom?> = _callRoom.asStateFlow()

    private val _roomStatus = MutableStateFlow("WAITING")
    val roomStatus: StateFlow<String> = _roomStatus.asStateFlow()

    // Timer and Balance
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _remainingBalance = MutableStateFlow(0L)
    val remainingBalance: StateFlow<Long> = _remainingBalance.asStateFlow()

    private var timerJob: Job? = null
    @Suppress("unused")
    private var syncJob: Job? = null

    // Inmate details
    protected var inmateId: String = ""
    protected var roomId: String = ""

    companion object {
        private const val BALANCE_SYNC_INTERVAL_SECONDS = 10L
    }

    /**
     * Initializes the call with inmate and room details.
     *
     * @param inmateId The inmate's user ID
     * @param roomId The call room ID
     * @param initialBalance Starting balance in seconds
     */
    fun initCall(inmateId: String, roomId: String, initialBalance: Long) {
        this.inmateId = inmateId
        this.roomId = roomId
        this._remainingBalance.value = initialBalance
        logger.d("Call initialized: inmate=$inmateId, room=$roomId, balance=$initialBalance")

        observeRoom()
    }

    /**
     * Observes the call room status via polling-based flow.
     */
    private fun observeRoom() {
        viewModelScope.launch {
            callRepository.observeRoom(roomId)
                .onEach { room ->
                    _callRoom.value = room
                    room?.room_status?.let { status ->
                        _roomStatus.value = status.uppercase()
                    }
                }
                .catch { throwable ->
                    logger.e("Error observing room", throwable)
                }
                .collect()
        }
    }

    /**
     * Starts the call timer and balance countdown.
     *
     * Decrements balance every second and triggers a server sync
     * every [BALANCE_SYNC_INTERVAL_SECONDS] seconds. When balance
     * reaches zero, the call is terminated via [onBalanceExhausted].
     */
    fun startCallTimer() {
        if (timerJob?.isActive == true) {
            logger.d("Timer already running, skipping")
            return
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value += 1
                _remainingBalance.value -= 1

                if (_remainingBalance.value <= 0) {
                    onBalanceExhausted()
                }

                // Periodic balance sync
                if (_elapsedSeconds.value % BALANCE_SYNC_INTERVAL_SECONDS == 0L) {
                    syncBalance()
                }
            }
        }
        logger.d("Call timer started")
    }

    /**
     * Syncs the current balance to the server.
     */
    private fun syncBalance() {
        val currentBalance = _remainingBalance.value
        if (inmateId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                userRepository.updateBalance(inmateId, currentBalance)
                    .onFailure { throwable ->
                        logger.e("Balance sync failed", throwable)
                    }
            }
        }
    }

    /**
     * Called when the call balance has been exhausted.
     * Terminates the call by setting room status to DISCONNECTED.
     */
    private fun onBalanceExhausted() {
        logger.w("Balance exhausted, terminating call.")
        updateRoomStatus("DISCONNECTED")
        timerJob?.cancel()
    }

    /**
     * Updates the room status in the database.
     *
     * @param status The new status (e.g., "CONNECTED", "DISCONNECTED")
     */
    fun updateRoomStatus(status: String) {
        viewModelScope.launch {
            callRepository.updateRoomStatus(roomId, status)
                .onFailure { throwable ->
                    logger.e("Failed to update room status to $status", throwable)
                }
        }
    }

    /**
     * Deletes the call room record from the database.
     */
    fun deleteRoom() {
        viewModelScope.launch {
            callRepository.deleteRoom(roomId)
                .onFailure { throwable ->
                    logger.e("Failed to delete room", throwable)
                }
        }
    }

    /**
     * Persists the final balance to the server before the ViewModel is cleared.
     *
     * Uses a separate [NonCancellable] scope to ensure the write completes
     * even if the ViewModel scope is cancelled during cleanup.
     */
    fun persistFinalBalance() {
        val finalBalance = _remainingBalance.value.coerceAtLeast(0)
        logger.d("Persisting final balance: $finalBalance")

        // Use NonCancellable to ensure this completes even if VM is cleared
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            userRepository.updateBalance(inmateId, finalBalance)
                .onFailure { throwable ->
                    logger.e("Failed to persist final balance", throwable)
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        syncJob?.cancel()
        logger.d("BaseCallViewModel cleared")
    }
}