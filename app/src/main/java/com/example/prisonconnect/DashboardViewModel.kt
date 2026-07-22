package com.example.prisonconnect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.prisonconnect.model.Contact
import com.example.prisonconnect.model.User
import com.example.prisonconnect.repository.ContactRepository
import com.example.prisonconnect.repository.SupabaseContactRepository
import com.example.prisonconnect.repository.UserRepository
import com.example.prisonconnect.repository.SupabaseUserRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {

    private val TAG = "DashboardViewModel"
    private val userRepository: UserRepository = SupabaseUserRepository()
    private val contactRepository: ContactRepository = SupabaseContactRepository()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadData(userId: String) {
        _isLoading.value = true
        viewModelScope.launch {
            launch {
                userRepository.observeUser(userId)
                    .onEach { _user.value = it }
                    .catch { Log.e(TAG, "Error observing user", it) }
                    .collect()
            }
            launch {
                contactRepository.observeContacts(userId)
                    .onEach { 
                        _contacts.value = it
                        _isLoading.value = false
                    }
                    .catch { 
                        Log.e(TAG, "Error observing contacts", it)
                        _isLoading.value = false
                    }
                    .collect()
            }
        }
    }
}