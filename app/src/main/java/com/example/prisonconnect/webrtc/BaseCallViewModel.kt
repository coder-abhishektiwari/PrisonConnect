package com.example.prisonconnect.webrtc

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.model.CallRoom
import com.example.prisonconnect.repository.CallRepository
import com.example.prisonconnect.repository.SupabaseCallRepository
import com.example.prisonconnect.repository.UserRepository
import com.example.prisonconnect.repository.SupabaseUserRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Shared ViewModel logic for both Audio and Video calls.
 * Manages signaling, room lifecycle, and call timer.
 */
abstract class BaseCallViewModel : ViewModel() {

    protected open val TAG = "BaseCallViewModel"

    private val callRepository: CallRepository = SupabaseCallRepository()
    private val userRepository: UserRepository = SupabaseUserRepository()

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
    private var syncJob: Job? = null
    
    // Inmate details
    protected var inmateId: String = ""
    protected var roomId: String = ""

    fun initCall(inmateId: String, roomId: String, initialBalance: Long) {
        this.inmateId = inmateId
        this.roomId = roomId
        this._remainingBalance.value = initialBalance
        
        observeRoom()
    }

    private fun observeRoom() {
        viewModelScope.launch {
            callRepository.observeRoom(roomId)
                .onEach { room ->
                    _callRoom.value = room
                    room?.room_status?.let { status ->
                        _roomStatus.value = status.uppercase()
                    }
                }
                .catch { Log.e(TAG, "Error observing room", it) }
                .collect()
        }
    }

    fun startCallTimer() {
        if (timerJob?.isActive == true) return
        
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value += 1
                _remainingBalance.value -= 1

                if (_remainingBalance.value <= 0) {
                    onBalanceExhausted()
                }
                
                // Periodic balance sync (every 10 seconds)
                if (_elapsedSeconds.value % 10 == 0L) {
                    syncBalance()
                }
            }
        }
    }

    private fun syncBalance() {
        val currentBalance = _remainingBalance.value
        val uid = inmateId
        if (uid.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                userRepository.updateBalance(uid, currentBalance)
            }
        }
    }

    private fun onBalanceExhausted() {
        Log.w(TAG, "Balance exhausted, terminating call.")
        updateRoomStatus("DISCONNECTED")
        timerJob?.cancel()
    }

    fun updateRoomStatus(status: String) {
        viewModelScope.launch {
            callRepository.updateRoomStatus(roomId, status)
        }
    }

    fun deleteRoom() {
        viewModelScope.launch {
            callRepository.deleteRoom(roomId)
        }
    }

    fun persistFinalBalance() {
        val finalBalance = _remainingBalance.value.coerceAtLeast(0)
        Log.d(TAG, "Persisting final balance: $finalBalance")
        
        // Use a separate scope to ensure this completes even if VM is cleared
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            userRepository.updateBalance(inmateId, finalBalance)
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        syncJob?.cancel()
    }
}