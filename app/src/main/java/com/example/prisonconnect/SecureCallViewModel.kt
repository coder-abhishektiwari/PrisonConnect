package com.example.prisonconnect

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.repository.DbService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SecureCallViewModel : ViewModel() {

    private val _roomStatus = MutableLiveData<String?>()
    val roomStatus: LiveData<String?> = _roomStatus

    private var pollingJob: Job? = null

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
                    // Silently retry
                }
                delay(3000L)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}